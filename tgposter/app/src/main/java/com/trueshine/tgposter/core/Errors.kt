package com.trueshine.tgposter.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Человекочитаемое описание исключения.
 *
 * У части системных исключений message пустой (например, у
 * NetworkOnMainThreadException), и без этого пользователь видел бы «null».
 */
fun Throwable.describe(): String {
    val detail = message?.takeIf { it.isNotBlank() }
    return when (this) {
        is UnknownHostException -> "нет подключения к интернету"
        is SocketTimeoutException -> "сервер не ответил вовремя"
        is SSLException -> "ошибка защищённого соединения" + (detail?.let { " ($it)" } ?: "")
        is IOException -> detail ?: "сбой соединения"
        else -> detail ?: this::class.java.simpleName
    }
}
