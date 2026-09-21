package com.plovault.sync.sync

import android.content.Context
import com.plovault.sync.data.ExportRecipe
import com.plovault.sync.data.FolderImporter
import com.plovault.sync.data.HandRepository
import com.plovault.sync.data.ImportPipeline
import com.plovault.sync.data.Prefs
import com.plovault.sync.net.Downloader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncOutcome(val ok: Boolean, val newHands: Int, val message: String)

/**
 * Автоматическая выгрузка: повторяет записанный запрос PokerCraft за нужный
 * период, скачивает файл и заливает раздачи в базу.
 */
class SyncEngine(private val context: Context) {

    private val prefs = Prefs(context)
    private val repo = HandRepository(context)
    private val pipeline = ImportPipeline(context)
    private val downloader = Downloader(context)
    private val folder = FolderImporter(context)

    fun sync(): SyncOutcome {
        // 1. Папка загрузок — работает без токена и без рецепта.
        val folderResult = if (prefs.watchFolderUri != null) folder.scan() else null

        val json = prefs.recipeJson
        if (json == null) {
            return if (folderResult != null) {
                prefs.lastSyncTs = System.currentTimeMillis()
                prefs.lastSyncStatus = folderResult.message
                SyncOutcome(true, folderResult.newHands, folderResult.message)
            } else {
                fail(
                    "Нечего синхронизировать: не записан рецепт выгрузки и не выбрана папка загрузок. " +
                        "Проще всего выбрать папку — тогда скачанные из PokerCraft файлы будут " +
                        "разбираться автоматически."
                )
            }
        }
        val recipe = ExportRecipe.fromJson(json)
            ?: return fail("Рецепт повреждён — запишите его заново в режиме обучения.")

        val now = System.currentTimeMillis()
        val lastHand = repo.lastHandTs()
        val windowMs = prefs.syncWindowDays * 24L * 3600_000L
        val from = if (lastHand > 0) maxOf(lastHand - windowMs, 0L) else now - windowMs
        val to = now

        if (recipe.usesToken && prefs.authToken.isNullOrBlank()) {
            if (folderResult != null && folderResult.newHands > 0) {
                prefs.lastSyncTs = System.currentTimeMillis()
                prefs.lastSyncStatus = folderResult.message + " (рецепту не хватает токена)"
                return SyncOutcome(true, folderResult.newHands, prefs.lastSyncStatus)
            }
            return fail(
                "В рецепте нужен токен, а он не сохранён. Откройте PokerCraft в клиенте GGPoker, " +
                    "скопируйте ссылку и вставьте её в настройках приложения."
            )
        }

        val res = downloader.run(recipe, from, to)
        if (!res.ok) {
            if (folderResult != null && folderResult.newHands > 0) {
                prefs.lastSyncTs = System.currentTimeMillis()
                prefs.lastSyncStatus = folderResult.message + " (выгрузка по рецепту не прошла: HTTP ${res.status})"
                return SyncOutcome(true, folderResult.newHands, prefs.lastSyncStatus)
            }
            val expired = res.status == 401 || res.status == 403
            return fail(
                if (expired) {
                    "Токен PokerCraft истёк (HTTP ${res.status}). Откройте PokerCraft в клиенте GGPoker, " +
                        "скопируйте свежую ссылку и вставьте её на вкладке «Синхро» — обучать заново не нужно."
                } else {
                    "Не удалось скачать выгрузку (${res.error ?: "пустой ответ"}). " +
                        "Проверьте связь или откройте PokerCraft в приложении."
                }
            )
        }

        var newHands = folderResult?.newHands ?: 0
        val first = pipeline.importBytes(res.bytes, res.fileName, "auto")
        newHands += first.handsNew
        val messages = ArrayList<String>()
        messages.add(first.message)

        // Ответ мог быть JSON'ом со ссылками на настоящие файлы — качаем их.
        for (u in first.followUpUrls.take(20)) {
            val f = downloader.get(u)
            if (!f.ok) continue
            val r = pipeline.importBytes(f.bytes, f.fileName, "auto-followup")
            newHands += r.handsNew
            messages.add(r.message)
        }

        prefs.lastSyncTs = now
        val period = "${dt(from)} — ${dt(to)}"
        val msg = if (newHands > 0) {
            "Синхронизация ОК ($period): новых раздач $newHands"
        } else {
            "Синхронизация ОК ($period): новых раздач нет. ${messages.firstOrNull() ?: ""}"
        }
        prefs.lastSyncStatus = msg
        return SyncOutcome(true, newHands, msg)
    }

    private fun fail(msg: String): SyncOutcome {
        prefs.lastSyncStatus = "Ошибка: $msg"
        prefs.lastSyncTs = System.currentTimeMillis()
        return SyncOutcome(false, 0, msg)
    }

    private fun dt(ts: Long) = SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ts))
}
