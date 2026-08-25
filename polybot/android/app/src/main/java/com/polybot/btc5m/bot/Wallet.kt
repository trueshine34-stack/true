package com.polybot.btc5m.bot

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import org.json.JSONObject

/**
 * Moving money off the exchange.
 *
 * Polymarket's balance is bridged USDC (USDC.e) on Polygon, held by the funder
 * address. Getting it out is a plain ERC-20 transfer — but only when the funder
 * is the signing key's own address. A proxy wallet (email or browser login)
 * holds the money in a contract that only Polymarket's relayer can move, and
 * that relayer has no documented interface; guessing at one with real money on
 * the line is not something this app will do.
 *
 * Nothing here bridges. USDC.e leaves on Polygon and arrives on Polygon; a
 * different chain or a different token is a swap the receiving wallet has to
 * make. The panel says so rather than implying otherwise.
 */
object Wallet {

    const val CHAIN_ID = 137L

    /** USDC.e — the bridged dollar Polymarket settles in. Six decimals. */
    const val USDC = "0x2791bca1f2de4661ed88a30c99a7a9449aa84174"

    private const val USDC_DECIMALS = 6

    /** Public Polygon endpoints, tried in order. */
    private val RPCS = listOf(
        "https://polygon-bor-rpc.publicnode.com",
        "https://1rpc.io/matic",
        "https://polygon.drpc.org",
    )

    /** Polygon will not include a transaction under about 25 gwei of tip. */
    private val MIN_PRIORITY_WEI: BigInteger = BigInteger.valueOf(30_000_000_000L)

    private const val TRANSFER = "a9059cbb"
    private const val BALANCE_OF = "70a08231"

    data class Info(
        val address: String,
        /** USDC.e held on chain, in dollars. */
        val usdc: Double,
        /** POL held on chain — the gas a transfer costs. */
        val gas: Double,
        /** What one transfer costs in POL, at the fees quoted right now. */
        val fee: Double,
        val canSend: Boolean,
        /** Why not, when it cannot. */
        val note: String?,
    )

    data class Sent(val hash: String)

    /**
     * What the wallet can do, read fresh from the chain.
     *
     * The balance shown here is the on-chain one rather than the exchange's,
     * because it is the one a transfer can actually spend: collateral locked
     * behind a resting order is still the exchange's until the order is pulled.
     */
    fun info(account: Account): Info {
        val address = account.funderAddress
        if (account.signatureType != SignatureType.EOA) {
            return Info(
                address = address,
                usdc = 0.0,
                gas = 0.0,
                fee = 0.0,
                canSend = false,
                note = "Средства держит прокси-кошелёк Polymarket — вывести из " +
                    "приложения нельзя, только через сайт Polymarket.",
            )
        }
        if (!account.funderAddress.equals(account.signerAddress, ignoreCase = true)) {
            return Info(
                address = address,
                usdc = 0.0,
                gas = 0.0,
                fee = 0.0,
                canSend = false,
                note = "Ключ подписи не владеет адресом с деньгами.",
            )
        }

        val usdc = fromUnits(tokenBalance(USDC, address), USDC_DECIMALS)
        val gas = fromUnits(nativeBalance(address), 18)
        val fee = fromUnits(feeEstimate(), 18)

        val note = when {
            usdc <= 0.0 -> "На адресе нет USDC — вероятно, деньги ещё на бирже."
            gas < fee -> "Не хватает POL на газ: нужно около " +
                String.format("%.3f", fee) + " POL, на адресе " +
                String.format("%.3f", gas) + "."
            else -> null
        }
        return Info(address, usdc, gas, fee, canSend = note == null, note = note)
    }

