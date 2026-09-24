package dev.pixelchutney.tally

import dev.pixelchutney.tally.ai.LocalRanking
import dev.pixelchutney.tally.ai.RankInput
import dev.pixelchutney.tally.capture.LocationSource
import dev.pixelchutney.tally.capture.PaymentContext
import dev.pixelchutney.tally.capture.PaymentIngestor
import dev.pixelchutney.tally.data.db.Seed
import dev.pixelchutney.tally.data.db.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRankingTest {

    private val noon = 1_757_664_000_000L // a fixed instant; only hour-of-day matters

    private fun past(categoryId: Long, payee: String? = null, note: String? = null, lat: Double? = null) =
        TransactionEntity(
            amountPaise = 45_000,
            timestamp = noon,
            loggedAt = noon,
            categoryId = categoryId,
            payee = payee,
            note = note,
            latitude = lat,
            longitude = lat?.let { 77.6 },
            locationSource = lat?.let { LocationSource.LIVE },
        )

    private fun input(payee: String? = null, note: String? = null, lat: Double? = null) = RankInput(
        amountPaise = 45_000,
        timestamp = noon,
        sourceApp = null,
        payee = payee,
        merchantName = null,
        note = note,
        context = PaymentContext(
            latitude = lat,
            longitude = lat?.let { 77.6 },
            locationSource = if (lat != null) LocationSource.LIVE else LocationSource.UNAVAILABLE,
        ),
    )

    private fun rank(input: RankInput, history: List<TransactionEntity>, remembered: Long? = null) =
        LocalRanking.score(input, Seed.categories, history, remembered, rememberedByPerson = true)

    @Test
    fun `same payee wins over general frequency`() {
        val history = List(5) { past(1) } + past(2, payee = "blinkit.rzp@axisbank")
        assertEquals(2L, rank(input(payee = "blinkit.rzp@axisbank"), history).first().category.id)
    }

    @Test
    fun `the note names its category`() {
        val history = List(5) { past(1) }
        assertEquals(2L, rank(input(note = "groceries for the week"), history).first().category.id)
    }

    @Test
    fun `nearby payments pull toward their category`() {
        val history = List(3) { past(1, lat = 13.0) } + List(2) { past(3, lat = 12.9350) }
        assertEquals(3L, rank(input(lat = 12.9351), history).first().category.id)
    }

    @Test
    fun `a merchant the owner filed wins outright`() {
        val history = List(5) { past(1) }
        assertEquals(7L, rank(input(payee = "apollo"), history, remembered = 7L).first().category.id)
    }

    @Test
    fun `every category is still offered`() {
        assertEquals(Seed.categories.size, rank(input(), emptyList()).size)
    }

    @Test
    fun `tidies payee names`() {
        assertEquals("Blinkit", PaymentIngestor.tidyPayee("blinkit.rzp@axisbank"))
        assertEquals("Raju Tea Stall", PaymentIngestor.tidyPayee("RAJU TEA STALL"))
        assertNull(PaymentIngestor.tidyPayee("paytmqr2810050501@paytm"))
    }
}
