package com.gonzalowebdev.mediadownloader.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro local de errores y del último cierre inesperado, para poder copiarlo y enviarlo. */
object DiagLog {
    private lateinit var dir: File

    fun init(ctx: Context) { dir = ctx.filesDir }

    private fun now(): String = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())

    @Synchronized
    fun add(tag: String, msg: String) {
        try {
            val f = File(dir, "diag.log")
            f.appendText("${now()} [$tag] ${msg.take(1500)}\n")
            if (f.length() > 60_000) f.writeText(f.readText().takeLast(30_000))
        } catch (e: Throwable) { /* sin registro */ }
    }

    fun crash(t: Throwable) {
        try {
            File(dir, "crash.log").writeText("${now()}\n${android.util.Log.getStackTraceString(t)}".take(8000))
        } catch (e: Throwable) { /* nada */ }
    }

    fun report(): String {
        val crash = try { File(dir, "crash.log").takeIf { it.exists() }?.readText() } catch (e: Throwable) { null }
        val log = try { File(dir, "diag.log").takeIf { it.exists() }?.readText()?.takeLast(12_000) } catch (e: Throwable) { null }
        return buildString {
            appendLine("Media Downloader Hub 1.3.0 · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("== Último cierre inesperado ==")
            appendLine(crash ?: "(ninguno registrado)")
            appendLine()
            appendLine("== Registro de errores ==")
            appendLine(log ?: "(vacío)")
        }
    }

    fun clear() {
        try { File(dir, "crash.log").delete(); File(dir, "diag.log").delete() } catch (e: Throwable) { /* nada */ }
    }
}
