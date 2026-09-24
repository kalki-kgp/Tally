package dev.pixelchutney.tally.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Every amount in Tally is a whole number of paise. Rupee floats drift, and a
 * tracker that quietly loses a rupee a week is worse than no tracker.
 */
object Money {

    fun fromRupeeString(input: String): Long? {
        val cleaned = input.replace(",", "").replace("₹", "").replace("Rs.", "", ignoreCase = true)
            .replace("Rs", "", ignoreCase = true).replace("INR", "", ignoreCase = true).trim()
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        return (value * 100.0).roundToLong()
    }

    fun toRupees(paise: Long): Double = paise / 100.0

    /**
     * Amount entry is in rupees, with the paise optional.
     *
     * The numpad used to collect paise, so ₹240 had to be typed as 2-4-0-0-0 and
     * read as ₹2.40 along the way. Almost every payment is a whole number of
     * rupees, so the common case now costs three taps and the decimal point is
     * there for the rare one.
     */
    fun fromEntry(entry: String): Long {
        if (entry.isEmpty()) return 0L
        val dot = entry.indexOf('.')
        val rupeePart = (if (dot < 0) entry else entry.take(dot)).ifEmpty { "0" }
        val rupees = rupeePart.toLongOrNull() ?: return 0L
        val paise = if (dot < 0) 0L else {
            entry.drop(dot + 1).take(2).padEnd(2, '0').toLongOrNull() ?: 0L
        }
        return rupees * 100 + paise
    }

    /** Seeds the numpad from an amount that already exists. */
    fun toEntry(paise: Long): String {
        val magnitude = abs(paise)
        val rupees = magnitude / 100
        val remainder = magnitude % 100
        return if (remainder == 0L) rupees.toString()
        else "$rupees." + remainder.toString().padStart(2, '0')
    }

    /**
     * Renders entry as it is being typed. Unlike [format] this keeps a decimal
     * point the moment it is pressed, so the display reacts to the tap instead of
     * swallowing it until a paise digit arrives.
     */
    fun formatEntry(entry: String): String {
        if (entry.isEmpty()) return "₹0"
        val dot = entry.indexOf('.')
        val rupeePart = (if (dot < 0) entry else entry.take(dot)).ifEmpty { "0" }
        val head = groupIndian(rupeePart.toLongOrNull() ?: 0L)
        return if (dot < 0) "₹$head" else "₹$head.${entry.drop(dot + 1)}"
    }

    /** `₹1,240` — drops paise when they are zero, which is almost always. */
    fun format(paise: Long, withSymbol: Boolean = true): String {
        val sign = if (paise < 0) "-" else ""
        val magnitude = abs(paise)
        val rupees = magnitude / 100
        val remainder = magnitude % 100
        val body = if (remainder == 0L) {
            groupIndian(rupees)
        } else {
            groupIndian(rupees) + "." + remainder.toString().padStart(2, '0')
        }
        return if (withSymbol) "$sign₹$body" else "$sign$body"
    }

    /**
     * Indian digit grouping: the last three digits, then pairs.
     * 1234567 becomes 12,34,567 — `DecimalFormat` cannot express this, since it
     * supports only one grouping width.
     */
    fun groupIndian(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits

        val lastThree = digits.takeLast(3)
        val rest = digits.dropLast(3)

        val pairs = StringBuilder()
        var index = rest.length
        while (index > 0) {
            val start = maxOf(0, index - 2)
            if (pairs.isNotEmpty()) pairs.insert(0, ',')
            pairs.insert(0, rest.substring(start, index))
            index = start
        }
        return "$pairs,$lastThree"
    }

    /** `₹12.4k` / `₹1.2L` — for chart axes and tight chips only. */
    fun compact(paise: Long): String {
        val rupees = abs(paise) / 100.0
        val sign = if (paise < 0) "-" else ""
        return when {
            rupees >= 10_000_000 -> "$sign₹${trim(rupees / 10_000_000)}Cr"
            rupees >= 100_000 -> "$sign₹${trim(rupees / 100_000)}L"
            rupees >= 1_000 -> "$sign₹${trim(rupees / 1_000)}k"
            else -> "$sign₹${rupees.roundToLong()}"
        }
    }

    private fun trim(value: Double): String {
        val rounded = (value * 10).roundToLong() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }
}
