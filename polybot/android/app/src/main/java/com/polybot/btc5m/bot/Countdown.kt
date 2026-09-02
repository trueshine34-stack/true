package com.polybot.btc5m.bot

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A cue in the headphones before each window opens.
 *
 * The decision this desk is about is made in the last seconds before a
 * five-minute window turns: what the closing one settled at is what the next
 * one opens against, and a person who wants to be at the phone for that has
 * otherwise to watch a clock for four minutes to catch twenty seconds. So the
 * clock says it out loud — once at twenty-five seconds, twice at ten, on the
 * media stream that survives the ringer switch and follows the headphones.
 *
 * It runs in the service rather than in the screen, which is the whole point:
 * the phone is in a pocket, and the WebView is asleep.
 */
object Countdown {

    /** How long before the boundary the first cue goes. */
    const val FIRST_SEC = 25L

    /** And the second, doubled, which is what makes it the nearer one. */
    const val SECOND_SEC = 10L

    @Volatile
    var on: Boolean = false
        private set

    /** The window whose marks have already been sounded, per mark. */
    @Volatile
    private var firstAt: Long = 0L

    @Volatile
    private var secondAt: Long = 0L

    private var job: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * Where the cue goes, which is the speaker unless a test is listening.
     *
     * The marks are arithmetic on a clock and the sound is a thread and an
     * AudioTrack; this is the seam between them, so the first can be checked
     * without the second.
     */
    @Volatile
    var listener: ((String) -> Unit)? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "countdown").apply { isDaemon = true }
    }

    fun set(enabled: Boolean) {
        if (on == enabled) return
        on = enabled
        // Switched on in the middle of a window, it may still catch that
        // window's marks; switched off and on again, it is armed afresh
        // rather than silent because a previous run had already spoken.
        firstAt = 0L
        secondAt = 0L
        if (enabled) start() else stop()
    }

    @Synchronized
    private fun start() {
        if (job != null) return
        // Twice a second: the marks are a second wide and a cue that fires a
        // second late is a cue for a moment that has passed.
        job = scheduler.scheduleWithFixedDelay({ tick() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun stop() {
        job?.cancel(false)
        job = null
    }

    /**
     * Through the trade-sound switch rather than under it: this cue has a
     * switch of its own, and someone who wants the clock in their ear does not
     * necessarily want the fills as well.
     */
    private fun say(kind: String) {
        listener?.let {
            it(kind)
            return
        }
        Chime.demo(kind)
    }

    /**
     * Sound whichever mark this second is, once per window.
     *
     * The marks are counted from the window that is closing, so the one being
     * announced is the one about to open — and each is stamped with the window
     * it belonged to, because a tick every half second would otherwise sound
     * the same mark twice.
     */
    internal fun tick(nowSec: Long = Clock.nowSec()) {
        if (!on) return
        val window = nowSec - (nowSec % WINDOW_SECONDS)
        val left = window + WINDOW_SECONDS - nowSec

        if (left <= FIRST_SEC && left > SECOND_SEC && firstAt != window) {
            firstAt = window
            say("tick")
            return
        }
        if (left <= SECOND_SEC && left > 0 && secondAt != window) {
            secondAt = window
            say("tick2")
        }
    }
}
