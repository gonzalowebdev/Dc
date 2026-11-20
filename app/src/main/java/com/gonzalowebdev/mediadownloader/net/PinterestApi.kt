package com.gonzalowebdev.mediadownloader.net

import android.net.Uri
import android.util.Base64
import com.gonzalowebdev.mediadownloader.data.Prefs
import com.gonzalowebdev.mediadownloader.domain.ResolveException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/** OAuth 2.0 (authorization code) contra la API oficial v5 de Pinterest. */
class PinterestAuth(private val prefs: Prefs) {

    fun baseUrl(): String =
        if (prefs.pinterestSandbox) "https://api-sandbox.pinterest.com" else "https://api.pinterest.com"

    fun hasToken(): Boolean = !prefs.accessToken.isNullOrBlank()

    fun authorizeUri(): Uri? {
        val id = prefs.pinterestClientId?.takeIf { it.isNotBlank() } ?: return null
        val state = UUID.randomUUID().toString()
        prefs.oauthState = state
        return Uri.parse("https://www.pinterest.com/oauth/").buildUpon()
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("redirect_uri", prefs.pinterestRedirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build()
    }

    suspend fun exchangeCode(code: String) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", prefs.pinterestRedirectUri)
            .build()
        saveTokens(tokenRequest(form))
    }

    /** Devuelve un token vigente (renovándolo si está por vencer). Bloqueante: se usa desde OkHttp. */
    @Synchronized
    fun validToken(): String? {
        val token = prefs.accessToken ?: return null
        val exp = prefs.tokenExpiresAt
        if (exp > 0 && System.currentTimeMillis() > exp - 60_000 && !prefs.refreshToken.isNullOrBlank()) {
            return refreshBlocking() ?: token
        }
        return token
    }

    @Synchronized
    fun refreshBlocking(): String? {
        val refresh = prefs.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).build()
            saveTokens(tokenRequest(form))
            prefs.accessToken
        } catch (e: Exception) {
            null
        }
    }

    private fun tokenRequest(form: FormBody): JsonObject {
        val id = prefs.pinterestClientId.orEmpty()
        val secret = prefs.pinterestClientSecret.orEmpty()
        val basic = Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder()
            .url("${baseUrl()}/v5/oauth/token")
            .header("Authorization", "Basic $basic")
            .post(form)
            .build()
        Net.client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ResolveException("Pinterest rechazó la autorización (HTTP ${r.code}): ${body.take(200)}")
            return JsonParser.parseString(body).asJsonObject
        }
    }

    private fun saveTokens(json: JsonObject) {
        prefs.accessToken = json.get("access_token")?.asString
        json.get("refresh_token")?.takeIf { !it.isJsonNull }?.asString?.let { prefs.refreshToken = it }
        val expiresIn = json.get("expires_in")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        prefs.tokenExpiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
    }

    companion object {
        const val SCOPES = "boards:read,boards:read_secret,pins:read,pins:read_secret,user_accounts:read"
    }
}

interface PinterestService {
    @GET
    suspend fun get(@Url url: String): JsonObject
}

class PinterestApi(private val auth: PinterestAuth) {

    private val http = Net.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
            auth.validToken()?.let { b.header("Authorization", "Bearer $it") }
            chain.proceed(b.build())
        }
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (response.request.header("X-Retry") != null) return null
                val token = auth.refreshBlocking() ?: return null
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("X-Retry", "1")
                    .build()
            }
        })
        .build()

    private val service: PinterestService = Retrofit.Builder()
        .baseUrl("https://api.pinterest.com/")
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(PinterestService::class.java)

    suspend fun get(path: String): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return service.get(auth.baseUrl() + path)
            } catch (e: HttpException) {
                if (e.code() == 429 && attempt < 6) {
                    attempt++
                    delay(5_000L * attempt)
                    continue
                }
                throw ResolveException(describe(e))
            } catch (e: IOException) {
                throw ResolveException("Sin conexión con Pinterest: ${e.message}")
            }
        }
    }

    /** Recorre TODAS las páginas (page_size=250 + bookmark) hasta que la API no devuelve más. */
    suspend fun pagedItems(
        path: String,
        extraQuery: String = "",
        onProgress: (Int) -> Unit = {}
    ): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        var bookmark: String? = null
        do {
            val sep = if (path.contains("?")) "&" else "?"
            val bm = bookmark?.let { "&bookmark=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            val page = get("$path${sep}page_size=250$extraQuery$bm")
            page.getAsJsonArray("items")?.forEach { if (it.isJsonObject) out.add(it.asJsonObject) }
            onProgress(out.size)
            bookmark = page.get("bookmark")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
        } while (bookmark != null)
        return out
    }

    private fun describe(e: HttpException): String {
        val body = try { e.response()?.errorBody()?.string().orEmpty() } catch (ex: Exception) { "" }
        val apiMsg = try { JsonParser.parseString(body).asJsonObject.get("message")?.asString } catch (ex: Exception) { null }
        return when (e.code()) {
            401 -> "Pinterest rechazó el token (sesión vencida o inválida). Vuelve a conectar la cuenta en Ajustes."
            403 -> "Pinterest denegó el acceso (HTTP 403). ${apiMsg ?: "Revisa los permisos (scopes) de tu app o si tiene acceso Trial."}"
            404 -> "Pinterest no encontró el recurso (HTTP 404). ${apiMsg ?: ""}".trim()
            else -> "Error de Pinterest (HTTP ${e.code()}). ${apiMsg ?: body.take(160)}".trim()
        }
    }
}
