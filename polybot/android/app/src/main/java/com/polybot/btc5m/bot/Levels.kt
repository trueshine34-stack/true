package com.polybot.btc5m.bot

import kotlin.math.abs

/**
 * Where price keeps stopping — the same lines the chart draws.
 *
 * A level is not a line someone drew: it is a price the market has turned at
 * more than once. So the work is to find the turns — a candle whose high is
 * the highest of its neighbours, or whose low the lowest — and then to notice
 * that several of them happened at the same price. One turn is an accident;
 * two at the same price is where the orders are.
 *
 * This is a port of the panel's own arithmetic, kept deliberately identical so
 * that a rule which refuses to buy near a level refuses near the line the user
 * can see, and not near some other line computed elsewhere.
 */
object Levels {

    /** A turn is the extreme of this many candles either side of it. */
    const val REACH = 2

    /** Two turns are one level if they are this close, against the range. */
    const val TOLERANCE = 0.02

    /**
     * How many lines the chart draws. Past about this many they stop being
     * levels and become a grid; below it the tested ones fall off the screen
     * while the two untested seats stay, which is the wrong three.
     */
    const val KEEP = 5

    /**
     * How much being recent is worth against being tested. At one and a half,
     * a brand new single turn outranks an ancient double and loses to a fresh
     * one.
     */
    const val FRESHNESS = 1.5

    data class Level(
        val price: Double,
        /** How many turns happened here. Two is a level; five is a wall. */
        val touches: Int,
        /** Named by where price is now, not by which kind of turn made it. */
        val kind: String,
        /**
         * The band the turns actually cover — a level is a zone, not a line.
         *
         * The orders that made it are spread over the prices the market
         * turned at, and resistance begins at the first of them rather than
         * at their average. Measuring room to the average lets a trade start
         * inside the zone and call it clear air, which is how a side was
         * bought with the shelf it had failed at all morning a few dollars
         * overhead. Both default to [price], so a single-price level is a
         * band of zero width and behaves exactly as a line.
         */
        val low: Double = price,
        val high: Double = price,
    ) {
        /**
         * Where this zone starts for a trade going [way] — the edge it will
         * reach first, which is the bottom of the band for a rally and the
         * top of it for a fall.
         */
        fun facing(way: String): Double = if (way == "Up") low else high
    }

    private data class Turn(val price: Double, val at: Int)

    private data class Cluster(
        val price: Double,
        val touches: Int,
        val strength: Double,
        val low: Double,
        val high: Double,
    )

    private fun pivots(candles: List<BinanceCandles.Candle>): List<Turn> {
        val out = ArrayList<Turn>()
        for (i in REACH until candles.size - REACH) {
            var isHigh = true
            var isLow = true
            for (j in (i - REACH)..(i + REACH)) {
                if (j == i) continue
                // Strict on the left, forgiving on the right: a run of equal
                // highs is one turn, counted at the candle that made it.
                val higher =
                    if (j < i) candles[j].high >= candles[i].high
                    else candles[j].high > candles[i].high
                if (higher) isHigh = false
                val lower =
                    if (j < i) candles[j].low <= candles[i].low
                    else candles[j].low < candles[i].low
                if (lower) isLow = false
            }
            if (isHigh) out.add(Turn(candles[i].high, i))
            if (isLow) out.add(Turn(candles[i].low, i))
        }
        return out
    }

    /**
     * How many tested prices the gate is told about.
     *
     * Six rather than the chart's three. The chart is drawing a picture and
     * three lines is all a picture wants; the gate is asking "is there
     * anything between me and where I am going", and a level dropped for
     * being the fourth-strongest is a wall the rule cannot see.
     */
    const val TESTED_KEEP = 6

    /** The clustered turns of a series, or null when there are too few. */
    private fun clustersOf(
        candles: List<BinanceCandles.Candle>,
        last: Double,
    ): Pair<List<Cluster>, Double>? {
        val clean = candles.filter {
            it.open > 0 && it.high > 0 && it.low > 0 && it.close > 0
        }
        if (clean.size < REACH * 2 + 1 || last <= 0.0) return null

        val low = clean.minOf { it.low }
        val high = clean.maxOf { it.high }
        val range = high - low
        if (range <= 0.0) return null
        val near = range * TOLERANCE

        // Sorted by price, so a level is a run of neighbours rather than a
        // search through every pair.
        val turns = pivots(clean).sortedBy { it.price }
        val groups = ArrayList<MutableList<Turn>>()
        for (turn in turns) {
            val open = groups.lastOrNull()
            if (open != null && turn.price - open[0].price <= near) open.add(turn)
            else groups.add(mutableListOf(turn))
        }

        val span = maxOf(1, clean.size - 1)
        return groups.map { g ->
            Cluster(
                price = g.sumOf { it.price } / g.size,
                touches = g.size,
                strength = g.size + FRESHNESS * (g.maxOf { it.at }.toDouble() / span),
                low = g.minOf { it.price },
                high = g.maxOf { it.price },
            )
        } to near
    }

