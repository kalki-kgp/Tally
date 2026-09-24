package dev.pixelchutney.tally

import dev.pixelchutney.tally.capture.PaymentNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentNotificationParserTest {

    private fun parse(text: String) = PaymentNotificationParser.parse(text)

    // ── UPI apps ──────────────────────────────────────────────────────────────

    @Test
    fun `reads upi app notifications`() {
        parse("Paid ₹450 to Blinkit")!!.let {
            assertEquals(45_000L, it.amountPaise)
            assertEquals("Blinkit", it.payee)
        }
        parse("₹1,250 paid to Third Wave Coffee\nUPI transaction ID: 424512345678")!!.let {
            assertEquals(1_25_000L, it.amountPaise)
            assertEquals("Third Wave Coffee", it.payee)
            assertEquals("424512345678", it.upiRef)
        }
        parse("Payment successful\nYou paid Rs.99.50 to swiggy@icici")!!.let {
            assertEquals(9_950L, it.amountPaise)
            assertEquals("swiggy@icici", it.payee)
        }
    }

    // ── Bank SMS ──────────────────────────────────────────────────────────────

    @Test
    fun `reads bank debit messages`() {
        parse(
            "Rs.450.00 debited from A/c XX1234 on 12-09-25 to VPA blinkit.rzp@axisbank. " +
                "UPI Ref 523412345678. Not you? Call 1800"
        )!!.let {
            assertEquals(45_000L, it.amountPaise)
            assertEquals("blinkit.rzp@axisbank", it.payee)
            assertEquals("523412345678", it.upiRef)
        }

        parse(
            "ICICI Bank Acct XX123 debited for Rs 240.00 on 12-Sep-25; RAJU TEA STALL credited. " +
                "UPI:523498765432. Call 18002662 for dispute."
        )!!.let {
            assertEquals(24_000L, it.amountPaise)
            assertEquals("RAJU TEA STALL", it.payee)
        }

        parse(
            "Sent Rs.1200.00\nFrom HDFC Bank A/C *1234\nTo ZOMATO LIMITED\nOn 12/09/25\n" +
                "Ref 523411112222\nNot You? Call 18002586161"
        )!!.let {
            assertEquals(1_20_000L, it.amountPaise)
            assertEquals("ZOMATO LIMITED", it.payee)
        }

        // SBI writes the amount with no currency at all.
        parse(
            "Dear UPI user A/C X1234 debited by 75.0 on date 12Sep25 trf to AUTO RICKSHAW " +
                "Refno 523400001111. If not u? call 1800111109. -SBI"
        )!!.let {
            assertEquals(7_500L, it.amountPaise)
            assertEquals("AUTO RICKSHAW", it.payee)
        }

        parse("INR 2,499.00 spent on your HDFC Bank Card x4321 at AMAZON on 2025-09-12")!!.let {
            assertEquals(2_49_900L, it.amountPaise)
            assertEquals("AMAZON", it.payee)
        }
    }

    // ── What must be refused ──────────────────────────────────────────────────

    @Test
    fun `ignores money coming in`() {
        assertNull(parse("Rs.500.00 credited to A/c XX1234 from VPA friend@okaxis. UPI Ref 523412345678"))
        assertNull(parse("You received ₹200 from Priya"))
        assertNull(parse("Refund of ₹349 processed to your account"))
    }

    @Test
    fun `ignores things that name an amount without spending it`() {
        assertNull(parse("123456 is your OTP for a transaction of Rs 450 at BLINKIT. Do not share"))
        assertNull(parse("Rahul has requested ₹500 from you"))
        assertNull(parse("Payment of ₹450 to Blinkit failed. Money will be refunded"))
        assertNull(parse("Your electricity bill of Rs 1,340 is due on 15 Sep"))
        assertNull(parse("Rs 499 will be debited on 20 Sep for Netflix"))
        assertNull(parse("Get up to ₹100 cashback on your next payment"))
        assertNull(parse("Your balance is Rs 12,000"))
    }

    @Test
    fun `tells bank texts from personal ones`() {
        assertTrue(PaymentNotificationParser.looksLikeBankMessage("Rs 450 debited from A/c XX1234"))
        assertTrue(PaymentNotificationParser.looksLikeBankMessage("Sent Rs.10 from Kotak Bank AC"))
        assertFalse(PaymentNotificationParser.looksLikeBankMessage("paid ₹500 for the cab, send me half"))
    }

    @Test
    fun `only trusts bank sender ids`() {
        assertTrue(PaymentNotificationParser.looksLikeBankSender("AX-HDFCBK"))
        assertTrue(PaymentNotificationParser.looksLikeBankSender("VM-SBIUPI-S"))
        assertTrue(PaymentNotificationParser.looksLikeBankSender("ICICIT"))
        assertFalse(PaymentNotificationParser.looksLikeBankSender("Rahul Sharma"))
        assertFalse(PaymentNotificationParser.looksLikeBankSender("Mom"))
        assertFalse(PaymentNotificationParser.looksLikeBankSender("+91 98450 12345"))
        assertFalse(PaymentNotificationParser.looksLikeBankSender("9845012345"))
    }

    @Test
    fun `converts rupee strings to paise`() {
        assertEquals(45_000L, PaymentNotificationParser.toPaise("450"))
        assertEquals(45_050L, PaymentNotificationParser.toPaise("450.5"))
        assertEquals(1_23_456L, PaymentNotificationParser.toPaise("1,234.56"))
    }
}
