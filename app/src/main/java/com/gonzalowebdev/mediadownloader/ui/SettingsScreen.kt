package com.gonzalowebdev.mediadownloader.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onSessionChanged() }
    fun login(p: Platform) = loginLauncher.launch(Intent(ctx, LoginActivity::class.java).putExtra("platform", p.name))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Configuración", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionCard {
            Text("Cuentas conectadas", fontWeight = FontWeight.SemiBold)
            Text(
                "Inicias sesión en la página real de cada red. La app no ve ni guarda tu contraseña: solo guarda la sesión, cifrada en tu teléfono.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AccountBlock(
                Platform.PINTEREST, Platform.PINTEREST.name in s.sessions,
                "Necesaria para descargar tableros (incluidos los secretos) y secciones.",
                { login(Platform.PINTEREST) }, { vm.logout(Platform.PINTEREST) }
            )
            OutlinedButton(onClick = { showConfig = true }) { Text("PINTEREST API (AVANZADO)") }
            HorizontalLine()
            AccountBlock(
                Platform.INSTAGRAM, Platform.INSTAGRAM.name in s.sessions,
                "Necesaria para publicaciones de imágenes, carruseles, reels y Stories.",
                { login(Platform.INSTAGRAM) }, { vm.logout(Platform.INSTAGRAM) }
            )
            HorizontalLine()
            AccountBlock(
                Platform.TIKTOK, Platform.TIKTOK.name in s.sessions,
                "Opcional: los videos públicos funcionan sin sesión.",
                { login(Platform.TIKTOK) }, { vm.logout(Platform.TIKTOK) }
            )
            Text(
                "Aviso: usar tu cuenta con herramientas automáticas puede llevar a que Instagram o TikTok limiten tu cuenta de forma temporal. Evita descargar cientos de elementos de golpe.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

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
            Text("Motor de descargas", fontWeight = FontWeight.SemiBold)
            Text(vm.ytdlpInfo, style = MaterialTheme.typography.bodyMedium)
            Text(
                "yt-dlp se actualiza solo una vez por día. Si Instagram o TikTok dejan de funcionar, actualízalo acá.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { vm.updateYtDlp() }, enabled = !vm.ytdlpBusy) { Text("ACTUALIZAR YT-DLP") }
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
            Text("Media Downloader Hub 1.2.0", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Uso personal. Respeta los términos de cada plataforma y los derechos de autor.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showConfig) PinterestConfigDialog(vm, onClose = { showConfig = false })
}

@Composable
private fun AccountBlock(p: Platform, connected: Boolean, hint: String, onLogin: () -> Unit, onLogout: () -> Unit) {
    AccountRow(p, if (connected) "Sesión iniciada" else "Desconectada", connected)
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (connected) OutlinedButton(onClick = onLogout) { Text("CERRAR SESIÓN") }
    else Button(onClick = onLogin) { Text("INICIAR SESIÓN") }
}

@Composable
private fun HorizontalLine() {
    Box(Modifier.fillMaxWidth().height(1.dp)) {
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
    val ctx = LocalContext.current
    val s = vm.settings
    var clientId by remember { mutableStateOf(s.clientId) }
    var secret by remember { mutableStateOf("") }
    var redirect by remember { mutableStateOf(s.redirectUri) }
    var sandbox by remember { mutableStateOf(s.sandbox) }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pinterest API (avanzado, opcional)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "No hace falta para el uso normal: iniciar sesión en Pinterest alcanza. Esto es solo una vía alternativa con la API oficial (estado: ${if (s.pinterestConnected) "conectada" else "desconectada"}).",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(clientId, { clientId = it }, label = { Text("App ID") }, singleLine = true)
                OutlinedTextField(
                    secret, { secret = it },
                    label = { Text(if (s.hasSecret) "App secret (guardado; vacío = no cambiar)" else "App secret") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(redirect, { redirect = it }, label = { Text("Redirect URI") }, singleLine = true)
                SwitchRow("Usar API sandbox (acceso Trial)", null, sandbox) { sandbox = it }
                OutlinedTextField(
                    token, { token = it }, label = { Text("Token de acceso (opcional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        vm.savePinterestConfig(clientId, secret, redirect, sandbox)
                        vm.connectPinterest(ctx)
                        onClose()
                    }) { Text("CONECTAR") }
                    if (s.pinterestConnected) TextButton(onClick = { vm.disconnectPinterest(); onClose() }) { Text("DESCONECTAR") }
                }
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
