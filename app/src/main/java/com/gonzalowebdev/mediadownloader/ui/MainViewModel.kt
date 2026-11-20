package com.gonzalowebdev.mediadownloader.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gonzalowebdev.mediadownloader.App
import com.gonzalowebdev.mediadownloader.data.ItemEntity
import com.gonzalowebdev.mediadownloader.data.JobStats
import com.gonzalowebdev.mediadownloader.domain.LinkParser
import com.gonzalowebdev.mediadownloader.domain.LinkType
import com.gonzalowebdev.mediadownloader.domain.ParsedLink
import com.gonzalowebdev.mediadownloader.domain.ResolveException
import com.gonzalowebdev.mediadownloader.domain.Resolved
import com.gonzalowebdev.mediadownloader.net.Sessions
import com.gonzalowebdev.mediadownloader.net.YtDlp
import com.gonzalowebdev.mediadownloader.domain.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface HomeState {
    data object Idle : HomeState
    data class Resolving(val msg: String) : HomeState
    data class Ready(val resolved: Resolved) : HomeState
    data class Failure(val msg: String) : HomeState
    data class Enqueued(val msg: String) : HomeState
}

data class SettingsState(
    val wifiOnly: Boolean,
    val notifications: Boolean,
    val concurrency: Int,
    val pinterestConnected: Boolean,
    val clientId: String,
    val hasSecret: Boolean,
    val redirectUri: String,
    val sandbox: Boolean,
    val sessions: Set<String>
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val a = application as App
    private val dao = a.db.dao()

    var tab by mutableStateOf(0)
    var input by mutableStateOf("")
    var parsed by mutableStateOf<ParsedLink?>(null)
    var home by mutableStateOf<HomeState>(HomeState.Idle)
    var message by mutableStateOf<String?>(null)
    var clipboardSuggestion by mutableStateOf<String?>(null)
    var settings by mutableStateOf(readSettings())
    var ytdlpInfo by mutableStateOf("Iniciando motor de descargas…")
    var ytdlpBusy by mutableStateOf(false)
    private var job: Job? = null

    val jobs: StateFlow<List<JobStats>> =
        dao.jobStats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val failed: StateFlow<List<ItemEntity>> =
        dao.failedItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history: StateFlow<List<ItemEntity>> =
        dao.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun readSettings() = SettingsState(
        wifiOnly = a.prefs.wifiOnly,
        notifications = a.prefs.notifications,
        concurrency = a.prefs.concurrency,
        pinterestConnected = a.auth.hasToken(),
        clientId = a.prefs.pinterestClientId.orEmpty(),
        hasSecret = !a.prefs.pinterestClientSecret.isNullOrBlank(),
        redirectUri = a.prefs.pinterestRedirectUri,
        sandbox = a.prefs.pinterestSandbox,
        sessions = Platform.values().filter { Sessions.has(it) }.map { it.name }.toSet()
    )

    private fun refreshSettings() { settings = readSettings() }

    fun onSessionChanged() {
        refreshSettings()
        message = "Sesiones: " + (settings.sessions.joinToString(", ") { Platform.valueOf(it).label }.ifBlank { "ninguna" })
    }

    fun logout(p: Platform) {
        Sessions.clear(p)
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        refreshSettings()
        message = "Sesión de ${p.label} cerrada"
    }

    init { refreshYtdlp() }

    fun refreshYtdlp() {
        viewModelScope.launch(Dispatchers.IO) {
            ytdlpInfo = try { "yt-dlp ${YtDlp.version(a)}" } catch (e: Exception) { "yt-dlp no disponible: ${e.message}" }
        }
    }

    fun updateYtDlp() {
        if (ytdlpBusy) return
        ytdlpBusy = true
        message = "Actualizando yt-dlp…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = YtDlp.update(a)
                a.prefs.ytdlpLastUpdate = System.currentTimeMillis()
                message = "yt-dlp: $r"
            } catch (e: Exception) {
                message = "No se pudo actualizar yt-dlp: ${e.message}"
            }
            ytdlpBusy = false
            refreshYtdlp()
        }
    }

    // ---------- Enlaces ----------
    fun onInputChanged(s: String) {
        input = s
        parsed = LinkParser.parse(s)
        if (home !is HomeState.Resolving) home = HomeState.Idle
    }

    fun onShared(text: String?) {
        tab = 0
        val url = text?.let { LinkParser.extractUrl(it) }
        if (url == null) { message = "No encontré un enlace en lo compartido."; return }
        onInputChanged(url)
        val p = parsed
        if (p == null) message = "El enlace compartido no es de Instagram, TikTok ni Pinterest."
        else analyze(p, autoStart = false)
    }

    fun checkClipboard(ctx: Context) {
        if (input.isNotBlank()) { clipboardSuggestion = null; return }
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            if (!cm.hasPrimaryClip() || cm.primaryClipDescription?.hasMimeType("text/*") != true) {
                clipboardSuggestion = null; return
            }
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString()
            clipboardSuggestion = if (t != null && LinkParser.parse(t) != null) t else null
        } catch (e: Exception) { clipboardSuggestion = null }
    }

    fun useSuggestion() {
        clipboardSuggestion?.let { onInputChanged(it) }
        clipboardSuggestion = null
    }

    fun download() {
        val link = parsed
        if (link == null) { message = "Pega un enlace válido de Instagram, TikTok o Pinterest."; return }
        val st = home
        if (st is HomeState.Ready && st.resolved.sourceUrl == link.url) { enqueue(st.resolved); return }
        analyze(link, autoStart = link.type != LinkType.BOARD && link.type != LinkType.BOARD_SECTION)
    }

    private fun analyze(link: ParsedLink, autoStart: Boolean) {
        if (link.type == LinkType.UNKNOWN) {
            home = HomeState.Failure("Este enlace no corresponde a contenido descargable (publicación, reel, video, Pin o tablero).")
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            home = HomeState.Resolving("Analizando enlace…")
            try {
                val r = withContext(Dispatchers.IO) {
                    a.resolver.resolve(link) { m -> home = HomeState.Resolving(m) }
                }
                if (autoStart) enqueue(r) else home = HomeState.Ready(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResolveException) {
                home = HomeState.Failure(e.message ?: "No se pudo analizar el enlace.")
            } catch (e: Exception) {
                home = HomeState.Failure("Error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun enqueue(r: Resolved) {
        viewModelScope.launch {
            try {
                val res = a.repo.enqueue(r)
                home = if (res.added == 0)
                    HomeState.Enqueued("Todo el contenido (${res.alreadyDownloaded} archivos) ya estaba descargado.")
                else HomeState.Enqueued(
                    "Se agregaron ${res.added} archivos a Descargas" +
                        if (res.alreadyDownloaded > 0) " (${res.alreadyDownloaded} ya estaban descargados)." else "."
                )
            } catch (e: Exception) {
                home = HomeState.Failure("No se pudo iniciar la descarga: ${e.message}")
            }
        }
    }

    // ---------- Descargas ----------
    fun pauseJob(id: Long) = viewModelScope.launch { a.repo.pauseJob(id) }
    fun resumeJob(id: Long) = viewModelScope.launch { a.repo.resumeJob(id) }
    fun retryFailed(id: Long) = viewModelScope.launch { a.repo.retryFailed(id) }
    fun retryItem(id: Long) = viewModelScope.launch { a.repo.retryItem(id) }
    fun deleteItem(id: Long) = viewModelScope.launch { a.repo.deleteItem(id) }
    fun deleteJob(id: Long) = viewModelScope.launch { a.repo.deleteJob(id) }

    // ---------- Ajustes ----------
    fun setWifiOnly(v: Boolean) { a.prefs.wifiOnly = v; refreshSettings() }
    fun setNotifications(v: Boolean) { a.prefs.notifications = v; refreshSettings() }
    fun setConcurrency(v: Int) { a.prefs.concurrency = v; refreshSettings() }

    fun savePinterestConfig(clientId: String, secret: String, redirect: String, sandbox: Boolean) {
        a.prefs.pinterestClientId = clientId.trim()
        if (secret.isNotBlank()) a.prefs.pinterestClientSecret = secret.trim()
        a.prefs.pinterestRedirectUri = redirect.trim().ifBlank { com.gonzalowebdev.mediadownloader.data.Prefs.DEFAULT_REDIRECT }
        a.prefs.pinterestSandbox = sandbox
        refreshSettings()
        message = "Configuración de Pinterest guardada"
    }

    fun saveManualToken(token: String) {
        if (token.isBlank()) return
        a.prefs.accessToken = token.trim()
        a.prefs.refreshToken = null
        a.prefs.tokenExpiresAt = 0L
        refreshSettings()
        message = "Token guardado. Pinterest figura como conectado."
    }

    fun connectPinterest(ctx: Context) {
        if (a.prefs.pinterestClientSecret.isNullOrBlank() || a.prefs.pinterestClientId.isNullOrBlank()) {
            message = "Primero configura el App ID y el App secret de Pinterest."
            return
        }
        val uri = a.auth.authorizeUri() ?: return
        try {
            CustomTabsIntent.Builder().build().launchUrl(ctx, uri)
        } catch (e: Exception) {
            message = "No se pudo abrir el navegador: ${e.message}"
        }
    }

    fun handleOAuth(uri: Uri) {
        tab = 3
        val err = uri.getQueryParameter("error")
        if (err != null) { message = "Pinterest devolvió un error: $err"; return }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code == null || state == null || state != a.prefs.oauthState) {
            message = "La respuesta de Pinterest no es válida (estado OAuth distinto)."
            return
        }
        a.prefs.oauthState = null
        viewModelScope.launch {
            try {
                a.auth.exchangeCode(code)
                refreshSettings()
                message = "Pinterest conectado"
            } catch (e: Exception) {
                message = e.message ?: "No se pudo completar la conexión con Pinterest."
            }
        }
    }

    fun disconnectPinterest() {
        a.prefs.clearPinterestTokens()
        refreshSettings()
        message = "Pinterest desconectado"
    }
}
