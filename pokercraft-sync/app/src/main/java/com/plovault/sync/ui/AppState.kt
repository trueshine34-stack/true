package com.plovault.sync.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.plovault.sync.data.Filter
import com.plovault.sync.data.HandRepository
import com.plovault.sync.data.ImportPipeline
import com.plovault.sync.data.Prefs

/** Общее состояние экранов. */
class AppState(val context: Context) {
    val prefs = Prefs(context)
    val repo = HandRepository(context)
    val pipeline = ImportPipeline(context)

    var totalHands by mutableStateOf(0)
        private set
    var lastHandTs by mutableStateOf(0L)
        private set
    var stakes by mutableStateOf<List<String>>(emptyList())
        private set
    var busy by mutableStateOf(false)
    var filter by mutableStateOf(Filter())
    var refreshTick by mutableStateOf(0)
        private set

    init {
        applyPrefsFilter()
        refresh()
    }

    fun applyPrefsFilter() {
        filter = if (prefs.onlyPlo4Rush) Filter(game = "PLO4", rushOnly = true)
        else Filter(game = null, rushOnly = false)
    }

    fun refresh() {
        totalHands = runCatching { repo.totalHands() }.getOrDefault(0)
        lastHandTs = runCatching { repo.lastHandTs() }.getOrDefault(0L)
        stakes = runCatching { repo.stakes(filter.copy(stake = null)) }.getOrDefault(emptyList())
        refreshTick++
    }
}
