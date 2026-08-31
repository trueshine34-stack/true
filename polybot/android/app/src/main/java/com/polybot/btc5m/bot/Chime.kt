package com.polybot.btc5m.bot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The three sounds a trade makes, for listening rather than watching.
 *
 * A five-minute window is decided while the phone is in a pocket, so the
 * report on the screen is read afterwards and the moment itself is missed. A
 * cue in the headphones is the only way to know what happened when it
 * happened — and to know it without looking, which means the three have to be
 * told apart by shape alone: Up rises, Down falls, and a sale is the two-note
 * ring of a coin, which is neither.
 *
 * Synthesised rather than played from a file. Three short tones are a few
 * hundred lines of arithmetic and no assets, no decoder and nothing to
 * package — and being generated, they can be tuned by changing a number
 * instead of by finding a new recording.
 *
 * Played as sonification, which is the category that belongs over music: the
 * cue mixes with whatever is playing instead of pausing it, and follows the
 * headphones because that is where the media stream goes.
 */
object Chime {

    private const val RATE = 44_100

    /** Loud enough to hear over music, quiet enough not to be an alarm. */
    private const val GAIN = 0.35

    @Volatile
    var on: Boolean = true

    private val player = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chime").apply { isDaemon = true }
    }

    /** A note: a frequency, when it starts, and how long it rings. */
    private data class Note(val hz: Double, val atMs: Int, val forMs: Int, val level: Double = 1.0)

    /** Bought upwards — two notes climbing, which is the shape of the bet. */
    fun boughtUp() = play(
        listOf(
            Note(660.0, 0, 110),
            Note(990.0, 90, 150),
        ),
    )

    /** And bought downwards, the same two notes the other way round. */
    fun boughtDown() = play(
        listOf(
            Note(660.0, 0, 110),
            Note(440.0, 90, 150),
        ),
    )

    /** Either, by name, since that is how the rest of the code holds a side. */
    fun bought(side: String) {
        when (side) {
            "Up" -> boughtUp()
            "Down" -> boughtDown()
        }
    }

    /**
     * Sold — a coin.
     *
     * The short-then-long fifth everything from a cash register to a video
     * game uses for money, because it is the one people already know means
     * exactly this without being told.
     */
    fun sold() = play(
        listOf(
            Note(988.0, 0, 80),
            Note(1319.0, 70, 320),
            // A quiet octave over the second note gives it the metallic edge a
            // pure sine has none of.
            Note(2638.0, 70, 240, level = 0.28),
        ),
    )

    private fun play(notes: List<Note>) {
        if (!on || notes.isEmpty()) return
        player.execute {
            try {
                ring(notes)
            } catch (e: Exception) {
                // A sound that will not play is not a reason to stop trading.
            }
        }
    }

    private fun ring(notes: List<Note>) {
        val lastMs = notes.maxOf { it.atMs + it.forMs }
        val total = RATE * lastMs / 1000
        val mix = DoubleArray(total)

        for (note in notes) {
            val from = RATE * note.atMs / 1000
            val len = RATE * note.forMs / 1000
            for (i in 0 until len) {
                val at = from + i
                if (at >= total) break
                val t = i.toDouble() / RATE
                // A quick rise and an exponential fall: a struck note rather
                // than a beep, and no click at either end.
                val rise = minOf(1.0, t / 0.004)
                val fall = exp(-t * (3500.0 / note.forMs))
                mix[at] += sin(2 * PI * note.hz * t) * rise * fall * note.level
            }
        }

        val loudest = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        val pcm = ShortArray(total)
        for (i in 0 until total) {
            pcm[i] = (mix[i] / loudest * GAIN * Short.MAX_VALUE).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.setNotificationMarkerPosition(pcm.size)
        track.play()
        // Static mode plays the whole buffer and stops; releasing before it
        // has finished cuts the sound off, so the thread waits it out. It is
        // this object's own thread and nothing else is queued behind it.
        Thread.sleep((lastMs + 120).toLong())
        try {
            track.stop()
        } catch (e: IllegalStateException) {
            // Already finished, which is the ordinary case.
        }
        track.release()
    }
}
