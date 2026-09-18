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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppCard
import ae.dressrent.studio.ui.components.AppTextField
import ae.dressrent.studio.ui.components.DropdownField
import ae.dressrent.studio.ui.components.FormRow
import ae.dressrent.studio.ui.components.FormSpacer
import ae.dressrent.studio.ui.components.MoneyField
import ae.dressrent.studio.ui.components.SectionHeader
import ae.dressrent.studio.util.Intents
import ae.dressrent.studio.util.Money
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: StudioViewModel, nav: NavHostController) {
    val saved by vm.settings.collectAsState()
    val dashboard by vm.dashboard.collectAsState()
    val items by vm.items.collectAsState()
    val context = LocalContext.current

    var form by remember { mutableStateOf(saved) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { form = saved }

    val averagePrice = remember(items) {
        val active = items.filter { it.active && it.pricePerDay > 0 }
        if (active.isEmpty()) 0L else active.sumOf { it.pricePerDay } / active.size
    }
    val rentalsNeeded = if (averagePrice > 0) ceil(form.dailyGoal.toDouble() / averagePrice).toInt() else 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
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
                .padding(bottom = 32.dp)
        ) {
            SectionHeader("Цель по деньгам", "От неё считается всё на главном экране")
            Column(Modifier.padding(horizontal = 16.dp)) {
                MoneyField(
                    "План на день",
                    form.dailyGoal,
                    { form = form.copy(dailyGoal = it) },
                    currency = form.currency,
                    supporting = "≈ ${Money.usd(form.dailyGoal, form.aedPerUsd)} в день, " +
                        "${Money.usd(form.dailyGoal * 30, form.aedPerUsd)} в месяц"
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 200).forEach { dollars ->
                        AssistChip(
                            onClick = {
                                form = form.copy(
                                    dailyGoal = (dollars * form.aedPerUsd * 100).toLong()
                                )
                            },
                            label = { Text("\$$dollars / день") }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                AppCard {
                    Text("Что это значит на практике", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (rentalsNeeded > 0)
                            "Средняя аренда в вашем гардеробе — ${Money.format(averagePrice, form.currency)} в день. " +
                                "Чтобы закрывать план, нужно $rentalsNeeded " +
                                "${plural(rentalsNeeded)} в день — или примерно " +
                                "${rentalsNeeded * 30} в месяц. " +
                                "Приложение не создаёт эти заказы само: оно следит, чтобы ни одна заявка, " +
                                "доплата и просрочка не потерялись."
                        else "Добавьте образы с ценами — и здесь появится расчёт, сколько аренд в день " +
                            "нужно для вашего плана.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FormSpacer()
                FormRow {
                    AppTextField(
                        "Валюта", form.currency,
                        { form = form.copy(currency = it.uppercase().take(3)) },
                        Modifier.weight(1f)
                    )
                    AppTextField(
                        "Курс к \$", form.aedPerUsd.toString(),
                        { form = form.copy(aedPerUsd = it.replace(",", ".").toDoubleOrNull() ?: form.aedPerUsd) },
                        Modifier.weight(1f),
                        keyboardType = KeyboardType.Decimal
                    )
                }
            }

            SectionHeader("Студия")
            Column(Modifier.padding(horizontal = 16.dp)) {
                AppTextField("Название", form.businessName, { form = form.copy(businessName = it) })
                FormSpacer()
                AppTextField("Адрес шоурума", form.showroomAddress, { form = form.copy(showroomAddress = it) })
                FormSpacer()
                FormRow {
                    AppTextField(
                        "Код страны", form.countryCode,
                        { form = form.copy(countryCode = it.filter { c -> c.isDigit() }.take(4)) },
                        Modifier.weight(1f),
                        keyboardType = KeyboardType.Phone
                    )
                    AppTextField(
                        "Промокод", form.promoCode,
                        { form = form.copy(promoCode = it.uppercase()) },
                        Modifier.weight(1f)
                    )
                }
            }

            SectionHeader("Напоминания", "Сводка приходит один раз в день")
            Column(Modifier.padding(horizontal = 16.dp)) {
                DropdownField(
                    label = "Время утренней сводки",
                    options = (6..22).toList(),
                    selected = form.reminderHour,
                    labelOf = { "%02d:00".format(it) },
                    onSelect = { form = form.copy(reminderHour = it) }
                )
                FormSpacer()
                FormRow {
                    DropdownField(
                        label = "Догрев заявки через",
                        options = listOf(1, 2, 3, 5, 7),
                        selected = form.leadFollowUpDays,
                        labelOf = { "$it дн." },
                        onSelect = { form = form.copy(leadFollowUpDays = it) },
                        modifier = Modifier.weight(1f)
                    )
                    DropdownField(
                        label = "«Давно не была»",
                        options = listOf(30, 45, 60, 90),
                        selected = form.dormantDays,
                        labelOf = { "$it дн." },
                        onSelect = { form = form.copy(dormantDays = it) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val (title, body) = vm.previewBrief()
                        ae.dressrent.studio.work.Notifications.showBrief(context, title, body)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Показать сводку сейчас") }
            }

            SectionHeader("Данные")
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(
                    onClick = { vm.seedDemo { Intents.toast(context, "Демо-данные загружены") } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Загрузить демо-данные") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Очистить все данные") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Все данные хранятся только на этом телефоне. Ни один платёж и ни один контакт " +
                        "не уходит на сервер: аккаунтов и подписки у приложения нет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Сегодня в работе: ${dashboard.actions.size} " +
                        "${tasksPlural(dashboard.actions.size)} на сумму " +
                        Money.format(dashboard.potentialToday, form.currency) + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    vm.saveSettings(form)
                    Intents.toast(context, "Настройки сохранены")
                    nav.popBackStack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) { Text("Сохранить") }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить все данные?") },
            text = { Text("Гардероб, клиенты, брони и платежи будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll { Intents.toast(context, "Данные очищены") }
                    confirmClear = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
}

private fun plural(n: Int): String = ae.dressrent.studio.util.Dates.plural(
    n.toLong(), "аренда", "аренды", "аренд"
)

private fun tasksPlural(n: Int): String = ae.dressrent.studio.util.Dates.plural(
    n.toLong(), "задача", "задачи", "задач"
)

