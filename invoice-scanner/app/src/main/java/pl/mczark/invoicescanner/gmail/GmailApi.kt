package pl.mczark.invoicescanner.gmail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class GmailAuthException(message: String) : IOException(message)

/** Minimalny klient Gmail REST API (tylko odczyt). */
class GmailApi(private val accessToken: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun profile(): GmailProfile = get("$BASE/profile")

    suspend fun listMessageIds(query: String, maxResults: Int): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = "$BASE/messages".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("maxResults", minOf(100, maxResults - ids.size).toString())
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
                .toString()
            val page: MessageList = get(url)
            ids += page.messages.orEmpty().map { it.id }
            pageToken = page.nextPageToken
        } while (pageToken != null && ids.size < maxResults)
        return ids
    }

    suspend fun message(id: String): GmailMessage = get("$BASE/messages/$id?format=full")

    suspend fun attachment(messageId: String, attachmentId: String): ByteArray {
        val body: MessagePartBody = get("$BASE/messages/$messageId/attachments/$attachmentId")
        return decodeBase64Url(body.data.orEmpty())
    }

    private suspend inline fun <reified T> get(url: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.code == 401 -> throw GmailAuthException("Token Gmail wygasł lub został cofnięty")
                !response.isSuccessful -> throw IOException("Gmail API ${response.code}: ${body.take(300)}")
                else -> JSON.decodeFromString<T>(body)
            }
        }
    }

    companion object {
        const val SCOPE_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun decodeBase64Url(data: String): ByteArray =
            Base64.getUrlDecoder().decode(data.trimEnd('=').replace("\\s".toRegex(), ""))
    }
}

@Serializable
data class GmailProfile(val emailAddress: String)

@Serializable
data class MessageList(
    val messages: List<MessageRef>? = null,
    val nextPageToken: String? = null,
)

@Serializable
data class MessageRef(val id: String)

@Serializable
data class GmailMessage(
    val id: String,
    val internalDate: String? = null,
    val snippet: String? = null,
    val payload: MessagePart? = null,
) {
    fun header(name: String): String? =
        payload?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /** Wszystkie części wiadomości (spłaszczone drzewo MIME). */
    fun allParts(): List<MessagePart> {
        val result = mutableListOf<MessagePart>()
        fun walk(part: MessagePart) {
            result += part
            part.parts?.forEach(::walk)
        }
        payload?.let(::walk)
        return result
    }
}

@Serializable
data class MessagePart(
    val mimeType: String? = null,
    val filename: String? = null,
    val headers: List<Header>? = null,
    val body: MessagePartBody? = null,
    val parts: List<MessagePart>? = null,
)

@Serializable
data class Header(val name: String, val value: String)

@Serializable
data class MessagePartBody(
    val size: Int = 0,
    val data: String? = null,
    val attachmentId: String? = null,
)
