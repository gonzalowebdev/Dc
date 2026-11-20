package com.gonzalowebdev.mediadownloader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gonzalowebdev.mediadownloader.data.ItemEntity
import com.gonzalowebdev.mediadownloader.domain.Platform
import com.gonzalowebdev.mediadownloader.download.guessMime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun DrawScope.drawNote(color: Color, dx: Float) {
    val s = size.minDimension
    val w = s * 0.08f
    drawLine(color, Offset(s * 0.56f + dx, s * 0.24f), Offset(s * 0.56f + dx, s * 0.66f), strokeWidth = w, cap = StrokeCap.Round)
    drawCircle(color, radius = s * 0.13f, center = Offset(s * 0.45f + dx, s * 0.68f))
    drawLine(color, Offset(s * 0.56f + dx, s * 0.24f), Offset(s * 0.72f + dx, s * 0.36f), strokeWidth = w, cap = StrokeCap.Round)
}

@Composable
fun PlatformIcon(platform: Platform, size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        when (platform) {
            Platform.INSTAGRAM -> {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5)),
                        start = Offset(0f, s), end = Offset(s, 0f)
                    ),
                    cornerRadius = CornerRadius(s * 0.28f)
                )
                drawRoundRect(
                    Color.White, topLeft = Offset(s * 0.2f, s * 0.2f), size = Size(s * 0.6f, s * 0.6f),
                    cornerRadius = CornerRadius(s * 0.18f), style = Stroke(s * 0.07f)
                )
                drawCircle(Color.White, radius = s * 0.15f, style = Stroke(s * 0.07f))
                drawCircle(Color.White, radius = s * 0.04f, center = Offset(s * 0.7f, s * 0.3f))
            }
            Platform.PINTEREST -> {
                drawCircle(Color(0xFFE60023))
                drawCircle(Color.White, radius = s * 0.19f, center = Offset(s * 0.5f, s * 0.4f), style = Stroke(s * 0.1f))
                drawLine(Color.White, Offset(s * 0.47f, s * 0.55f), Offset(s * 0.4f, s * 0.82f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
            }
            Platform.TIKTOK -> {
                drawCircle(Color.Black)
                drawNote(Color(0xFF25F4EE), -s * 0.03f)
                drawNote(Color(0xFFFE2C55), s * 0.03f)
                drawNote(Color.White, 0f)
            }
        }
    }
}

@Composable
fun AppLogo() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8B7CFF), Color(0xFF3DDCC8)))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Download, contentDescription = null, tint = Color(0xFF0B0B10)) }
        Column {
            Text("Media Downloader Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Instagram · TikTok · Pinterest", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

fun formatBytes(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
}

fun formatDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "AR")).format(Date(ms))

fun openFile(ctx: Context, item: ItemEntity) {
    val loc = item.location ?: return
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(loc), guessMime(item.savedName ?: item.fileName))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se pudo abrir el archivo (¿fue movido o eliminado?)", Toast.LENGTH_SHORT).show()
    }
}

fun shareFile(ctx: Context, item: ItemEntity) {
    val loc = item.location ?: return
    try {
        val send = Intent(Intent.ACTION_SEND).setType(guessMime(item.savedName ?: item.fileName))
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(loc))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Compartir archivo"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "No se pudo compartir el archivo", Toast.LENGTH_SHORT).show()
    }
}
