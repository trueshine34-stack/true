#!/usr/bin/env python3
"""Regenerate the signing vectors the native tests are pinned to.

The vectors come from Polymarket's own client, so this is the only place the
expected values are allowed to change from. Never hand-edit the generated
Kotlin: the exchange is what we are matching, not the file.

    pip install git+https://github.com/Polymarket/py-clob-client-v2.git
    python3 tools/gen_reference_vectors.py > \\
        android/app/src/test/java/com/polybot/btc5m/bot/ReferenceVectors.kt
"""

import base64
import sys

from py_clob_client_v2.order_builder.builder import ROUNDING_CONFIG, OrderBuilder
from py_clob_client_v2.order_utils.exchange_order_builder_v2 import (
    ExchangeOrderBuilderV2,
)
from py_clob_client_v2.order_utils.model.order_data_v2 import OrderDataV2
from py_clob_client_v2.order_utils.model.side import Side
from py_clob_client_v2.order_utils.model.signature_type_v2 import SignatureTypeV2
from py_clob_client_v2.signer import Signer
from py_clob_client_v2.signing.eip712 import sign_clob_auth_message
from py_clob_client_v2.signing.hmac import build_hmac_signature

# A well-known throwaway key; it holds nothing and never will.
PRIVATE_KEY = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
EXCHANGE_V2 = "0xE111180000d2663C0091e4f400237545B87B996B"
NEG_RISK_EXCHANGE_V2 = "0xe2222d279d744050d28e00520010520000310F59"

# Cover every signature type and both exchanges, since each one changes a field
# that goes into the hash.
ORDER_CASES = [
    (
        EXCHANGE_V2,
        False,
        SignatureTypeV2.POLY_PROXY,
        "0x1111111111111111111111111111111111111111",
        "87465024265663983453498414425405578094829774831585265350989993735252357608746",
        "2000000",
        "4000000",
        Side.BUY,
        "123456789",
        "1700000000000",
    ),
    (
        EXCHANGE_V2,
        False,
        SignatureTypeV2.EOA,
        None,  # signer's own address
        "67464111329884829809698715411318025916720630698461118762480635776286189367930",
        "2550000",
        "5000000",
        Side.BUY,
        "987654321987654321",
        "1787205600123",
    ),
    (
        NEG_RISK_EXCHANGE_V2,
        True,
        SignatureTypeV2.POLY_GNOSIS_SAFE,
        "0xabCDEF0000000000000000000000000000000123",
        "14962795813408211288733361863077102787980028577150059935007151111838741304490",
        "5000000",
        "9803921",
        Side.SELL,
        "1",
        "1",
    ),
]

# L1 auth is what mints the API credentials; a wrong domain here means the
# wallet can never authenticate at all.
AUTH_CASES = [
    (1700000000, 0),
    (1787205600, 7),
]

HMAC_CASES = [
    ("1700000000", "POST", "/order", '{"a":1,"b":"x"}'),
    ("1787205600", "GET", "/balance-allowance", ""),
    ("1", "DELETE", "/cancel-market-orders", '{"market":"0xabc"}'),
]

PRICES = [0.01, 0.05, 0.11, 0.28, 0.33, 0.4, 0.47, 0.5, 0.51, 0.53, 0.66, 0.75, 0.9, 0.99]
MARKET_STAKES = [2.0, 2.55, 5.0]
LIMIT_SIZES = [5.0, 7.14, 12.5]


def kotlin_string(value) -> str:
    escaped = str(value).replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


