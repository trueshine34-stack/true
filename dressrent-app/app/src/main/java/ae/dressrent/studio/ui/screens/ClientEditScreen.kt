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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import ae.dressrent.studio.data.Client
import ae.dressrent.studio.data.LeadSource
import ae.dressrent.studio.ui.StudioViewModel
import ae.dressrent.studio.ui.components.AppTextField
import ae.dressrent.studio.ui.components.DropdownField
import ae.dressrent.studio.ui.components.FormSpacer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientEditScreen(vm: StudioViewModel, nav: NavHostController, clientId: Long) {
    val clients by vm.clients.collectAsState()
    val existing = remember(clients, clientId) { clients.firstOrNull { it.id == clientId } }

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(LeadSource.INSTAGRAM) }
    var notes by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(existing) {
        existing?.let {
            name = it.name
            phone = it.phone
            source = it.source
            notes = it.notes
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (clientId == 0L) "Новая клиентка" else "Клиентка") },
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
            AppTextField("Имя", name, { name = it })
            FormSpacer()
            AppTextField(
                "Телефон", phone, { phone = it },
                placeholder = "971501234567",
                keyboardType = KeyboardType.Phone,
                supporting = "Без плюса и пробелов — так работает кнопка WhatsApp"
            )
            FormSpacer()
            DropdownField(
                label = "Откуда пришла",
                options = LeadSource.entries,
                selected = source,
                labelOf = { it.label },
                onSelect = { source = it }
            )
            FormSpacer()
            AppTextField("Заметки", notes, { notes = it }, singleLine = false, placeholder = "Размер, предпочтения, повод")

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    vm.saveClient(
                        Client(
                            id = clientId,
                            name = name.trim(),
                            phone = phone.trim(),
                            source = source,
                            notes = notes.trim(),
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                            lastContactAt = existing?.lastContactAt
                        )
                    )
                    nav.popBackStack()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить") }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить клиентку?") },
            text = { Text("Её брони и платежи тоже будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteClient(existing)
                    confirmDelete = false
                    nav.popBackStack()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}
