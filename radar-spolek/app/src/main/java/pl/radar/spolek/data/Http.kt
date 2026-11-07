package pl.radar.spolek.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/** Prosty słoik ciasteczek w pamięci – potrzebny do tokenu (crumb) Yahoo Finance. */
private class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            store.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        store.removeAll { it.expiresAt < now }
        return store.filter { it.matches(url) }
    }
}

object Http {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(MemoryCookieJar())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** GET z ponawianiem przy błędach sieci oraz HTTP 429/5xx. */
    suspend fun get(url: String, allowError: Boolean = false, retries: Int = 2): String = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (attempt in 0..retries) {
            if (attempt > 0) delay(800L * attempt)
            try {
                return@withContext fetchOnce(url, allowError)
            } catch (e: HttpException) {
                if (e.code != 429 && e.code < 500) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Błąd sieci")
    }

    private fun fetchOnce(url: String, allowError: Boolean): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful || allowError) return body
            throw HttpException(resp.code, "HTTP ${resp.code} dla ${url.substringBefore('?')}")
        }
    }
}