    /**
     * Send USDC.e to an address on Polygon.
     *
     * The amount is capped at what the address actually holds — asking for more
     * than that reverts, and a reverted transfer still costs the gas.
     */
    fun send(keyPair: Secp256k1.KeyPair, account: Account, to: String, amountUsd: Double): Sent {
        require(account.signatureType == SignatureType.EOA) {
            "вывод возможен только с обычного кошелька"
        }
        require(to.matches(Regex("^0x[0-9a-fA-F]{40}$"))) { "неверный адрес" }
        require(amountUsd > 0.0) { "нечего выводить" }

        val from = account.funderAddress
        val held = tokenBalance(USDC, from)
        val want = toUnits(amountUsd, USDC_DECIMALS)
        val value = if (want > held) held else want
        require(value.signum() > 0) { "на адресе нет USDC" }

        val data = TRANSFER + word(to) + word(value)
        val nonce = quantity("eth_getTransactionCount", listOf(quote(from), quote("pending")))
        val (priority, maxFee) = fees()
        val gasLimit = estimateGas(from, USDC, data)

        val raw = Tx(
            chainId = CHAIN_ID,
            nonce = nonce,
            maxPriorityFeePerGas = priority,
            maxFeePerGas = maxFee,
            gasLimit = gasLimit,
            to = USDC,
            value = BigInteger.ZERO,
            data = hexToBytes(data),
        ).sign(keyPair)

        val hash = rpc("eth_sendRawTransaction", listOf(quote("0x" + Secp256k1.toHex(raw))))
            .optString("result")
        if (hash.isNullOrEmpty()) throw ApiException("узел не принял транзакцию")
        return Sent(hash)
    }

    // ---- chain reads -------------------------------------------------------

    private fun tokenBalance(token: String, owner: String): BigInteger {
        val result = call(token, BALANCE_OF + word(owner))
        return if (result.isEmpty()) BigInteger.ZERO else BigInteger(result, 16)
    }

    private fun nativeBalance(address: String): BigInteger =
        quantity("eth_getBalance", listOf(quote(address), quote("latest")))

    /**
     * What a transfer costs at the fees quoted right now, in wei. Sixty
     * thousand gas covers an ERC-20 transfer with room to spare.
     */
    private fun feeEstimate(): BigInteger {
        val (_, maxFee) = fees()
        return maxFee.multiply(BigInteger.valueOf(60_000L))
    }

    /** Tip and ceiling, from the chain's own numbers. */
    private fun fees(): Pair<BigInteger, BigInteger> {
        val base = try {
            val block = rpc(
                "eth_getBlockByNumber",
                listOf(quote("latest"), "false"),
            ).optJSONObject("result")
            hexToBig(block?.optString("baseFeePerGas"))
        } catch (e: Exception) {
            BigInteger.ZERO
        }
        val tip = try {
            quantity("eth_maxPriorityFeePerGas", emptyList()).max(MIN_PRIORITY_WEI)
        } catch (e: Exception) {
            MIN_PRIORITY_WEI
        }
        // Room for two base-fee doublings, which is what a wallet leaves: the
        // difference is refunded, an underpriced transaction simply sticks.
        return tip to base.multiply(BigInteger.TWO).add(tip)
    }

    private fun estimateGas(from: String, to: String, data: String): BigInteger {
        val params = "{\"from\":${quote(from)},\"to\":${quote(to)},\"data\":${quote("0x$data")}}"
        return try {
            val used = quantity("eth_estimateGas", listOf(params))
            // A quarter over the estimate: the token's own bookkeeping costs
            // more the first time an address is credited.
            used.multiply(BigInteger.valueOf(125)).divide(BigInteger.valueOf(100))
        } catch (e: Exception) {
            BigInteger.valueOf(120_000L)
        }
    }

    private fun call(to: String, data: String): String {
        val params = "{\"to\":${quote(to)},\"data\":${quote("0x$data")}}"
        val result = rpc("eth_call", listOf(params, quote("latest"))).optString("result")
        return result.orEmpty().removePrefix("0x")
    }

    private fun quantity(method: String, params: List<String>): BigInteger =
        hexToBig(rpc(method, params).optString("result"))

    /** Ask each endpoint in turn; a public node being down is ordinary. */
    private fun rpc(method: String, params: List<String>): JSONObject {
        val body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":${quote(method)}," +
            "\"params\":[${params.joinToString(",")}]}"
        var last: Exception? = null
        for (url in RPCS) {
            try {
                val json = JSONObject(
                    Http.postJson(url, body, mapOf("Content-Type" to "application/json")),
                )
                json.optJSONObject("error")?.let {
                    throw ApiException(it.optString("message", "ошибка узла"))
                }
                return json
            } catch (e: Exception) {
                last = e
            }
        }
        throw ApiException(last?.message ?: "сеть Polygon недоступна")
    }