    /**
     * Every price this series has actually turned at more than once, nearest
     * first — which is a different question from the one the chart asks.
     *
     * [find] is picking three lines to draw, so it seats the nearest turn
     * either side whether or not anything ever bounced there, and fills what
     * is left by strength — which counts being recent. A gate wants neither:
     * a single turn is not a wall however close it is, an old price the
     * market turned at four times is a wall however long ago, and of two
     * walls the near one is this window's problem. Feeding the gate the
     * chart's three meant the two untested seats used up two of them, the
     * filter for tested levels then threw both away, and one line was all
     * that ever reached the rule.
     */
    fun tested(
        candles: List<BinanceCandles.Candle>,
        last: Double,
        least: Int = 2,
        keep: Int = TESTED_KEEP,
    ): List<Level> {
        val (clusters, near) = clustersOf(candles, last) ?: return emptyList()

        val chosen = ArrayList<Cluster>()
        for (level in clusters.filter { it.touches >= least }.sortedBy { abs(it.price - last) }) {
            if (chosen.size >= keep) break
            // Two prices a hair apart are one wall counted twice.
            if (chosen.none { abs(it.price - level.price) < near * 3 }) chosen.add(level)
        }
        return chosen
            .map {
                Level(
                    price = it.price,
                    touches = it.touches,
                    kind = if (it.price > last) "resistance" else "support",
                    low = it.low,
                    high = it.high,
                )
            }
            .sortedByDescending { it.price }
    }

    fun find(
        candles: List<BinanceCandles.Candle>,
        last: Double,
        keep: Int = KEEP,
    ): List<Level> {
        val (clusters, near) = clustersOf(candles, last) ?: return emptyList()

        fun nearest(side: List<Cluster>) = side.sortedWith(
            compareBy<Cluster> { abs(it.price - last) }.thenByDescending { it.strength },
        )

        val above = nearest(clusters.filter { it.price > last })
        val below = nearest(clusters.filter { it.price <= last })

        // The two that matter first: what a rally has to get through, and what
        // a fall has to break. Drawing only the most-tested prices puts both
        // of them an hour behind price after any real move.
        val chosen = ArrayList<Cluster>()
        fun room(level: Cluster) = chosen.none { abs(it.price - level.price) < near * 3 }

        listOfNotNull(above.firstOrNull(), below.firstOrNull()).forEach {
            if (room(it)) chosen.add(it)
        }

        // Then the tested prices, nearest first — but a line for a price that
        // turned once, somewhere in the middle, is noise. Nearest rather than
        // strongest: strength counts being recent, so it would draw a fresh
        // double four hundred dollars away and leave out an old triple forty
        // dollars away, and it is the near one price is about to reach.
        val rest = clusters
            .filter { it.touches >= 2 && chosen.none { c -> c === it } }
            .sortedBy { abs(it.price - last) }
        for (level in rest) {
            if (chosen.size >= keep) break
            if (room(level)) chosen.add(level)
        }

        return chosen
            .map {
                Level(
                    price = it.price,
                    touches = it.touches,
                    kind = if (it.price > last) "resistance" else "support",
                    low = it.low,
                    high = it.high,
                )
            }
            .sortedByDescending { it.price }
    }

    /**
     * The first level the move would run into, or null when there is none
     * that way — which is a trend with nothing in front of it.
     */
    fun ahead(levels: List<Level>, last: Double, way: String): Double? {
        if (last <= 0.0) return null
        val side = when (way) {
            "Up" -> levels.filter { it.price > last }
            "Down" -> levels.filter { it.price < last }
            else -> return null
        }
        return side.minByOrNull { abs(it.price - last) }?.price
    }

    /**
     * What one candle of this series usually travels.
     *
     * The measure a distance is worth judging against: "forty dollars from the
     * level" means nothing on its own, and everything next to "and a window
     * usually moves sixty".
     */
    fun typicalRange(candles: List<BinanceCandles.Candle>, over: Int = 12): Double {
        val use = candles.filter { it.high > 0 && it.low > 0 }.takeLast(over)
        if (use.isEmpty()) return 0.0
        return use.sumOf { it.high - it.low } / use.size
    }
}
