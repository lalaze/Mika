package com.wangzan101.mika.device

import com.wangzan101.mika.core.device.CredentialKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyguardFieldsTest {
    @Test fun stockKeyguardSelfDrawnPinFieldDoesNotNeedEditable() {
        assertTrue(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/pinEntry",
            "com.android.keyguard.PasswordTextView", true, false, false))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/pinEntry",
            "com.android.keyguard.PasswordTextView", true, false, false))
    }

    @Test fun editableStockPinWithInputType16OnlyMatchesPinRegardlessOfDigitPad() {
        // TYPE_NUMBER_VARIATION_PASSWORD alone (16) has no TYPE_CLASS_NUMBER bits and is not TYPE_NULL.
        val inputType = 16
        val numeric = inputType and 0x0f == 2
        val untyped = inputType == 0
        assertFalse(numeric)
        assertFalse(untyped)
        for (digitPad in listOf(false, true)) {
            val kinds = CredentialKind.entries.filter { kind ->
                KeyguardFields.matches(kind, "com.android.systemui:id/pinEntry", "com.android.keyguard.PasswordTextView",
                    password = true, editable = true, numeric = numeric, untyped = untyped, digitPad = digitPad)
            }
            assertEquals(listOf(CredentialKind.Pin), kinds)
        }
    }

    @Test fun stockPinIsExclusiveForAllEditableAndInputTypeFlags() {
        for (editable in listOf(false, true)) for (numeric in listOf(false, true)) {
            for (untyped in listOf(false, true)) for (digitPad in listOf(false, true)) {
                val kinds = CredentialKind.entries.filter { kind ->
                    KeyguardFields.matches(kind, "com.android.systemui:id/pinEntry", "com.android.keyguard.PasswordTextView",
                        password = true, editable = editable, numeric = numeric, untyped = untyped, digitPad = digitPad)
                }
                assertEquals(listOf(CredentialKind.Pin), kinds)
            }
        }
    }

    @Test fun pinEntryWithWrongClassDoesNotUseStockPinBranch() {
        for (digitPad in listOf(false, true)) {
            val kinds = CredentialKind.entries.filter { kind ->
                KeyguardFields.matches(kind, "com.android.systemui:id/pinEntry", "android.widget.EditText",
                    password = true, editable = true, numeric = false, untyped = false, digitPad = digitPad)
            }
            assertEquals(listOf(CredentialKind.Password), kinds)
            for (kind in CredentialKind.entries) {
                assertFalse(KeyguardFields.matches(kind, "com.android.systemui:id/pinEntry", "android.widget.EditText",
                    password = true, editable = false, numeric = false, untyped = false, digitPad = digitPad))
            }
        }
    }

    @Test fun nonStockInputType16DoesNotBecomePin() {
        val inputType = 16
        for (id in listOf("com.android.systemui:id/passwordEntry", "com.android.systemui:id/bouncer_text_entry")) {
            for (className in listOf("android.widget.EditText", "com.android.keyguard.PasswordTextView")) {
                for (digitPad in listOf(false, true)) {
                    val kinds = CredentialKind.entries.filter { kind ->
                        KeyguardFields.matches(kind, id, className, password = true, editable = true,
                            numeric = inputType and 0x0f == 2, untyped = inputType == 0, digitPad = digitPad)
                    }
                    assertEquals(listOf(CredentialKind.Password), kinds)
                }
            }
        }
    }

    @Test fun stockPinBranchCannotBypassPasswordNamespaceOrSimGates() {
        val rejected = listOf(
            "com.android.systemui:id/pinEntry" to false,
            "other:id/pinEntry" to true,
            "com.android.systemui.extra:id/pinEntry" to true,
            "com.android.systemui:idFake/pinEntry" to true,
            "com.android.systemui:id/simPinEntry" to true,
            "com.android.systemui:id/SIMPinEntry" to true,
        )
        for ((id, password) in rejected) for (kind in CredentialKind.entries) {
            for (editable in listOf(false, true)) for (numeric in listOf(false, true)) {
                assertFalse(KeyguardFields.matches(kind, id, "com.android.keyguard.PasswordTextView", password,
                    editable = editable, numeric = numeric, untyped = true, digitPad = true))
            }
        }
    }

    @Test fun editablePasswordAndPinHaveDistinctTypes() {
        assertTrue(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/passwordEntry", "android.widget.EditText", true, true, false))
        assertTrue(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/pinEntry", "android.widget.EditText", true, true, true))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/pinEntry", "android.widget.EditText", true, true, true))
    }

    @Test fun unknownCustomFieldsAndSimPinsAreNeverEntered() {
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/simPinEntry", "com.android.keyguard.PasswordTextView", true, true, true))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "other:id/pinEntry", "com.android.keyguard.PasswordTextView", true, true, true))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/unknown", "CustomPasswordView", true, false, false))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/passwordEntry", "android.widget.EditText", false, true, false))
    }

    @Test fun untypedFieldIsPinWhenTheWindowHasADigitPadAndPasswordOtherwise() {
        val id = "com.android.systemui:id/bouncer_text_entry"
        assertTrue(KeyguardFields.matches(CredentialKind.Pin, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = true))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = true))
        assertTrue(KeyguardFields.matches(CredentialKind.Password, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = false))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, id, "android.widget.EditText", true, true, false, untyped = true, digitPad = false))
        // 声明了文字输入类型的密码框,旁边有数字也还是密码框
        assertTrue(KeyguardFields.matches(CredentialKind.Password, id, "android.widget.EditText", true, true, false, untyped = false, digitPad = true))
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, id, "android.widget.EditText", true, true, false, untyped = false, digitPad = true))
    }

    @Test fun patternViewsNeverBecomeCredentialFields() {
        assertFalse(KeyguardFields.matches(CredentialKind.Pin, "com.android.systemui:id/lockPatternView",
            "android.view.View", false, false, false, untyped = true, digitPad = true))
        assertFalse(KeyguardFields.matches(CredentialKind.Password, "com.android.systemui:id/lockPatternView",
            "android.view.View", false, false, false, untyped = true, digitPad = true))
    }

    @Test fun digitKeysAreSingleDigitsOptionallyWithLetters() {
        assertEquals('1', KeyguardFields.digitKey("1", null))
        assertEquals('2', KeyguardFields.digitKey(null, "2 ABC"))
        assertEquals('0', KeyguardFields.digitKey(" 0 ", "0 +"))
        assertNull(KeyguardFields.digitKey("12:30", null))
        assertNull(KeyguardFields.digitKey("5G", null))
        assertNull(KeyguardFields.digitKey("", "Enter"))
        assertTrue(KeyguardFields.isDigitPad(('0'..'9').toSet()))
        assertFalse(KeyguardFields.isDigitPad(('1'..'9').toSet()))
    }
}
