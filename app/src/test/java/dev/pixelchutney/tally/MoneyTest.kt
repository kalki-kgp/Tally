package dev.pixelchutney.tally

import dev.pixelchutney.tally.core.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `groups digits the Indian way`() {
        assertEquals("0", Money.groupIndian(0))
        assertEquals("999", Money.groupIndian(999))
        assertEquals("1,000", Money.groupIndian(1_000))
        assertEquals("99,999", Money.groupIndian(99_999))
        assertEquals("1,00,000", Money.groupIndian(100_000))
        assertEquals("12,34,567", Money.groupIndian(1_234_567))
        assertEquals("1,00,00,000", Money.groupIndian(10_000_000))
    }

    @Test
    fun `hides paise when there are none`() {
        assertEquals("₹450", Money.format(45_000))
        assertEquals("₹450.50", Money.format(45_050))
        assertEquals("₹450.05", Money.format(45_005))
    }

    @Test
    fun `keeps refunds signed`() {
        assertEquals("-₹250", Money.format(-25_000))
    }

    @Test
    fun `parses the shapes that appear in notifications`() {
        assertEquals(45_000L, Money.fromRupeeString("450"))
        assertEquals(45_000L, Money.fromRupeeString("450.00"))
        assertEquals(123_450L, Money.fromRupeeString("1,234.50"))
        assertEquals(45_000L, Money.fromRupeeString("₹450"))
        assertEquals(45_000L, Money.fromRupeeString("Rs.450"))
        assertNull(Money.fromRupeeString("abc"))
        assertNull(Money.fromRupeeString(""))
    }

    @Test
    fun `reads numpad entry as rupees, not paise`() {
        assertEquals(24_000L, Money.fromEntry("240"))
        assertEquals(0L, Money.fromEntry(""))
        assertEquals(24_000L, Money.fromEntry("240."))
        assertEquals(24_050L, Money.fromEntry("240.5"))
        assertEquals(24_005L, Money.fromEntry("240.05"))
        assertEquals(50L, Money.fromEntry("0.50"))
    }

    @Test
    fun `seeds the numpad from an existing amount`() {
        assertEquals("240", Money.toEntry(24_000))
        assertEquals("240.50", Money.toEntry(24_050))
        assertEquals("240.05", Money.toEntry(24_005))
        assertEquals("240", Money.toEntry(-24_000))
    }

    @Test
    fun `shows the decimal point as soon as it is typed`() {
        assertEquals("₹0", Money.formatEntry(""))
        assertEquals("₹240", Money.formatEntry("240"))
        assertEquals("₹240.", Money.formatEntry("240."))
        assertEquals("₹240.5", Money.formatEntry("240.5"))
        assertEquals("₹12,340", Money.formatEntry("12340"))
        assertEquals("₹0.", Money.formatEntry("0."))
    }

    @Test
    fun `compacts large amounts`() {
        assertEquals("₹950", Money.compact(95_000))
        assertEquals("₹12.3k", Money.compact(1_234_500))
        assertEquals("₹1.5L", Money.compact(15_000_000))
    }
}
