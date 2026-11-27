package com.wangzan101.mika.core.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 主屏状态,与 Agent 的虚拟屏无关。读不到状态时由端口报错,不推定已解锁。 */
data class DeviceState(val interactive: Boolean, val keyguardShowing: Boolean, val deviceLocked: Boolean,
    val secure: Boolean, val helperAvailable: Boolean, val userUnlocked: Boolean) {
    val ready: Boolean get() = interactive && !keyguardShowing && !deviceLocked && userUnlocked
}

enum class CredentialKind { Pin, Password }

/**
 * 一次凭据输入的结果。[NotSent] 必须是确定一个键都没送到锁屏;拿不准(助手中断、只送达前几位)一律算 [Incomplete]。
 * [Sent] 只表示按键全部送达,不代表系统接受了凭据。
 */
enum class CredentialEntry { NotSent, Incomplete, Sent }

/** 凭据只在本机确定性流程内短暂存在;不提供带原文的 toString。用完擦除。 */
class DeviceCredential(val kind: CredentialKind, val value: CharArray, val revision: String = "") : AutoCloseable {
    override fun close() { value.fill('\u0000') }
    override fun toString() = "DeviceCredential($kind, redacted)"

    companion object {
        fun valid(kind: CredentialKind, value: CharArray): Boolean = when (kind) {
            CredentialKind.Pin -> value.size in 4..16 && value.all { it in '0'..'9' }
            CredentialKind.Password -> value.size in 4..64 && value.all { it in ' '..'~' }
        }
    }
}

/** 平台适配:唤醒、显示系统凭据页、确认输入目标、输入。绝不改变系统锁屏凭据。 */
interface DeviceAccessPort {
    suspend fun state(): DeviceState
    suspend fun wake(): Boolean
    suspend fun dismiss(): Boolean
    /** 安全锁屏还停在通知/指纹页时,尝试一次通用的系统导航来露出凭据页;不识别文字,不输入凭据。 */
    suspend fun revealCredential(): Boolean = false
    /** 上滑后仍是生物识别优先页、没有凭据框时,做一次通用的回退点击(点最大的可点目标);找不到目标就不动。 */
    suspend fun revealCredentialFallback(): Boolean = false
    suspend fun credentialReady(kind: CredentialKind): Boolean
    /** 只读识别当前唯一的系统凭据框;未知、歧义或平台不支持时返回 null,不聚焦或输入。 */
    suspend fun detectedCredentialKind(): CredentialKind? = null
    /** 认不出凭据页时给出不含屏上文字和凭据的受限诊断,便于定位通用识别规则的缺口。 */
    suspend fun credentialDiagnostics(): List<String> = emptyList()
    suspend fun enter(credential: DeviceCredential): CredentialEntry
}

/**
 * 许可和凭据来自手机设置,电脑不能修改。输入前持久化占位,失败/取消/崩溃后不再自动重试。
 * 占位防的是把错误凭据反复提交给系统;确定一个键都没送达的那次不算尝试,由 [releaseAttempt] 撤掉这次的占位。
 */
interface DeviceAccessStore {
    val enabled: Boolean
    /** 本机任务的独立开关,与 [enabled] 互不带动;默认关。 */
    val localEnabled: Boolean get() = false
    val credentialConfigured: Boolean
    val attemptBlocked: Boolean
    fun credential(): DeviceCredential?
    fun beginAttempt(credential: DeviceCredential): Boolean
    /** 本机任务输入凭据前落自己的占位;开关关着或已暂停时返回 false。 */
    fun beginLocalAttempt(): Boolean = false
    fun completeAttempt(credential: DeviceCredential)
    /**
     * 撤掉这次调用自己落的那一个占位([local] 为本机占位,否则为远程占位),只在确定一个键都没送达锁屏时用;
     * 另一个占位不动。缺省什么都不做,即保留占位:宁可让用户重设一次,也不重复提交。
     */
    fun releaseAttempt(credential: DeviceCredential, local: Boolean) {}
}

data class DeviceAccessResult(val status: String, val state: DeviceState?, val enabled: Boolean,
    val credentialConfigured: Boolean, val attemptBlocked: Boolean,
    val storedKind: CredentialKind? = null, val detectedKind: CredentialKind? = null,
    val keyguardDiagnostics: List<String> = emptyList(),
    /** 这次流程真的把手机从熄屏/锁屏带到可用(唤醒、上滑或注入凭据);用户中途自己解开的不算。 */
    val performedUnlock: Boolean = false)

