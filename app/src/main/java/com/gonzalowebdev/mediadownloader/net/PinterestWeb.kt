package com.gonzalowebdev.mediadownloader.net

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.gonzalowebdev.mediadownloader.domain.Platform
import com.gonzalowebdev.mediadownloader.domain.ResolveException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hace las consultas a Pinterest desde un WebView (motor real de Chrome) con la sesión del usuario,
 * porque Pinterest rechaza (403) las mismas consultas hechas con un cliente HTTP común.
 */
object PinterestWeb {
    private lateinit var app: Context
    private var web: WebView? = null
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String>>>()
    private val counter = AtomicInteger(0)

    fun init(ctx: Context) { app = ctx.applicationContext }

    private class Bridge {
        @JavascriptInterface
        fun done(id: Int, status: Int, body: String) {
            pending.remove(id)?.complete(status to body)
        }
    }

    /** Descarta el WebView (por ejemplo, al cambiar la sesión) para que se vuelva a cargar con las cookies nuevas. */
    fun reset() {
        Handler(Looper.getMainLooper()).post {
            web?.destroy()
            web = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWeb(): WebView = withContext(Dispatchers.Main) {
        web?.let { return@withContext it }
        val loaded = CompletableDeferred<Unit>()
        val w = WebView(app)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.addJavascriptInterface(Bridge(), "MDH")
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        Sessions.pairs(Platform.PINTEREST).forEach { (n, v) ->
            cm.setCookie("https://www.pinterest.com", "$n=$v; Domain=.pinterest.com; Path=/; Secure")
        }
        cm.flush()
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
        w.loadUrl("https://www.pinterest.com/")
        try {
            withTimeout(30_000) { loaded.await() }
        } catch (e: TimeoutCancellationException) {
            w.destroy()
            throw ResolveException("Pinterest tardó demasiado en cargar. Revisa tu conexión.")
        }
        web = w
        w
    }

    /** Devuelve (código HTTP, cuerpo). `path` es relativo a https://www.pinterest.com. */
    suspend fun fetch(path: String, sourceUrl: String): Pair<Int, String> = mutex.withLock {
        val w = ensureWeb()
        val id = counter.incrementAndGet()
        val d = CompletableDeferred<Pair<Int, String>>()
        pending[id] = d
        val js = "(async()=>{try{const m=document.cookie.match(/csrftoken=([^;]+)/);" +
            "const h={'Accept':'application/json, text/javascript, */*, q=0.01','X-Requested-With':'XMLHttpRequest'," +
            "'X-Pinterest-AppState':'active','X-Pinterest-Source-Url':${JSONObject.quote(sourceUrl)}};" +
            "if(m)h['X-CSRFToken']=m[1];" +
            "const r=await fetch(${JSONObject.quote(path)},{headers:h,credentials:'include'});" +
            "const t=await r.text();MDH.done($id,r.status,t);}catch(e){MDH.done($id,0,String(e));}})();"
        withContext(Dispatchers.Main) { w.evaluateJavascript(js, null) }
        try {
            withTimeout(45_000) { d.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw ResolveException("Pinterest tardó demasiado en responder.")
        }
    }
}
