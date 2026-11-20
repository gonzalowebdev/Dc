package com.gonzalowebdev.mediadownloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gonzalowebdev.mediadownloader.domain.Platform

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s = vm.settings
    var showConfig by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Configuración", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard {
            Text("Descargas", fontWeight = FontWeight.SemiBold)
            SwitchRow("Descargar solo con Wi-Fi", "Desactivado: usa Wi-Fi y datos móviles", s.wifiOnly) { vm.setWifiOnly(it) }
            SwitchRow("Notificaciones de descargas", null, s.notifications) { vm.setNotifications(it) }
            Text("Descargas simultáneas", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { n ->
                    FilterChip(selected = s.concurrency == n, onClick = { vm.setConcurrency(n) }, label = { Text("$n") })
                }
            }
        }

        SectionCard {
            Text("Cuentas conectadas", fontWeight = FontWeight.SemiBold)
            AccountRow(Platform.PINTEREST, if (s.pinterestConnected) "Conectada" else "Desconectada", s.pinterestConnected)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.pinterestConnected) {
                    OutlinedButton(onClick = { vm.disconnectPinterest() }) { Text("DESCONECTAR") }
                } else {
                    Button(onClick = { vm.connectPinterest(ctx) }) { Text("CONECTAR") }
                }
                OutlinedButton(onClick = { showConfig = true }) { Text("CONFIGURAR API") }
            }
            HorizontalLine()
            AccountRow(Platform.INSTAGRAM, "Desconectada", false)
            Text(
                "Instagram no ofrece OAuth para descargar contenido: solo se intentan publicaciones públicas sin iniciar sesión.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalLine()
            AccountRow(Platform.TIKTOK, "Desconectada", false)
            Text(
                "TikTok no ofrece OAuth para descargar videos de terceros: solo se intenta contenido público.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard {
            Text("Almacenamiento", fontWeight = FontWeight.SemiBold)
            Text("Download/Media Downloader/{Instagram, TikTok, Pinterest}/", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Sin subcarpetas. Si un nombre ya existe se agrega _1, _2… y nunca se sobrescribe.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard {
            Text("Información", fontWeight = FontWeight.SemiBold)
            Text("Media Downloader Hub 1.0.0", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Uso personal. Respeta los términos de cada plataforma y los derechos de autor. No se almacenan contraseñas; los tokens se guardan cifrados.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showConfig) PinterestConfigDialog(vm, onClose = { showConfig = false })
}

@Composable
private fun HorizontalLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp)) {
        Surface(color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxSize()) {}
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AccountRow(p: Platform, status: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlatformIcon(p, 30.dp)
        Column(Modifier.weight(1f)) {
            Text(p.label, fontWeight = FontWeight.Medium)
            Text(
                status, style = MaterialTheme.typography.bodySmall,
                color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PinterestConfigDialog(vm: MainViewModel, onClose: () -> Unit) {
    val s = vm.settings
    var clientId by remember { mutableStateOf(s.clientId) }
    var secret by remember { mutableStateOf("") }
    var redirect by remember { mutableStateOf(s.redirectUri) }
    var sandbox by remember { mutableStateOf(s.sandbox) }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("API de Pinterest") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Datos de tu app en developers.pinterest.com. Registra el redirect URI exactamente como aparece abajo.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(clientId, { clientId = it }, label = { Text("App ID") }, singleLine = true)
                OutlinedTextField(
                    secret, { secret = it },
                    label = { Text(if (s.hasSecret) "App secret (guardado; vacío = no cambiar)" else "App secret") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(redirect, { redirect = it }, label = { Text("Redirect URI") }, singleLine = true)
                SwitchRow("Usar API sandbox (acceso Trial)", null, sandbox) { sandbox = it }
                Text("Alternativa: pegar un token de acceso generado en el panel de Pinterest.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    token, { token = it }, label = { Text("Token de acceso (opcional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.savePinterestConfig(clientId, secret, redirect, sandbox)
                if (token.isNotBlank()) vm.saveManualToken(token)
                onClose()
            }) { Text("GUARDAR") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("CANCELAR") } }
    )
}
