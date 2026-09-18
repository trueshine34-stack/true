package ae.dressrent.studio.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object Intents {

    /** wa.me needs digits only; anything a human typed gets normalised here. */
    fun normalizePhone(raw: String, countryCode: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith(countryCode) -> digits
            digits.startsWith("0") -> countryCode + digits.drop(1)
            else -> countryCode + digits
        }
    }

    fun openWhatsApp(context: Context, phone: String, message: String, countryCode: String = "971") {
        val number = normalizePhone(phone, countryCode)
        val url = if (number.isEmpty()) {
            "https://wa.me/?text=${Uri.encode(message)}"
        } else {
            "https://wa.me/$number?text=${Uri.encode(message)}"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            share(context, message)
        }
    }

    fun call(context: Context, phone: String, countryCode: String = "971") {
        val number = normalizePhone(phone, countryCode)
        if (number.isEmpty()) return
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$number"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun share(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Отправить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(context, "Не удалось открыть приложение") }
    }

    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
