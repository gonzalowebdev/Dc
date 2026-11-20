package com.gonzalowebdev.mediadownloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.gonzalowebdev.mediadownloader.domain.MediaKind
import com.gonzalowebdev.mediadownloader.domain.Resolved

@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.checkClipboard(ctx) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppLogo()

        vm.clipboardSuggestion?.let {
            SectionCard {
                Text("Hay un enlace compatible en el portapapeles", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { vm.useSuggestion() }) { Text("USAR ENLACE") }
            }
        }

        OutlinedTextField(
            value = vm.input,
            onValueChange = vm::onInputChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enlace de Instagram, TikTok o Pinterest") },
            singleLine = true,
            trailingIcon = {
                if (vm.input.isNotEmpty()) IconButton(onClick = { vm.onInputChanged("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Borrar")
                }
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { clipboard.getText()?.text?.let { vm.onInputChanged(it) } },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("PEGAR")
            }
            Button(
                onClick = { vm.download() },
                modifier = Modifier.weight(1f),
                enabled = vm.home !is HomeState.Resolving
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("DESCARGAR")
            }
        }

        val p = vm.parsed
        if (p != null) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlatformIcon(p.platform, 34.dp)
                    Column {
                        Text(p.platform.label, fontWeight = FontWeight.SemiBold)
                        Text("Contenido: ${p.type.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (vm.input.isNotBlank()) {
            Text("No reconozco este enlace.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        when (val st = vm.home) {
            HomeState.Idle -> Unit
            is HomeState.Resolving -> SectionCard {
                Text(st.msg, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is HomeState.Ready -> ReadyCard(st.resolved) { vm.download() }
            is HomeState.Failure -> SectionCard {
                Text("No se pudo completar", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(st.msg, style = MaterialTheme.typography.bodyMedium)
            }
            is HomeState.Enqueued -> SectionCard {
                Text(st.msg, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.tab = 1 }) { Text("VER DESCARGAS") }
            }
        }
    }
}

@Composable
private fun ReadyCard(r: Resolved, onConfirm: () -> Unit) {
    val images = r.entries.count { it.kind == MediaKind.IMAGE }
    val videos = r.entries.count { it.kind == MediaKind.VIDEO }
    SectionCard {
        r.previewUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "${r.type.label} · ${r.entries.size} archivos ($images imágenes, $videos videos)",
            style = MaterialTheme.typography.bodyMedium
        )
        r.notes.forEach {
            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("DESCARGAR TODO (${r.entries.size})") }
    }
}
