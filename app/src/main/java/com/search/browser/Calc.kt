package com.search.browser

import java.math.BigDecimal
import java.math.MathContext

/**
 * The calculator answer in the search box: typing "12*7" offers "= 84" above
 * the suggestions, as Chrome's omnibox answers do. Worked out on the phone, so
 * it shows in Night Owl and with search suggestions off.
 *
 * A small parser of its own rather than a script engine: + - * / ^, the
 * signs people type from other keyboards (x is not one, it is too often a
 * letter), brackets and decimals. Anything else - a word, a comma, a trailing
 * operator - is not a calculation and gets no answer. So does a bare number,
 * and a date or a phone number written with one repeated separator
 * (2024-10-05, 555-123-4567), which would otherwise "calculate".
 */
object Calc {

    private const val MAX_LEN = 80
    private val DATE_LIKE = Regex("^\\d+([-/.])\\d+\\1\\d+$")
    private val LIMIT_BIG = BigDecimal("1e15")
    private val LIMIT_SMALL = BigDecimal("1e-6")

    fun answer(input: String): String? {
        var s = input.trim()
        if (s.startsWith("=")) s = s.substring(1).trim()
        if (s.isEmpty() || s.length > MAX_LEN) return null
        s = s.replace('×', '*').replace('÷', '/').replace('−', '-')
        if (DATE_LIKE.matches(s.replace(" ", ""))) return null
        val p = Parser(s)
        val v = try { p.parseAll() } catch (e: IllegalArgumentException) { return null }
        if (p.operators == 0 || v.isNaN() || v.isInfinite()) return null
        return format(v)
    }

    internal fun format(v: Double): String {
        if (v == 0.0) return "0"
        val bd = BigDecimal(v).round(MathContext(12)).stripTrailingZeros()
        val abs = bd.abs()
        return if (abs >= LIMIT_BIG || abs < LIMIT_SMALL) bd.toString() else bd.toPlainString()
    }

    /** expr = term (+|- term)*, term = power (*|/ power)*, power = unary (^ power)?, unary = (+|-) unary | atom. */
    private class Parser(private val s: String) {
        private var i = 0
        var operators = 0

        fun parseAll(): Double {
            val v = expr()
            skip()
            if (i != s.length) fail()
            return v
        }

        private fun fail(): Nothing = throw IllegalArgumentException()

        private fun skip() { while (i < s.length && s[i] == ' ') i++ }

        private fun peek(): Char? { skip(); return if (i < s.length) s[i] else null }

        private fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; operators++; v += term() }
                    '-' -> { i++; operators++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    '*' -> { i++; operators++; v *= power() }
                    '/' -> { i++; operators++; v /= power() }
                    else -> return v
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                i++
                operators++
                return Math.pow(base, power())
            }
            return base
        }

        private fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> atom()
        }

        private fun atom(): Double {
            val c = peek() ?: fail()
            if (c == '(') {
                i++
                val v = expr()
                if (peek() != ')') fail()
                i++
                return v
            }
            val start = i
            var dots = 0
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                if (s[i] == '.') dots++
                i++
            }
            val num = s.substring(start, i)
            if (num.isEmpty() || dots > 1 || num == ".") fail()
            return num.toDouble()
        }
    }
}
