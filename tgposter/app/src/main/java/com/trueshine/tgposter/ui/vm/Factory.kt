package com.trueshine.tgposter.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trueshine.tgposter.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer не предоставлен")
}

@Suppress("UNCHECKED_CAST")
class SimpleVmFactory(private val creator: () -> ViewModel) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}

@Composable
inline fun <reified VM : ViewModel> rememberVm(
    key: String? = null,
    crossinline creator: (AppContainer) -> VM,
): VM {
    val container = LocalContainer.current
    val factory = remember(key) { SimpleVmFactory { creator(container) } }
    return viewModel(modelClass = VM::class.java, key = key, factory = factory)
}
