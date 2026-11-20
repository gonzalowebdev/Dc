package com.gonzalowebdev.mediadownloader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gonzalowebdev.mediadownloader.data.ItemEntity
import com.gonzalowebdev.mediadownloader.data.JobStats
import com.gonzalowebdev.mediadownloader.data.Status
import com.gonzalowebdev.mediadownloader.domain.LinkType
import com.gonzalowebdev.mediadownloader.domain.Platform

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val jobs by vm.jobs.collectAsState()
    val failed by vm.failed.collectAsState()
    if (jobs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Todavía no hay descargas.\nPega un enlace en Inicio o compártelo desde Instagram, TikTok o Pinterest.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Descargas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(jobs, key = { it.id }) { j -> JobCard(vm, j, failed.filter { it.jobId == j.id }) }
    }
}

@Composable
private fun JobCard(vm: MainViewModel, j: JobStats, failedItems: List<ItemEntity>) {
    val platform = Platform.valueOf(j.platform)
    val complete = j.total > 0 && j.done == j.total
    val status = when {
        complete -> "Completado"
        j.active > 0 -> "Descargando…"
        j.pending > 0 -> "En cola"
        j.paused > 0 -> "En pausa"
        j.failed > 0 -> "Incompleto: ${j.failed} con error"
        else -> "—"
    }
    val percent = if (j.total == 0) 0 else j.done * 100 / j.total
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlatformIcon(platform, 30.dp)
            Column(Modifier.weight(1f)) {
                Text(j.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val type = runCatching { LinkType.valueOf(j.type).label }.getOrDefault("")
                Text("${platform.label} · $type", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (j.pending + j.active > 0) IconButton(onClick = { vm.pauseJob(j.id) }) { Icon(Icons.Rounded.Pause, "Pausar") }
            if (j.paused > 0) IconButton(onClick = { vm.resumeJob(j.id) }) { Icon(Icons.Rounded.PlayArrow, "Reanudar") }
            if (j.failed > 0) IconButton(onClick = { vm.retryFailed(j.id) }) { Icon(Icons.Rounded.Refresh, "Reintentar fallidos") }
            IconButton(onClick = { vm.deleteJob(j.id) }) { Icon(Icons.Rounded.Delete, "Quitar de la lista") }
        }
        LinearProgressIndicator(progress = { j.done.toFloat() / j.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
        Text(
            "$status · $percent% · Descargados ${j.done} de ${j.total} · Pendientes ${j.pending + j.paused} · Errores ${j.failed}",
            style = MaterialTheme.typography.bodySmall
        )
        failedItems.take(5).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.error ?: "Error", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
                IconButton(onClick = { vm.retryItem(f.id) }) { Icon(Icons.Rounded.Refresh, "Reintentar") }
            }
        }
        if (failedItems.size > 5) Text("… y ${failedItems.size - 5} errores más (usa Reintentar fallidos)", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val items by vm.history.collectAsState()
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("El historial está vacío.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Historial", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(items, key = { it.id }) { it2 ->
            val platform = Platform.valueOf(it2.platform)
            val ok = it2.status == Status.DONE
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlatformIcon(platform, 28.dp)
                    Column(Modifier.weight(1f)) {
                        Text(it2.savedName ?: it2.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${platform.label} · ${formatDate(it2.finishedAt ?: it2.createdAt)} · ${if (ok) formatBytes(it2.sizeBytes) else "Falló"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (ok) "Download/Media Downloader/${platform.folder}/" else (it2.error ?: "Error"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            maxLines = 2
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (ok) {
                        IconButton(onClick = { openFile(ctx, it2) }) { Icon(Icons.Rounded.OpenInNew, "Abrir") }
                        IconButton(onClick = { shareFile(ctx, it2) }) { Icon(Icons.Rounded.Share, "Compartir") }
                    } else {
                        IconButton(onClick = { vm.retryItem(it2.id) }) { Icon(Icons.Rounded.Refresh, "Reintentar") }
                    }
                    IconButton(onClick = { vm.deleteItem(it2.id) }) { Icon(Icons.Rounded.Delete, "Eliminar registro") }
                }
            }
        }
    }
}