def main() -> None:
    signer = Signer(PRIVATE_KEY, 137)
    address = signer.address()

    orders = []
    for exchange, neg_risk, sig_type, maker, token, mk, tk, side, salt, ts in ORDER_CASES:
        builder = ExchangeOrderBuilderV2(
            exchange, 137, signer, generate_salt=lambda s=salt: s
        )
        data = OrderDataV2(
            maker=maker or address,
            tokenId=token,
            makerAmount=mk,
            takerAmount=tk,
            side=side,
            signer=address,
            signatureType=sig_type,
            timestamp=ts,
        )
        typed = builder.build_order_typed_data(builder.build_order(data))
        orders.append(
            "OrderVector({}, {}, {}, {}, {}, {}, {}, {}, {}, {}L, {}, {})".format(
                kotlin_string(exchange),
                str(neg_risk).lower(),
                int(sig_type),
                kotlin_string(maker or address),
                kotlin_string(token),
                kotlin_string(mk),
                kotlin_string(tk),
                kotlin_string("BUY" if side == Side.BUY else "SELL"),
                kotlin_string(salt),
                ts,
                kotlin_string(builder.build_order_hash(typed)),
                kotlin_string(builder.build_order_signature(typed)),
            )
        )

    auths = [
        "AuthVector({}L, {}, {})".format(
            timestamp,
            nonce,
            kotlin_string(sign_clob_auth_message(signer, timestamp, nonce)),
        )
        for timestamp, nonce in AUTH_CASES
    ]

    secret = base64.urlsafe_b64encode(b"0123456789abcdef0123456789abcdef").decode()
    hmacs = [
        "HmacVector({}, {}, {}, {}, {})".format(
            kotlin_string(ts),
            kotlin_string(method),
            kotlin_string(path),
            kotlin_string(body),
            kotlin_string(
                build_hmac_signature(secret, ts, method, path, body if body else None)
            ),
        )
        for ts, method, path, body in HMAC_CASES
    ]

    order_builder = OrderBuilder(signer)
    config = ROUNDING_CONFIG["0.01"]
    amounts = []
    for price in PRICES:
        for stake in MARKET_STAKES:
            _, mk, tk = order_builder.get_market_order_amounts("BUY", stake, price, config)
            amounts.append(("market_buy", stake, price, mk, tk))
        for size in LIMIT_SIZES:
            _, mk, tk = order_builder.get_order_amounts("BUY", size, price, config)
            amounts.append(("limit_buy", size, price, mk, tk))
            _, mk, tk = order_builder.get_order_amounts("SELL", size, price, config)
            amounts.append(("limit_sell", size, price, mk, tk))

    amount_lines = [
        "AmountVector({}, {}, {}, {}, {})".format(
            kotlin_string(kind), amount, price, kotlin_string(mk), kotlin_string(tk)
        )
        for kind, amount, price, mk, tk in amounts
    ]

    joiner = ",\n        "
    sys.stdout.write(
        f"""package com.polybot.btc5m.bot

/**
 * Signing vectors produced by Polymarket's own py-clob-client-v2.
 *
 * Regenerate with tools/gen_reference_vectors.py if the reference client
 * changes its wire format; never hand-edit an expected value to make a test
 * pass, because the exchange is the thing being matched, not this file.
 */
object ReferenceVectors {{

    const val PRIVATE_KEY = {kotlin_string(PRIVATE_KEY)}
    const val ADDRESS = {kotlin_string(address)}
    const val SECRET = {kotlin_string(secret)}

    data class OrderVector(
        val verifyingContract: String,
        val negRisk: Boolean,
        val signatureType: Int,
        val maker: String,
        val tokenId: String,
        val makerAmount: String,
        val takerAmount: String,
        val side: String,
        val salt: String,
        val timestampMs: Long,
        val digest: String,
        val signature: String,
    )

    data class AuthVector(
        val timestamp: Long,
        val nonce: Int,
        val signature: String,
    )

    data class HmacVector(
        val timestamp: String,
        val method: String,
        val path: String,
        val body: String,
        val expected: String,
    )

    data class AmountVector(
        val kind: String,
        val amount: Double,
        val price: Double,
        val maker: String,
        val taker: String,
    )

    val ORDERS = listOf(
        {joiner.join(orders)},
    )

    val AUTHS = listOf(
        {joiner.join(auths)},
    )

    val HMACS = listOf(
        {joiner.join(hmacs)},
    )

    val AMOUNTS = listOf(
        {joiner.join(amount_lines)},
    )
}}
"""
    )


if __name__ == "__main__":
    main()
