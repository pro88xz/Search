package com.search.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The calculator answer in the search box (Calc): what counts as a calculation and how answers read. */
class CalcTest {

    @Test fun answersArithmetic() {
        assertEquals("4", Calc.answer("2+2"))
        assertEquals("42", Calc.answer("6*7"))
        assertEquals("9", Calc.answer("(1+2)*3"))
        assertEquals("2.5", Calc.answer("10/4"))
        assertEquals("1024", Calc.answer("2^10"))
        assertEquals("-10", Calc.answer("-(2+3)*2"))
        assertEquals("7", Calc.answer(" 1 + 2 * 3 "))
    }

    @Test fun powerIsRightAssociative() {
        assertEquals("512", Calc.answer("2^3^2"))
    }

    @Test fun roundsAwayFloatingPointNoise() {
        assertEquals("0.3", Calc.answer("0.1+0.2"))
        assertEquals("0.333333333333", Calc.answer("1/3"))
    }

    @Test fun acceptsOtherKeyboardsSigns() {
        assertEquals("12", Calc.answer("= 3 × 4"))
        assertEquals("3.5", Calc.answer("7 ÷ 2"))
        assertEquals("1", Calc.answer("3 − 2"))
    }

    @Test fun hugeAndTinyResultsUseExponents() {
        assertEquals("1E+20", Calc.answer("10^20"))
        assertEquals("1E-8", Calc.answer("1/100000000"))
    }

    @Test fun ignoresWhatIsNotACalculation() {
        assertNull(Calc.answer("5"))
        assertNull(Calc.answer("-5"))
        assertNull(Calc.answer("(3)"))
        assertNull(Calc.answer("hello"))
        assertNull(Calc.answer("2+"))
        assertNull(Calc.answer("2+x"))
        assertNull(Calc.answer("1,5+1"))
        assertNull(Calc.answer("1.2.3+1"))
        assertNull(Calc.answer(""))
        assertNull(Calc.answer("((1+2)"))
    }

    @Test fun ignoresDatesAndPhoneNumbers() {
        assertNull(Calc.answer("2024-10-05"))
        assertNull(Calc.answer("10/05/2024"))
        assertNull(Calc.answer("555-123-4567"))
    }

    @Test fun noAnswerForDivisionByZero() {
        assertNull(Calc.answer("1/0"))
    }
}
