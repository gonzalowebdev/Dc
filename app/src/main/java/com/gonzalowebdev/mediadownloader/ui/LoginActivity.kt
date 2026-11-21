package com.gonzalowebdev.mediadownloader.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gonzalowebdev.mediadownloader.domain.Platform
import com.gonzalowebdev.mediadownloader.net.Sessions

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private fun baseUrl(p: Platform) = when (p) {
    Platform.INSTAGRAM -> "https://www.instagram.com"
    Platform.TIKTOK -> "https://www.tiktok.com"
    Platform.PINTEREST -> "https://www.pinterest.com"
}

private fun loginUrl(p: Platform) = when (p) {
    Platform.INSTAGRAM -> "https://www.instagram.com/accounts/login/"
    Platform.TIKTOK -> "https://www.tiktok.com/login"
    Platform.PINTEREST -> "https://www.pinterest.com/login/"
}

private fun isLoggedIn(p: Platform, header: String) = when (p) {
    Platform.INSTAGRAM -> header.contains("sessionid=")
    Platform.TIKTOK -> header.contains("sessionid=") || header.contains("sid_tt=")
    Platform.PINTEREST -> header.contains("_pinterest_sess=")
}

/** Guarda la sesión si el usuario ya inició sesión en el WebView. */
private fun capture(p: Platform): Boolean {
    CookieManager.getInstance().flush()
    val h = CookieManager.getInstance().getCookie(baseUrl(p))
    if (h.isNullOrBlank() || !isLoggedIn(p, h)) return false
    Sessions.save(p, h)
    return true
}

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = Platform.valueOf(intent.getStringExtra("platform") ?: Platform.INSTAGRAM.name)
        // Sesión nueva: se borran las cookies del WebView (la app ya guarda las suyas cifradas).
        CookieManager.getInstance().removeAllCookies(null)
        setContent {
            AppTheme {
                LoginScreen(platform) { ok ->
                    setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                    finish()
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(platform: Platform, onDone: (Boolean) -> Unit) {
    var status by remember { mutableStateOf("Inicia sesión en ${platform.label}. Esta ventana se cierra sola al terminar.") }
    var finished by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (capture(platform)) { finished = true; onDone(true) }
                else status = "No detecté la sesión todavía. Termina de iniciar sesión y toca LISTO."
            }) { Text("LISTO") }
            TextButton(onClick = { onDone(false) }) { Text("CANCELAR") }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = MOBILE_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme?.lowercase()
                            return scheme != "http" && scheme != "https" // bloquea snssdk://, intent://, etc.
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (!finished && capture(platform)) { finished = true; onDone(true) }
                        }
                    }
                    loadUrl(loginUrl(platform))
                }
            }
        )
    }
}
