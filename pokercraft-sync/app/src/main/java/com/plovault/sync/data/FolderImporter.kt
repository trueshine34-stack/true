package com.plovault.sync.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

data class FolderScanResult(
    val checked: Int,
    val imported: Int,
    val newHands: Int,
    val message: String
)

/**
 * Следит за папкой загрузок: как только там появляется выгрузка PokerCraft
 * (zip или txt с историей рук), приложение само её разбирает.
 *
 * Работает без токена и без записанного рецепта — достаточно один раз выдать
 * доступ к папке. Файлы читаются только на чтение, ничего не удаляется.
 */
class FolderImporter(private val context: Context) {

    private val prefs = Prefs(context)
    private val pipeline = ImportPipeline(context)

    fun folderName(): String? = prefs.watchFolderUri?.let { uri ->
        runCatching { Uri.parse(uri).lastPathSegment?.substringAfterLast(':') }.getOrNull()
    }

    /** Сохраняет выбранную папку и берёт долгоживущее разрешение на чтение. */
    fun useFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs.watchFolderUri = treeUri.toString()
    }

    fun forget() {
        prefs.watchFolderUri = null
        prefs.seenFolderFiles = emptySet()
    }

    fun scan(maxFileBytes: Long = 80L * 1024 * 1024): FolderScanResult {
        val treeUriString = prefs.watchFolderUri
            ?: return FolderScanResult(0, 0, 0, "Папка не выбрана")
        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull()
            ?: return FolderScanResult(0, 0, 0, "Папка недоступна")

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
        }.getOrElse { return FolderScanResult(0, 0, 0, "Папка недоступна — выберите её заново") }

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        var checked = 0
        var imported = 0
        var newHands = 0
        val messages = ArrayList<String>()

        val cursor = runCatching { context.contentResolver.query(childrenUri, projection, null, null, null) }
            .getOrNull() ?: return FolderScanResult(0, 0, 0, "Не удалось прочитать папку — выдайте доступ заново")

        cursor.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val size = c.getLong(2)
                if (!looksLikeExport(name) || size <= 0 || size > maxFileBytes) continue

                val key = "$name:$size"
                if (key in prefs.seenFolderFiles) continue
                checked++
                prefs.markFolderFileSeen(key)

                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val bytes = runCatching {
                    context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                }.getOrNull() ?: continue

                val res = pipeline.importBytes(bytes, name, "folder")
                if (res.handsFound > 0) {
                    imported++
                    newHands += res.handsNew
                    messages.add("$name: +${res.handsNew}")
                }
            }
        }

        prefs.lastFolderScanTs = System.currentTimeMillis()
        val msg = when {
            checked == 0 -> "Новых файлов в папке нет"
            imported == 0 -> "Проверено файлов: $checked, историй рук среди них нет"
            else -> "Импортировано файлов: $imported, новых раздач: $newHands"
        }
        return FolderScanResult(checked, imported, newHands, msg)
    }

    private fun looksLikeExport(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".zip") || n.endsWith(".txt")
    }
}
