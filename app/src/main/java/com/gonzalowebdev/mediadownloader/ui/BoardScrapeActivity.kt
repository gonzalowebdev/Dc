package com.gonzalowebdev.mediadownloader.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.gonzalowebdev.mediadownloader.data.DiagLog
import com.gonzalowebdev.mediadownloader.domain.Platform
import com.gonzalowebdev.mediadownloader.domain.ResolveException
import com.gonzalowebdev.mediadownloader.net.BoardScrape
import com.gonzalowebdev.mediadownloader.net.ScrapeRequest
import com.gonzalowebdev.mediadownloader.net.ScrapeResult
import com.gonzalowebdev.mediadownloader.net.ScrapedPin
import com.gonzalowebdev.mediadownloader.net.Sessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

private const val COLLECT_JS = """(function(){
 var cut=Infinity;
 var hs=document.querySelectorAll('h1,h2,h3,[role="heading"]');
 for(var i=0;i<hs.length;i++){var t=(hs[i].textContent||'').trim();if(/^(m[aá]s ideas|more ideas)/i.test(t)){cut=hs[i].getBoundingClientRect().top+window.scrollY;break;}}
 var pins={};
 document.querySelectorAll('a[href*="/pin/"]').forEach(function(a){
  var m=(a.getAttribute('href')||'').match(/\/pin\/(\d+)/); if(!m) return;
  var y=a.getBoundingClientRect().top+window.scrollY; if(y>=cut) return;
  var img=a.querySelector('img'); if(!img) return;
  var src=img.currentSrc||img.src||''; if(src.indexOf('pinimg.com')<0) return;
  var tile=a.closest('[data-test-id="pin"],[data-grid-item]')||a.parentElement;
  var vid=!!(tile&&(tile.querySelector('video')||tile.querySelector('[data-test-id*="video"]')||/\b\d+:\d{2}\b/.test(tile.innerText||'')));
  pins[m[1]]={s:src,v:vid};
 });
 var secs=[];
 document.querySelectorAll('a[href]').forEach(function(a){var h=a.getAttribute('href')||'';if(/^\/[^\/]+\/[^\/?]+\/[^\/?]+\/?(\?.*)?$/.test(h)) secs.push(h.split('?')[0]);});
 var cm=((document.body&&document.body.innerText)||'').slice(0,1500).match(/(\d[\d.,]*)\s*(pines|pins)\b/i);
 return JSON.stringify({pins:pins,secs:secs,count:cm?cm[1]:null});
})()"""

private const val SCROLL_JS = "window.scrollBy(0, Math.max(400, window.innerHeight*0.7)); true"

class BoardScrapeActivity : ComponentActivity() {
    private val status = mutableStateOf("Abriendo el tablero…")
    private lateinit var web: WebView
    private var pageLoaded = CompletableDeferred<Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = BoardScrape.request
        val deferred = BoardScrape.result
        if (req == null || deferred == null) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        Sessions.pairs(Platform.PINTEREST).forEach { (n, v) ->
            cm.setCookie("https://www.pinterest.com", "$n=$v; Domain=.pinterest.com; Path=/; Secure")
        }
        cm.flush()

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!pageLoaded.isCompleted) pageLoaded.complete(Unit)
                }
            }
        }

        setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(status.value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            deferred.completeExceptionally(ResolveException("Lectura cancelada."))
                            finish()
                        }) { Text("CANCELAR") }
                    }
                    AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        lifecycleScope.launch {
            try {
                deferred.complete(scrape(req))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResolveException) {
                deferred.completeExceptionally(e)
            } catch (e: Throwable) {
                DiagLog.add("tablero-pagina", e.stackTraceToString().take(600))
                deferred.completeExceptionally(ResolveException("Error leyendo el tablero: ${e.message ?: e.javaClass.simpleName}"))
            }
            finish()
        }
    }

    override fun onDestroy() {
        BoardScrape.result?.completeExceptionally(ResolveException("Lectura interrumpida."))
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    private suspend fun navigate(url: String) {
        pageLoaded = CompletableDeferred()
        web.loadUrl(url)
        try { withTimeout(40_000) { pageLoaded.await() } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw ResolveException("Pinterest tardó demasiado en cargar la página.") }
        delay(2500)
    }

    private suspend fun eval(js: String): String? = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(js) { v -> if (cont.isActive) cont.resume(v) }
    }

    private fun unquote(v: String?): String? =
        if (v == null || v == "null") null else try { JSONTokener(v).nextValue() as? String } catch (e: Exception) { null }

    /** Recorre la página desplazándola y acumulando los Pines que van apareciendo. Devuelve el total declarado. */
    private suspend fun collect(label: String, acc: LinkedHashMap<String, ScrapedPin>, secs: MutableSet<String>?): Int? {
        var stall = 0
        var declared: Int? = null
        var iter = 0
        while (stall < 10 && iter < 4000) {
            iter++
            var added = 0
            val raw = unquote(eval(COLLECT_JS))
            val o = try { raw?.let { JSONObject(it) } } catch (e: Exception) { null }
            if (o != null) {
                val pins = o.getJSONObject("pins")
                pins.keys().forEach { id ->
                    if (id !in acc) {
                        val p = pins.getJSONObject(id)
                        acc[id] = ScrapedPin(id, p.getString("s"), p.optBoolean("v"))
                        added++
                    }
                }
                if (secs != null) o.optJSONArray("secs")?.let { a -> for (i in 0 until a.length()) secs.add(a.getString(i)) }
                if (declared == null) declared = o.optString("count", "").replace(Regex("[.,]"), "").toIntOrNull()
            }
            val d = declared
            status.value = "$label ${acc.size}" + (if (d != null) " de $d" else "")
            BoardScrape.progress?.invoke(status.value)
            if (d != null && acc.size >= d) break
            stall = if (added == 0) stall + 1 else 0
            eval(SCROLL_JS)
            delay(900)
        }
        return declared
    }

    private suspend fun scrape(req: ScrapeRequest): ScrapeResult {
        val acc = LinkedHashMap<String, ScrapedPin>()
        val notes = mutableListOf<String>()
        navigate(req.boardUrl)
        val secs = LinkedHashSet<String>()
        val declared = collect("Leyendo Pines…", acc, secs)
        if (req.includeSections) {
            val prefix = "/${req.user}/${req.slug}/".lowercase()
            val skip = setOf("more_ideas", "_tools", "_shop", "_created", "_saved")
            val sections = secs.filter { p ->
                val parts = p.trim('/').split('/')
                p.lowercase().startsWith(prefix) && parts.size == 3 && parts[2] !in skip
            }.distinct()
            sections.forEachIndexed { i, path ->
                status.value = "Sección ${i + 1} de ${sections.size}…"
                navigate("https://www.pinterest.com$path")
                collect("Sección ${i + 1}/${sections.size}:", acc, null)
            }
            if (sections.isNotEmpty()) notes += "Se leyeron ${sections.size} secciones."
        }
        if (declared != null && acc.size < declared) notes += "Pinterest indica $declared Pines y se leyeron ${acc.size}."
        DiagLog.add("tablero-pagina", "${req.boardUrl}: ${acc.size} pines (declarados: $declared)")
        return ScrapeResult(acc.values.toList(), declared, notes)
    }
}
