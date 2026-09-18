package ae.dressrent.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.BookingStatus
import ae.dressrent.studio.data.Payment
import ae.dressrent.studio.data.PaymentKind
import ae.dressrent.studio.data.PaymentMethod
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppCard
import ae.dressrent.studio.ui.components.DropdownField
import ae.dressrent.studio.ui.components.FormSpacer
import ae.dressrent.studio.ui.components.KeyValueRow
import ae.dressrent.studio.ui.components.MoneyField
import ae.dressrent.studio.ui.components.Pill
import ae.dressrent.studio.ui.components.SectionHeader
import ae.dressrent.studio.util.Dates
import ae.dressrent.studio.util.Intents
import ae.dressrent.studio.util.Messages
import ae.dressrent.studio.util.Money
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingDetailScreen(vm: StudioViewModel, nav: NavHostController, bookingId: Long) {
    val card by vm.booking(bookingId).collectAsState(initial = null)
    val payments by vm.payments(bookingId).collectAsState(initial = emptyList())
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    var showPayment by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val current = card

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.clientName ?: "Бронь") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate("booking/edit/$bookingId") }) {
                        Icon(Icons.Default.Edit, "Изменить")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, "Удалить")
                    }
                }
            )
        }
    ) { padding ->
        if (current == null) {
            Text("Бронь не найдена", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            AppCard(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(current.booking.status.label)
                    Spacer(Modifier.weight(1f))
                    Text(
                        Money.format(current.booking.price, settings.currency),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Образ", current.itemTitle)
                KeyValueRow("Даты", Dates.range(current.booking.start, current.booking.end))
                KeyValueRow(
                    "Дней",
                    "${current.booking.days} " +
                        Dates.plural(current.booking.days.toLong(), "день", "дня", "дней")
                )
                if (current.booking.eventName.isNotBlank()) {
                    KeyValueRow("Повод", current.booking.eventName)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                KeyValueRow("Получено", Money.format(current.collected, settings.currency))
                KeyValueRow("Остаток", Money.format(current.outstanding, settings.currency), bold = true)
                if (current.booking.deposit > 0) {
                    KeyValueRow("Залог", Money.format(current.booking.deposit, settings.currency))
                }
                if (current.booking.notes.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(current.booking.notes, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SectionHeader("Написать клиентке", "Текст уже готов — остаётся отправить")
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val templates = buildList {
                    add("Подтверждение брони" to Messages.confirmation(current, settings))
                    add("Напоминание о выдаче" to Messages.pickupToday(current, settings))
                    add("Напоминание о возврате" to Messages.returnDue(current, settings))
                    if (current.booking.status == BookingStatus.LEAD) {
                        add("Дожать заявку" to Messages.leadFollowUp(current, settings))
                    }
                    if (current.booking.status == BookingStatus.RETURNED) {
                        add("Попросить отзыв" to Messages.reviewRequest(current, settings))
                    }
                }
                templates.forEach { (label, message) ->
                    OutlinedButton(
                        onClick = {
                            Intents.openWhatsApp(
                                context, current.clientPhone, message, settings.countryCode
                            )
                            vm.touchClient(current.booking.clientId)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Chat, null, Modifier.height(17.dp))
                        Text("  $label")
                    }
                }
                if (current.clientPhone.isNotBlank()) {
                    TextButton(onClick = { Intents.call(context, current.clientPhone, settings.countryCode) }) {
                        Icon(Icons.Default.Call, null, Modifier.height(17.dp))
                        Text("  Позвонить ${current.clientPhone}")
                    }
                }
            }

            SectionHeader("Статус")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val next = when (current.booking.status) {
                    BookingStatus.LEAD -> BookingStatus.CONFIRMED
                    BookingStatus.CONFIRMED -> BookingStatus.OUT
                    BookingStatus.OUT -> BookingStatus.RETURNED
                    else -> null
                }
                if (next != null) {
                    Button(
                        onClick = { vm.setStatus(bookingId, next) },
                        modifier = Modifier.weight(1f)
                    ) { Text("→ ${next.label}") }
                }
                if (current.booking.status != BookingStatus.CANCELLED) {
                    OutlinedButton(
                        onClick = { vm.setStatus(bookingId, BookingStatus.CANCELLED) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Отменить") }
                }
            }

            SectionHeader(
                "Платежи",
                "Выручка засчитывается в план того дня, когда деньги получены"
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (payments.isEmpty()) {
                    Text(
                        "Платежей ещё нет.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    payments.forEach { payment ->
                        AppCard(Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(payment.kind.label, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${Dates.human(LocalDate.ofEpochDay(payment.date))} · ${payment.method.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    Money.format(payment.amount, settings.currency),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                IconButton(onClick = { vm.deletePayment(payment) }) {
                                    Icon(Icons.Default.Delete, "Удалить платёж")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = { showPayment = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Добавить платёж") }
            }
        }
    }

    if (showPayment && current != null) {
        PaymentDialog(
            suggested = current.outstanding,
            currency = settings.currency,
            onDismiss = { showPayment = false },
            onSave = { amount, kind, method ->
                vm.addPayment(
                    Payment(
                        bookingId = bookingId,
                        amount = amount,
                        date = LocalDate.now().toEpochDay(),
                        kind = kind,
                        method = method
                    )
                )
                showPayment = false
            }
        )
    }

    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить бронь?") },
            text = { Text("Платежи по этой брони тоже будут удалены, и выручка пересчитается.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBooking(current.booking)
                    confirmDelete = false
                    nav.popBackStack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun PaymentDialog(
    suggested: Long,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (Long, PaymentKind, PaymentMethod) -> Unit
) {
    var amount by remember { mutableStateOf(suggested) }
    var kind by remember { mutableStateOf(PaymentKind.BALANCE) }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый платёж") },
        text = {
            Column {
                MoneyField("Сумма", amount, { amount = it }, currency = currency)
                FormSpacer()
                DropdownField(
                    label = "Тип",
                    options = PaymentKind.entries,
                    selected = kind,
                    labelOf = { it.label },
                    onSelect = { kind = it }
                )
                FormSpacer()
                DropdownField(
                    label = "Способ",
                    options = PaymentMethod.entries,
                    selected = method,
                    labelOf = { it.label },
                    onSelect = { method = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(amount, kind, method) },
                enabled = amount > 0
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
