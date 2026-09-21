package com.plovault.sync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.plovault.parser.ParsedHand

/** Строка раздачи в локальной базе (без сырого текста — он лежит в файлах). */
data class HandRow(
    val handId: String,
    val ts: Long,
    val game: String,
    val stake: String,
    val rush: Boolean,
    val currency: String,
    val sb: Double,
    val bb: Double,
    val tableName: String,
    val seats: Int,
    val players: Int,
    val position: String,
    val cards: String,
    val board: String,
    val streets: Int,
    val invested: Double,
    val collected: Double,
    val net: Double,
    val netBb: Double,
    val pot: Double,
    val rake: Double,
    val fees: Double,
    val vpip: Boolean,
    val pfr: Boolean,
    val threeBet: Boolean,
    val sawFlop: Boolean,
    val showdown: Boolean,
    val won: Boolean,
    val allIn: Boolean,
    val srcFile: String
)

class Db(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE hands (
              hand_id TEXT PRIMARY KEY,
              ts INTEGER NOT NULL,
              game TEXT NOT NULL,
              stake TEXT NOT NULL,
              rush INTEGER NOT NULL,
              currency TEXT NOT NULL,
              sb REAL NOT NULL,
              bb REAL NOT NULL,
              table_name TEXT,
              seats INTEGER,
              players INTEGER,
              position TEXT,
              cards TEXT,
              board TEXT,
              streets INTEGER,
              invested REAL,
              collected REAL,
              net REAL,
              net_bb REAL,
              pot REAL,
              rake REAL,
              fees REAL,
              vpip INTEGER,
              pfr INTEGER,
              three_bet INTEGER,
              saw_flop INTEGER,
              showdown INTEGER,
              won INTEGER,
              all_in INTEGER,
              cashout INTEGER,
              src_file TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_hands_ts ON hands(ts)")
        db.execSQL("CREATE INDEX idx_hands_stake ON hands(stake)")
        db.execSQL("CREATE INDEX idx_hands_game ON hands(game, rush)")
        db.execSQL(
            """
            CREATE TABLE files (
              name TEXT PRIMARY KEY,
              imported_at INTEGER NOT NULL,
              bytes INTEGER,
              hands_total INTEGER,
              hands_new INTEGER,
              source TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS hands")
        db.execSQL("DROP TABLE IF EXISTS files")
        onCreate(db)
    }

    companion object {
        private const val NAME = "plovault.db"
        private const val VERSION = 1
    }
}

/** Доступ к базе: вставка раздач, выборки, агрегаты. */
class HandRepository(context: Context) {

    private val helper = Db(context)

    /** Вставляет раздачи, пропуская уже существующие. Возвращает количество новых. */
    fun insertHands(hands: List<ParsedHand>, srcFile: String): Int {
        if (hands.isEmpty()) return 0
        val db = helper.writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (h in hands) {
                val cv = ContentValues().apply {
                    put("hand_id", h.handId)
                    put("ts", h.timestampMillis)
                    put("game", h.gameCode.name)
                    put("stake", h.stakeLabel)
                    put("rush", if (h.isRushAndCash) 1 else 0)
                    put("currency", h.currency)
                    put("sb", h.smallBlind)
                    put("bb", h.bigBlind)
                    put("table_name", h.tableName)
                    put("seats", h.maxSeats)
                    put("players", h.playerCount)
                    put("position", h.heroPosition)
                    put("cards", h.heroCards.joinToString(" "))
                    put("board", h.board.joinToString(" "))
                    put("streets", h.streetsSeen)
                    put("invested", h.heroInvested)
                    put("collected", h.heroCollected)
                    put("net", h.heroNet)
                    put("net_bb", h.heroNetBb)
                    put("pot", h.totalPot)
                    put("rake", h.rake)
                    put("fees", h.jackpotFees)
                    put("vpip", if (h.heroVpip) 1 else 0)
                    put("pfr", if (h.heroPfr) 1 else 0)
                    put("three_bet", if (h.heroThreeBet) 1 else 0)
                    put("saw_flop", if (h.heroSawFlop) 1 else 0)
                    put("showdown", if (h.heroWentToShowdown) 1 else 0)
                    put("won", if (h.heroWonHand) 1 else 0)
                    put("all_in", if (h.heroAllIn) 1 else 0)
                    put("cashout", if (h.cashedOut) 1 else 0)
                    put("src_file", srcFile)
                }
                val id = db.insertWithOnConflict("hands", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (id != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun recordFile(name: String, bytes: Long, total: Int, new: Int, source: String) {
        val cv = ContentValues().apply {
            put("name", name)
            put("imported_at", System.currentTimeMillis())
            put("bytes", bytes)
            put("hands_total", total)
            put("hands_new", new)
            put("source", source)
        }
        helper.writableDatabase.insertWithOnConflict("files", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun totalHands(): Int = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM hands", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun lastHandTs(): Long = helper.readableDatabase.rawQuery("SELECT MAX(ts) FROM hands", null).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }

    fun stakes(filter: Filter = Filter()): List<String> {
        val (where, args) = filter.toSql()
        val sql = "SELECT stake, COUNT(*) c FROM hands WHERE $where GROUP BY stake ORDER BY c DESC"
        return helper.readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    fun hands(filter: Filter, limit: Int = 200, offset: Int = 0): List<HandRow> {
        val (where, args) = filter.toSql()
        val sql = "SELECT * FROM hands WHERE $where ORDER BY ts DESC LIMIT $limit OFFSET $offset"
        return helper.readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(c.toHandRow()) }
        }
    }

    fun aggregate(filter: Filter): Agg {
        val (where, args) = filter.toSql()
        val sql = """
            SELECT COUNT(*), IFNULL(SUM(net),0), IFNULL(SUM(net_bb),0), IFNULL(SUM(vpip),0),
                   IFNULL(SUM(pfr),0), IFNULL(SUM(three_bet),0), IFNULL(SUM(saw_flop),0),
                   IFNULL(SUM(showdown),0), IFNULL(SUM(CASE WHEN showdown=1 AND won=1 THEN 1 ELSE 0 END),0),
                   IFNULL(SUM(CASE WHEN saw_flop=1 AND won=1 THEN 1 ELSE 0 END),0),
                   IFNULL(SUM(rake),0), IFNULL(SUM(fees),0), IFNULL(MIN(ts),0), IFNULL(MAX(ts),0),
                   IFNULL(MAX(net),0), IFNULL(MIN(net),0), IFNULL(SUM(all_in),0)
            FROM hands WHERE $where
        """.trimIndent()
        return helper.readableDatabase.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst()) Agg() else Agg(
                hands = c.getInt(0), net = c.getDouble(1), netBb = c.getDouble(2),
                vpip = c.getInt(3), pfr = c.getInt(4), threeBet = c.getInt(5), sawFlop = c.getInt(6),
                showdown = c.getInt(7), wonAtSd = c.getInt(8), wonAfterFlop = c.getInt(9),
                rake = c.getDouble(10), fees = c.getDouble(11), firstTs = c.getLong(12), lastTs = c.getLong(13),
                bestHand = c.getDouble(14), worstHand = c.getDouble(15), allIn = c.getInt(16)
            )
        }
    }

    fun groupBy(filter: Filter, column: String): List<Pair<String, Agg>> {
        val safe = when (column) {
            "stake", "position", "game", "table_name" -> column
            "day" -> "date(ts/1000,'unixepoch','localtime')"
            "month" -> "strftime('%Y-%m', ts/1000,'unixepoch','localtime')"
            else -> "stake"
        }
        val (where, args) = filter.toSql()
        val sql = """
            SELECT $safe AS g, COUNT(*), IFNULL(SUM(net),0), IFNULL(SUM(net_bb),0), IFNULL(SUM(vpip),0),
                   IFNULL(SUM(pfr),0), IFNULL(SUM(three_bet),0), IFNULL(SUM(saw_flop),0),
                   IFNULL(SUM(showdown),0), IFNULL(SUM(CASE WHEN showdown=1 AND won=1 THEN 1 ELSE 0 END),0),
                   IFNULL(SUM(CASE WHEN saw_flop=1 AND won=1 THEN 1 ELSE 0 END),0),
                   IFNULL(SUM(rake),0), IFNULL(SUM(fees),0)
            FROM hands WHERE $where GROUP BY g ORDER BY COUNT(*) DESC
        """.trimIndent()
        return helper.readableDatabase.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        (c.getString(0) ?: "?") to Agg(
                            hands = c.getInt(1), net = c.getDouble(2), netBb = c.getDouble(3),
                            vpip = c.getInt(4), pfr = c.getInt(5), threeBet = c.getInt(6), sawFlop = c.getInt(7),
                            showdown = c.getInt(8), wonAtSd = c.getInt(9), wonAfterFlop = c.getInt(10),
                            rake = c.getDouble(11), fees = c.getDouble(12)
                        )
                    )
                }
            }
        }
    }

    /** Накопительная кривая в bb, прореженная до [points] точек. */
    fun equityCurve(filter: Filter, points: Int = 120): List<Double> {
        val (where, args) = filter.toSql()
        val sql = "SELECT net_bb FROM hands WHERE $where ORDER BY ts ASC"
        val all = ArrayList<Double>()
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            var acc = 0.0
            while (c.moveToNext()) {
                acc += c.getDouble(0)
                all.add(acc)
            }
        }
        if (all.size <= points) return all
        val step = all.size.toDouble() / points
        return (0 until points).map { all[(it * step).toInt().coerceAtMost(all.size - 1)] }
    }

    /** Отдаёт все раздачи фильтра в CSV-виде (потоково, чтобы не держать всё в памяти). */
    fun exportCsv(filter: Filter, out: Appendable) {
        out.append(
            "hand_id,date_local,game,stake,rush,currency,sb,bb,table,seats,players,position," +
                "cards,board,streets,invested,collected,net,net_bb,pot,rake,fees," +
                "vpip,pfr,three_bet,saw_flop,showdown,won,all_in\n"
        )
        val (where, args) = filter.toSql()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM hands WHERE $where ORDER BY ts ASC", args
        ).use { c ->
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            while (c.moveToNext()) {
                val r = c.toHandRow()
                out.append(r.handId).append(',')
                    .append(fmt.format(java.util.Date(r.ts))).append(',')
                    .append(r.game).append(',')
                    .append('"').append(r.stake).append('"').append(',')
                    .append(if (r.rush) "1" else "0").append(',')
                    .append(r.currency).append(',')
                    .append(r.sb.toString()).append(',')
                    .append(r.bb.toString()).append(',')
                    .append('"').append(r.tableName).append('"').append(',')
                    .append(r.seats.toString()).append(',')
                    .append(r.players.toString()).append(',')
                    .append(r.position).append(',')
                    .append('"').append(r.cards).append('"').append(',')
                    .append('"').append(r.board).append('"').append(',')
                    .append(r.streets.toString()).append(',')
                    .append(r.invested.toString()).append(',')
                    .append(r.collected.toString()).append(',')
                    .append(r.net.toString()).append(',')
                    .append(r.netBb.toString()).append(',')
                    .append(r.pot.toString()).append(',')
                    .append(r.rake.toString()).append(',')
                    .append(r.fees.toString()).append(',')
                    .append(if (r.vpip) "1" else "0").append(',')
                    .append(if (r.pfr) "1" else "0").append(',')
                    .append(if (r.threeBet) "1" else "0").append(',')
                    .append(if (r.sawFlop) "1" else "0").append(',')
                    .append(if (r.showdown) "1" else "0").append(',')
                    .append(if (r.won) "1" else "0").append(',')
                    .append(if (r.allIn) "1" else "0").append('\n')
            }
        }
    }

    fun clearAll() {
        helper.writableDatabase.execSQL("DELETE FROM hands")
        helper.writableDatabase.execSQL("DELETE FROM files")
    }

    private fun android.database.Cursor.toHandRow(): HandRow = HandRow(
        handId = getString(getColumnIndexOrThrow("hand_id")),
        ts = getLong(getColumnIndexOrThrow("ts")),
        game = getString(getColumnIndexOrThrow("game")),
        stake = getString(getColumnIndexOrThrow("stake")),
        rush = getInt(getColumnIndexOrThrow("rush")) == 1,
        currency = getString(getColumnIndexOrThrow("currency")) ?: "$",
        sb = getDouble(getColumnIndexOrThrow("sb")),
        bb = getDouble(getColumnIndexOrThrow("bb")),
        tableName = getString(getColumnIndexOrThrow("table_name")) ?: "",
        seats = getInt(getColumnIndexOrThrow("seats")),
        players = getInt(getColumnIndexOrThrow("players")),
        position = getString(getColumnIndexOrThrow("position")) ?: "?",
        cards = getString(getColumnIndexOrThrow("cards")) ?: "",
        board = getString(getColumnIndexOrThrow("board")) ?: "",
        streets = getInt(getColumnIndexOrThrow("streets")),
        invested = getDouble(getColumnIndexOrThrow("invested")),
        collected = getDouble(getColumnIndexOrThrow("collected")),
        net = getDouble(getColumnIndexOrThrow("net")),
        netBb = getDouble(getColumnIndexOrThrow("net_bb")),
        pot = getDouble(getColumnIndexOrThrow("pot")),
        rake = getDouble(getColumnIndexOrThrow("rake")),
        fees = getDouble(getColumnIndexOrThrow("fees")),
        vpip = getInt(getColumnIndexOrThrow("vpip")) == 1,
        pfr = getInt(getColumnIndexOrThrow("pfr")) == 1,
        threeBet = getInt(getColumnIndexOrThrow("three_bet")) == 1,
        sawFlop = getInt(getColumnIndexOrThrow("saw_flop")) == 1,
        showdown = getInt(getColumnIndexOrThrow("showdown")) == 1,
        won = getInt(getColumnIndexOrThrow("won")) == 1,
        allIn = getInt(getColumnIndexOrThrow("all_in")) == 1,
        srcFile = getString(getColumnIndexOrThrow("src_file")) ?: ""
    )
}

/** Фильтр выборки: по умолчанию — PLO4 Rush & Cash. */
data class Filter(
    val game: String? = "PLO4",
    val rushOnly: Boolean = true,
    val stake: String? = null,
    val position: String? = null,
    val fromTs: Long? = null,
    val toTs: Long? = null
) {
    fun toSql(): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        game?.let { parts.add("game = ?"); args.add(it) }
        if (rushOnly) parts.add("rush = 1")
        stake?.let { parts.add("stake = ?"); args.add(it) }
        position?.let { parts.add("position = ?"); args.add(it) }
        fromTs?.let { parts.add("ts >= ?"); args.add(it.toString()) }
        toTs?.let { parts.add("ts <= ?"); args.add(it.toString()) }
        if (parts.isEmpty()) parts.add("1=1")
        return parts.joinToString(" AND ") to args.toTypedArray()
    }
}

data class Agg(
    val hands: Int = 0,
    val net: Double = 0.0,
    val netBb: Double = 0.0,
    val vpip: Int = 0,
    val pfr: Int = 0,
    val threeBet: Int = 0,
    val sawFlop: Int = 0,
    val showdown: Int = 0,
    val wonAtSd: Int = 0,
    val wonAfterFlop: Int = 0,
    val rake: Double = 0.0,
    val fees: Double = 0.0,
    val firstTs: Long = 0,
    val lastTs: Long = 0,
    val bestHand: Double = 0.0,
    val worstHand: Double = 0.0,
    val allIn: Int = 0
) {
    val bb100: Double get() = if (hands == 0) 0.0 else netBb / hands * 100.0
    val vpipPct: Double get() = pct(vpip, hands)
    val pfrPct: Double get() = pct(pfr, hands)
    val threeBetPct: Double get() = pct(threeBet, hands)
    val sawFlopPct: Double get() = pct(sawFlop, hands)
    val wtsdPct: Double get() = pct(showdown, sawFlop)
    val wsdPct: Double get() = pct(wonAtSd, showdown)
    val wwsfPct: Double get() = pct(wonAfterFlop, sawFlop)
    val rakeBb100: Double get() = 0.0

    private fun pct(a: Int, b: Int): Double = if (b == 0) 0.0 else a * 100.0 / b
}
