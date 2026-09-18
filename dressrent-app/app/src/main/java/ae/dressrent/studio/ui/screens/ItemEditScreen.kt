package ae.dressrent.studio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.Category
import ae.dressrent.studio.data.Item
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppTextField
import ae.dressrent.studio.ui.components.DropdownField
import ae.dressrent.studio.ui.components.FormRow
import ae.dressrent.studio.ui.components.FormSpacer
import ae.dressrent.studio.ui.components.MoneyField
import ae.dressrent.studio.ui.components.PhotoPicker
import ae.dressrent.studio.util.Money

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditScreen(vm: StudioViewModel, nav: NavHostController, itemId: Long) {
    val items by vm.items.collectAsState()
    val settings by vm.settings.collectAsState()
    val existing = remember(items, itemId) { items.firstOrNull { it.id == itemId } }

    var title by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Category.EVENING) }
    var size by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("") }
    var pricePerDay by remember { mutableStateOf(0L) }
    var deposit by remember { mutableStateOf(0L) }
    var purchaseCost by remember { mutableStateOf(0L) }
    var photoUri by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
        existing?.let {
            title = it.title
            brand = it.brand
            category = it.category
            size = it.size
            color = it.color
            pricePerDay = it.pricePerDay
            deposit = it.deposit
            purchaseCost = it.purchaseCost
            photoUri = it.photoUri
            active = it.active
            notes = it.notes
        }
    }

    val breakEven = if (pricePerDay > 0 && purchaseCost > 0) {
        ((purchaseCost + pricePerDay - 1) / pricePerDay).toInt()
    } else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (itemId == 0L) "Новый образ" else "Образ") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, "Удалить")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            PhotoPicker(photoUri, { photoUri = it })
            FormSpacer()
            AppTextField("Название", title, { title = it }, placeholder = "Silk Gown «Amal»")
            FormSpacer()
            FormRow {
                AppTextField("Бренд", brand, { brand = it }, Modifier.weight(1f))
                AppTextField("Размер", size, { size = it }, Modifier.weight(1f))
            }
            FormSpacer()
            FormRow {
                DropdownField(
                    label = "Категория",
                    options = Category.entries,
                    selected = category,
                    labelOf = { it.label },
                    onSelect = { category = it },
                    modifier = Modifier.weight(1f)
                )
                AppTextField("Цвет", color, { color = it }, Modifier.weight(1f))
            }
            FormSpacer()
            FormRow {
                MoneyField("Аренда за день", pricePerDay, { pricePerDay = it }, Modifier.weight(1f), settings.currency)
                MoneyField("Залог", deposit, { deposit = it }, Modifier.weight(1f), settings.currency)
            }
            FormSpacer()
            MoneyField(
                "Закупочная цена", purchaseCost, { purchaseCost = it },
                currency = settings.currency,
                supporting = if (breakEven > 0)
                    "Окупится за $breakEven аренд. При плане ${Money.formatShort(settings.dailyGoal, settings.currency)} в день это важно знать."
                else "Нужна, чтобы считать окупаемость"
            )
            FormSpacer()
            AppTextField("Заметки", notes, { notes = it }, singleLine = false)
            FormSpacer()

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("В работе", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Выключите, если образ в химчистке, продан или временно недоступен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = active, onCheckedChange = { active = it })
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    vm.saveItem(
                        Item(
                            id = itemId,
                            title = title.trim(),
                            brand = brand.trim(),
                            category = category,
                            size = size.trim(),
                            color = color.trim(),
                            pricePerDay = pricePerDay,
                            deposit = deposit,
                            purchaseCost = purchaseCost,
                            photoUri = photoUri,
                            active = active,
                            notes = notes.trim(),
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                    nav.popBackStack()
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить образ?") },
            text = { Text("Вместе с ним удалятся все брони и платежи по этому образу.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteItem(existing)
                    confirmDelete = false
                    nav.popBackStack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}