/** 整个进程共用一份。并发调用串行化,成功依据系统状态,不依据按键或命令的返回码。 */
class DeviceAccess(private val port: DeviceAccessPort, private val store: DeviceAccessStore,
    private val pollMs: Long = 150, private val waitMs: Long = 5_000) {
    private val mutex = Mutex()

    suspend fun state(): DeviceAccessResult {
        val s = read()
        return result(if (s == null) "device_unavailable" else "state", s)
    }

    suspend fun wake(): DeviceAccessResult = mutex.withLock {
        prepare(local = false) { true }?.let { return@withLock it }
        val before = read() ?: return@withLock result("device_unavailable")
        if (before.interactive) return@withLock result("awake", before)
        if (!port.wake()) return@withLock result("wake_failed", read())
        val after = await { it.interactive }
        result(if (after?.interactive == true) "awake" else "wake_failed", after)
    }

    /** 远程来源(连接电脑)的解锁:只认远程开关。 */
    suspend fun unlock(authorized: () -> Boolean = { true }): DeviceAccessResult = unlockUsing(local = false, authorized)

    /** 本机任务(界面发起/定时)的解锁:只认本机开关,与远程开关互不影响。 */
    suspend fun unlockLocal(authorized: () -> Boolean = { true }): DeviceAccessResult = unlockUsing(local = true, authorized)

    /** 来源授权只能进一步收窄对应开关；等待锁和系统界面后、每次操作前都重新核对。 */
    private suspend fun unlockUsing(local: Boolean, authorized: () -> Boolean): DeviceAccessResult = mutex.withLock {
        fun allowed() = (if (local) store.localEnabled else store.enabled) && authorized()
        prepare(local, authorized)?.let { return@withLock it }
        var now = read() ?: return@withLock result("device_unavailable")
        if (now.ready) return@withLock result("unlocked", now)
        var woke = false
        if (!now.interactive) {
            if (!allowed()) return@withLock result("disabled", now)
            if (!port.wake()) return@withLock result("wake_failed", read())
            woke = true
            now = await { !allowed() || it.interactive } ?: return@withLock result("device_unavailable")
            if (!allowed()) return@withLock result("disabled", now)
            if (!now.interactive) return@withLock result("wake_failed", now)
        }
        if (!allowed()) return@withLock result("disabled", now)
        val wasSecure = now.secure
        // 这次流程刚把屏幕唤醒时锁屏可能还没准备好接受 dismiss:在同一个有界等待([waitMs])里重试,每次前复核授权
        if (!retrying(enabled = woke, ::allowed) { port.dismiss() }) {
            return@withLock result(if (allowed()) "dismiss_failed" else "disabled", read())
        }
        now = await { !allowed() || it.ready || it.secure && it.keyguardShowing } ?: return@withLock result("device_unavailable")
        if (!allowed()) return@withLock result("disabled", now)
        // 非安全锁屏由这次流程上滑解开、或这次流程唤醒了熄屏:算这次解的;安全锁屏没输入凭据就开了是用户同时自己解的
        if (now.ready) return@withLock result("unlocked", now, performedUnlock = woke || !wasSecure)
        if (!now.secure) return@withLock result("unlock_failed", now)
        // 远程分流在加载凭据前先看占位,省一次解密;本机分流的独立占位在输入前由 beginLocalAttempt 原子检查
        if (!local && store.attemptBlocked) return@withLock result("attempt_blocked", now)
        val credential = store.credential() ?: return@withLock result("credential_required", now)
        credential.use {
            if (!DeviceCredential.valid(it.kind, it.value)) return@withLock result("credential_unavailable", now)
            var revealedAt = -1L
            var fallbackTried = false
            var waited = 0L
            for (round in 1..ENTRY_ROUNDS) {
                var targetReady = false
                val deadline = waited + waitMs
                while (!targetReady && waited < deadline) {
                    val current = read() ?: return@withLock result("device_unavailable")
                    if (current.ready) return@withLock result("unlocked", current)
                    if (!allowed()) return@withLock result("disabled", current)
                    if (!current.helperAvailable) return@withLock result("helper_unavailable", current)
                    targetReady = port.credentialReady(it.kind)
                    // 凭据页滑入和无障碍树更新都要时间。手势发早了会落在正在出现的凭据页上(划过或点中数字键),
                    // 多出来的一位会让正确的凭据被系统判错。所以等够了仍认不出才导航,回退点击再等上滑后的页面稳定。
                    if (!targetReady) {
                        if (revealedAt < 0 && waited >= pollMs * REVEAL_AFTER_POLLS) {
                            if (!allowed()) return@withLock result("disabled", current)
                            revealedAt = waited
                            port.revealCredential()
                        } else if (revealedAt >= 0 && !fallbackTried && waited - revealedAt >= pollMs * FALLBACK_AFTER_POLLS) {
                            // 上滑后仍是生物识别优先页(没有凭据框)时,再给一次回退点击的机会。
                            if (!allowed()) return@withLock result("disabled", current)
                            fallbackTried = true
                            port.revealCredentialFallback()
                        }
                        delay(pollMs); waited += pollMs
                    }
                }
                if (!targetReady) {
                    val current = read() ?: return@withLock result("device_unavailable")
                    if (current.ready) return@withLock result("unlocked", current)
                    if (!allowed()) return@withLock result("disabled", current)
                    if (!current.helperAvailable) return@withLock result("helper_unavailable", current)
                    val detected = port.detectedCredentialKind()
                    val diagnostics = port.credentialDiagnostics()
                    if (detected != null && detected != it.kind) {
                        return@withLock result("credential_kind_mismatch", current, it.kind, detected, diagnostics)
                    }
                    return@withLock result("unsupported_keyguard", current, keyguardDiagnostics = diagnostics)
                }
                if (!allowed()) return@withLock result("disabled", read())
                val reserved = if (local) store.beginLocalAttempt() else store.beginAttempt(it)
                if (!reserved) return@withLock result("attempt_blocked", read())
                // 从此刻起中断也保留占位,不重复输入。只有系统已解锁、或确定一个键都没送达才清掉。
                if (!allowed()) return@withLock result("disabled", read())
                val entry = port.enter(it)
                if (entry == CredentialEntry.NotSent) {
                    // 凭据页在最后一刻失焦、被收起或熄屏:锁屏什么都没收到,系统不可能记一次错误凭据。
                    // 留着占位只会让用户白白重设一次,所以撤掉;同一次调用里只重来一轮,导航手势整次调用仍各只做一次。
                    // 只撤这次自己落的那个占位:另一个可能是之前真失败落下的,不能顺手清掉。
                    store.releaseAttempt(it, local)
                    val current = read() ?: return@withLock result("device_unavailable")
                    if (current.ready) return@withLock result("unlocked", current)
                    if (round == ENTRY_ROUNDS) return@withLock result("input_failed", current)
                    delay(pollMs); waited += pollMs
                    continue
                }
                // 按键没全送达也照样等系统状态:最后一位自动提交或回车生效后锁屏正在退场,焦点已离开 SystemUI,
                // 收尾按键会被拒;只查一次会把这次成功的解锁当成失败,占位就再也清不掉。
                now = await { s -> s.ready } ?: return@withLock result("device_unavailable")
                if (now.ready) { store.completeAttempt(it); return@withLock result("unlocked", now, performedUnlock = true) }
                return@withLock result(if (entry == CredentialEntry.Sent) "unlock_failed" else "input_failed", now)
            }
            result("input_failed", read())
        }
    }

    private suspend fun prepare(local: Boolean, authorized: () -> Boolean): DeviceAccessResult? {
        val on = if (local) store.localEnabled else store.enabled
        if (!on || !authorized()) return result("disabled", read())
        val s = read() ?: return result("device_unavailable")
        if (!s.userUnlocked) return result("first_unlock_required", s)
        if (!s.helperAvailable) return result("helper_unavailable", s)
        return null
    }

    private suspend fun read(): DeviceState? = try { port.state() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    private suspend fun await(done: (DeviceState) -> Boolean): DeviceState? {
        var waited = 0L
        var s = read()
        while (s != null && !done(s) && waited < waitMs) { delay(pollMs); waited += pollMs; s = read() }
        return s
    }
    /** [action] 成功就返回 true;[enabled] 时失败了隔 [pollMs] 再试,总共不超过 [waitMs],[allowed] 一收回就停。 */
    private suspend fun retrying(enabled: Boolean, allowed: () -> Boolean, action: suspend () -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (action()) return true
            if (!enabled || waited >= waitMs || !allowed()) return false
            delay(pollMs); waited += pollMs
        }
    }
    private fun result(status: String, s: DeviceState? = null, storedKind: CredentialKind? = null, detectedKind: CredentialKind? = null,
        keyguardDiagnostics: List<String> = emptyList(), performedUnlock: Boolean = false) =
        DeviceAccessResult(status, s, store.enabled, store.credentialConfigured, store.attemptBlocked, storedKind, detectedKind,
            keyguardDiagnostics, performedUnlock)

    private companion object {
        /** 认不出凭据页多久后才上滑,以及上滑后多久才回退点击,单位是轮询周期。 */
        const val REVEAL_AFTER_POLLS = 3
        const val FALLBACK_AFTER_POLLS = 4
        /** 一个键都没送达时,同一次调用里总共输入几轮。 */
        const val ENTRY_ROUNDS = 2
    }
}
