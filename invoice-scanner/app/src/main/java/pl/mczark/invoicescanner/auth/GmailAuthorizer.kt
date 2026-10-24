package pl.mczark.invoicescanner.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import pl.mczark.invoicescanner.gmail.GmailApi

sealed interface AuthState {
    data class Authorized(val accessToken: String) : AuthState
    /** Użytkownik musi wybrać konto / wyrazić zgodę – trzeba uruchomić [pendingIntent]. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthState
}

/**
 * Uzyskuje token OAuth z uprawnieniem `gmail.readonly` przez Google Identity Services.
 * Gdy zgoda została już udzielona, token wydawany jest bez interakcji z użytkownikiem.
 */
class GmailAuthorizer(context: Context) {

    private val appContext = context.applicationContext
    private val client = Identity.getAuthorizationClient(appContext)

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(GmailApi.SCOPE_READONLY)))
        .build()

    suspend fun authorize(): AuthState = client.authorize(request).await().toState()

    /** Usuwa z pamięci podręcznej token odrzucony przez Gmail, żeby następne [authorize] wydało nowy. */
    suspend fun invalidate(accessToken: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(appContext, accessToken) }
    }

    /** Wynik ekranu zgody uruchomionego z [AuthState.NeedsConsent]. */
    fun handleConsentResult(data: Intent?): AuthState =
        client.getAuthorizationResultFromIntent(data ?: error("Anulowano logowanie")).toState()

    private fun AuthorizationResult.toState(): AuthState {
        val intent = pendingIntent
        return when {
            hasResolution() && intent != null -> AuthState.NeedsConsent(intent)
            accessToken != null -> AuthState.Authorized(accessToken!!)
            else -> error("Nie udało się uzyskać dostępu do Gmaila")
        }
    }
}