    // ---- encoding ----------------------------------------------------------

    /** One 32-byte ABI word, from an address or an unsigned integer. */
    internal fun word(value: String): String =
        value.removePrefix("0x").lowercase().padStart(64, '0')

    internal fun word(value: BigInteger): String =
        value.toString(16).padStart(64, '0')

    internal fun toUnits(amount: Double, decimals: Int): BigInteger =
        BigDecimal.valueOf(amount)
            .setScale(decimals, RoundingMode.DOWN)
            .movePointRight(decimals)
            .toBigIntegerExact()

    internal fun fromUnits(value: BigInteger, decimals: Int): Double =
        BigDecimal(value).movePointLeft(decimals).toDouble()

    private fun quote(value: String) = "\"$value\""

    private fun hexToBig(hex: String?): BigInteger {
        val clean = hex?.removePrefix("0x").orEmpty()
        return if (clean.isEmpty()) BigInteger.ZERO else BigInteger(clean, 16)
    }

    internal fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or
                Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /**
     * An EIP-1559 (type 2) transaction.
     *
     * Signed over `0x02 || rlp(fields)` and broadcast as the same list with the
     * signature appended — the shape every Ethereum node has expected since
     * London, and the only one Polygon prices sanely.
     */
    internal class Tx(
        val chainId: Long,
        val nonce: BigInteger,
        val maxPriorityFeePerGas: BigInteger,
        val maxFeePerGas: BigInteger,
        val gasLimit: BigInteger,
        val to: String,
        val value: BigInteger,
        val data: ByteArray,
    ) {
        private fun fields(): List<ByteArray> = listOf(
            Rlp.int(BigInteger.valueOf(chainId)),
            Rlp.int(nonce),
            Rlp.int(maxPriorityFeePerGas),
            Rlp.int(maxFeePerGas),
            Rlp.int(gasLimit),
            Rlp.bytes(hexToBytes(to)),
            Rlp.int(value),
            Rlp.bytes(data),
            Rlp.list(emptyList()),
        )

        fun unsigned(): ByteArray = byteArrayOf(0x02) + Rlp.list(fields())

        fun sign(keyPair: Secp256k1.KeyPair): ByteArray {
            val digest = Secp256k1.keccak256(unsigned())
            val sig = Secp256k1.sign(digest, keyPair)
            // Typed transactions carry the parity bit itself, not 27 plus it.
            val yParity = (sig.v.toInt() - 27).toLong()
            val signed = fields() + listOf(
                Rlp.int(BigInteger.valueOf(yParity)),
                Rlp.int(BigInteger(1, sig.r)),
                Rlp.int(BigInteger(1, sig.s)),
            )
            return byteArrayOf(0x02) + Rlp.list(signed)
        }
    }

    /**
     * Just enough RLP to build one transaction.
     *
     * Integers are minimal big-endian and zero is the empty string — a leading
     * zero byte makes a different, invalid encoding, which is the classic way
     * to produce a transaction no node will accept.
     */
    internal object Rlp {
        fun int(value: BigInteger): ByteArray {
            if (value.signum() <= 0) return bytes(ByteArray(0))
            val raw = value.toByteArray()
            val trimmed = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            return bytes(trimmed)
        }

        fun bytes(input: ByteArray): ByteArray = when {
            input.size == 1 && (input[0].toInt() and 0xff) < 0x80 -> input
            input.size <= 55 -> byteArrayOf((0x80 + input.size).toByte()) + input
            else -> {
                val len = lengthBytes(input.size)
                byteArrayOf((0xb7 + len.size).toByte()) + len + input
            }
        }

        fun list(items: List<ByteArray>): ByteArray {
            var payload = ByteArray(0)
            for (item in items) payload += item
            return if (payload.size <= 55) {
                byteArrayOf((0xc0 + payload.size).toByte()) + payload
            } else {
                val len = lengthBytes(payload.size)
                byteArrayOf((0xf7 + len.size).toByte()) + len + payload
            }
        }

        private fun lengthBytes(length: Int): ByteArray {
            var value = length
            val out = ArrayList<Byte>()
            while (value > 0) {
                out.add(0, (value and 0xff).toByte())
                value = value shr 8
            }
            return out.toByteArray()
        }
    }
}
