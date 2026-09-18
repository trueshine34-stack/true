package ae.dressrent.studio.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.Booking
import ae.dressrent.studio.data.BookingStatus
import ae.dressrent.studio.data.Client
import ae.dressrent.studio.data.LeadSource
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppTextField
import ae.dressrent.studio.ui.components.DateField
import ae.dressrent.studio.ui.components.DropdownField
import ae.dressrent.studio.ui.components.FormRow
import ae.dressrent.studio.ui.components.FormSpacer
import ae.dressrent.studio.ui.components.MoneyField
import ae.dressrent.studio.util.Dates
import ae.dressrent.studio.util.Intents
import ae.dressrent.studio.util.Money
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingEditScreen(vm: StudioViewModel, nav: NavHostController, bookingId: Long) {
    val items by vm.items.collectAsState()
    val clients by vm.clients.collectAsState()
    val bookings by vm.bookings.collectAsState()
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val existing = remember(bookings, bookingId) {
        bookings.firstOrNull { it.booking.id == bookingId }?.booking
    }

    var itemId by remember { mutableStateOf(0L) }
    var clientId by remember { mutableStateOf(0L) }
    var start by remember { mutableStateOf(LocalDate.now()) }
    var end by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var price by remember { mutableStateOf(0L) }
    var deposit by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf(BookingStatus.LEAD) }
    var eventName by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var priceTouched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newClient by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
        existing?.let {
            itemId = it.itemId
            clientId = it.clientId
            start = it.start
            end = it.end
            price = it.price
            deposit = it.deposit
            status = it.status
            eventName = it.eventName
            notes = it.notes
            priceTouched = true
        }
    }

    val selectedItem = items.firstOrNull { it.id == itemId }
    val days = (end.toEpochDay() - start.toEpochDay() + 1).coerceAtLeast(1)

    // Price follows the rate card until the user overrides it by hand.
    LaunchedEffect(itemId, start, end) {
        if (!priceTouched && selectedItem != null) {
            price = selectedItem.pricePerDay * days
            deposit = selectedItem.deposit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (bookingId == 0L) "Новая бронь" else "Изменить бронь") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
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
            if (items.isEmpty()) {
                Text(
                    "Сначала добавьте хотя бы один образ в гардероб.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                FormSpacer()
                Button(onClick = { nav.navigate("item/edit/0") }) { Text("Добавить образ") }
                return@Scaffold
            }

            DropdownField(
                label = "Образ",
                options = items,
                selected = selectedItem,
                labelOf = { "${it.title} · ${Money.formatShort(it.pricePerDay, settings.currency)}/день" },
                onSelect = {
                    itemId = it.id
                    priceTouched = false
                }
            )
            FormSpacer()

            DropdownField(
                label = "Клиентка",
                options = clients,
                selected = clients.firstOrNull { it.id == clientId },
                labelOf = { if (it.phone.isBlank()) it.name else "${it.name} · ${it.phone}" },
                onSelect = { clientId = it.id }
            )
            TextButton(onClick = { newClient = true }) { Text("+ Новая клиентка") }
            FormSpacer()

            FormRow {
                DateField("Выдача", start, {
                    start = it
                    if (end.isBefore(it)) end = it
                    priceTouched = false
                }, Modifier.weight(1f))
                DateField("Возврат", end, {
                    end = if (it.isBefore(start)) start else it
                    priceTouched = false
                }, Modifier.weight(1f))
            }
            Text(
                "$days ${Dates.plural(days, "день", "дня", "дней")} аренды",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            FormSpacer()

            FormRow {
                MoneyField(
                    "Стоимость аренды", price,
                    { price = it; priceTouched = true },
                    Modifier.weight(1f), settings.currency
                )
                MoneyField(
                    "Залог", deposit,
                    { deposit = it },
                    Modifier.weight(1f), settings.currency
                )
            }
            Text(
                "≈ ${Money.usd(price, settings.aedPerUsd)} · план дня ${Money.formatShort(settings.dailyGoal, settings.currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            FormSpacer()

            DropdownField(
                label = "Статус",
                options = BookingStatus.entries,
                selected = status,
                labelOf = { it.label },
                onSelect = { status = it }
            )
            FormSpacer()

            AppTextField("Повод", eventName, { eventName = it }, placeholder = "Свадьба, гала-ужин, съёмка…")
            FormSpacer()
            AppTextField("Заметки", notes, { notes = it }, singleLine = false)

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (itemId == 0L || clientId == 0L) {
                        error = "Выберите образ и клиентку."
                        return@Button
                    }
                    scope.launch {
                        val conflict = status.blocksCalendar &&
                            vm.hasConflict(itemId, start, end, bookingId)
                        if (conflict) {
                            error = "Этот образ уже занят на выбранные даты. " +
                                "Выберите другие даты или другой образ."
                            return@launch
                        }
                        val saved = vm.saveBooking(
                            Booking(
                                id = bookingId,
                                itemId = itemId,
                                clientId = clientId,
                                startDate = start.toEpochDay(),
                                endDate = end.toEpochDay(),
                                eventName = eventName.trim(),
                                price = price,
                                deposit = deposit,
                                status = status,
                                notes = notes.trim(),
                                createdAt = existing?.createdAt ?: System.currentTimeMillis()
                            )
                        )
                        Intents.toast(context, "Сохранено")
                        nav.popBackStack()
                        if (bookingId == 0L) nav.navigate("booking/$saved")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }

    if (newClient) {
        NewClientDialog(
            onDismiss = { newClient = false },
            onSave = { name, phone ->
                vm.saveClient(Client(name = name, phone = phone, source = LeadSource.WHATSAPP))
                newClient = false
            }
        )
    }
}

@Composable
private fun NewClientDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая клиентка") },
        text = {
            Column {
                AppTextField("Имя", name, { name = it })
                FormSpacer()
                AppTextField(
                    "Телефон", phone, { phone = it },
                    placeholder = "971501234567",
                    keyboardType = KeyboardType.Phone,
                    supporting = "Нужен для кнопки WhatsApp"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), phone.trim()) }, enabled = name.isNotBlank()) {
                Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
