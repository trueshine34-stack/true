package com.polybot.btc5m.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The confluence the pulse bot needs before it will pay for a side. */
class PulsePlanTest {

    private val on = PulsePlan.Settings(enabled = true)

    /** Everything agreeing on Up, cheaply enough to be worth taking. */
    private fun good(
        elapsed: Long = 120,
        lead: Double = 20.0,
        momentum: Double = 8.0,
        volume: Double = 1.4,
        lean: Double = 0.62,
        upAsk: Double? = 0.62,
        downAsk: Double? = 0.40,
        ceiling: Double = 1.0,
        cash: Double = 10.0,
    ) = PulsePlan.Read(
        elapsedSec = elapsed,
        lead = lead,
        momentum = momentum,
        volume = volume,
        lean = lean,
        upAsk = upAsk,
        downAsk = downAsk,
        ceiling = ceiling,
        cashUsd = cash,
    )

    @Test
    fun buysWhenEverythingAgrees() {
        assertNull(PulsePlan.blockedBecause(good(), on, holding = false))
        assertEquals("Up", PulsePlan.leader(good().lead, on.minEdge))
    }

    @Test
    fun aWindowWithNoLeadIsACoinFlip() {
        // Two dollars either way inside five minutes is noise, and noise has
        // no side to buy.
        assertNull(PulsePlan.leader(2.0, on.minEdge))
        assertNull(PulsePlan.leader(-2.0, on.minEdge))
        assertTrue(
            PulsePlan.blockedBecause(good(lead = 2.0), on, holding = false)!!
                .startsWith("нет перевеса"),
        )
    }

    @Test
    fun willNotBuyALeadThatIsBeingHandedBack() {
        assertEquals(
            "импульс против",
            PulsePlan.blockedBecause(good(momentum = -5.0), on, holding = false),
        )
        // And the mirror: a Down lead with price climbing back.
        val down = good(lead = -20.0, momentum = 5.0)
        assertEquals("Down", PulsePlan.leader(down.lead, on.minEdge))
        assertEquals("импульс против", PulsePlan.blockedBecause(down, on, holding = false))
    }

    @Test
    fun willNotBuyAMoveNobodyTraded() {
        assertTrue(
            PulsePlan.blockedBecause(good(volume = 0.4), on, holding = false)!!
                .startsWith("нет объёма"),
        )
    }

    @Test
    fun willNotBuyAgainstTheBook() {
        // The lead says Up while the resting size is stacked on the offer.
        assertTrue(
            PulsePlan.blockedBecause(good(lean = 0.44), on, holding = false)!!
                .startsWith("стакан против"),
        )
        // The same book read from the other side is a reason to buy Down.
        val down = good(lead = -20.0, momentum = -5.0, lean = 0.38, downAsk = 0.6)
        assertNull(PulsePlan.blockedBecause(down, on, holding = false))
    }

    @Test
    fun staysInsideTheOddsBand() {
        assertTrue(
            PulsePlan.blockedBecause(good(upAsk = 0.88), on, holding = false)!!
                .startsWith("дорого"),
        )
        // Cheap here means the market disagrees with all four signals, which is
        // a disagreement to sit out rather than a bargain.
        assertTrue(
            PulsePlan.blockedBecause(good(upAsk = 0.2), on, holding = false)!!
                .startsWith("рынок против"),
        )
    }

    @Test
    fun obeysTheDesksOwnEarlyCeiling() {
        // The rule may allow eighty cents; the window's first minutes do not.
        assertTrue(
            PulsePlan.blockedBecause(good(upAsk = 0.7, ceiling = 0.63), on, holding = false)!!
                .startsWith("дорого"),
        )
    }

    @Test
    fun tradesOneClipAtATime() {
        assertEquals("в позиции", PulsePlan.blockedBecause(good(), on, holding = true))
    }

    @Test
    fun waitsForTheWindowToSaySomething() {
        assertEquals("рано", PulsePlan.blockedBecause(good(elapsed = 20), on, holding = false))
    }

    @Test
    fun tradesTheLastMinuteToo() {
        // A late lot cannot reach its own take price, but from the ride second
        // a side that is still ahead is carried into settlement instead — and
        // settlement pays a dollar with no fee.
        assertNull(PulsePlan.blockedBecause(good(elapsed = 250), on, holding = false))
        assertNull(PulsePlan.blockedBecause(good(elapsed = 295), on, holding = false))
    }

    @Test
    fun stillHonoursALimitSetInsideTheWindow() {
        val early = on.copy(untilSec = 120)
        assertNull(PulsePlan.blockedBecause(good(elapsed = 100), early, holding = false))
        assertEquals(
            "поздно",
            PulsePlan.blockedBecause(good(elapsed = 130), early, holding = false),
        )
    }

    @Test
    fun willNotSpendMoneyItDoesNotHave() {
        // Five shares at sixty-two cents is $3.10.
        assertEquals("нет денег", PulsePlan.blockedBecause(good(cash = 3.0), on, holding = false))
    }

    @Test
    fun doesNothingWhileSwitchedOff() {
        assertEquals(
            "выключен",
            PulsePlan.blockedBecause(good(), on.copy(enabled = false), holding = false),
        )
    }

    @Test
    fun cutsAPositionTheLeadHasTurnedOn() {
        val turned = good(lead = -4.0)
        assertEquals(PulsePlan.Exit.CUT, PulsePlan.exitFor("Up", turned, on))
        // Still ahead: the resting offer does the work, not a market sale.
        assertEquals(PulsePlan.Exit.HOLD, PulsePlan.exitFor("Up", good(), on))
    }

    @Test
    fun ridesAWinnerIntoSettlement() {
        // Settlement pays a dollar and charges no fee, so a side still ahead
        // with seconds left is worth more held than sold.
        val late = good(elapsed = 280)
        assertEquals(PulsePlan.Exit.RIDE, PulsePlan.exitFor("Up", late, on))
        // Late but losing is still a cut.
        assertEquals(
            PulsePlan.Exit.CUT,
            PulsePlan.exitFor("Up", good(elapsed = 280, lead = -9.0), on),
        )
    }

    @Test
    fun takesProfitAtTheRungAboveWhatItPaid() {
        // Twelve percent on sixty-two cents is 69.4, and a sell never rounds
        // down onto a worse price than it asked for.
        assertEquals(0.70, PulsePlan.takePrice(0.62, on, 0.01), 1e-9)
        assertEquals(0.34, PulsePlan.takePrice(0.30, on, 0.01), 1e-9)
    }

    @Test
    fun crossesTheSpreadToBeFilledNow() {
        assertEquals(0.63, PulsePlan.crossPrice(0.62, 0.01), 1e-9)
        assertEquals(0.99, PulsePlan.crossPrice(0.99, 0.01), 1e-9)
    }

    @Test
    fun readsTheSidesOwnPrice() {
        assertEquals(0.62, PulsePlan.askFor("Up", good()))
        assertEquals(0.40, PulsePlan.askFor("Down", good()))
        assertNull(PulsePlan.askFor(null, good()))
        assertNotNull(PulsePlan.askFor("Up", good()))
    }
}
