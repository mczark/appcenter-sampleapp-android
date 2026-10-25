package pl.mczark.invoicescanner.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import pl.mczark.invoicescanner.InvoiceScannerApp
import pl.mczark.invoicescanner.auth.AuthState
import pl.mczark.invoicescanner.data.PaymentStatus
import pl.mczark.invoicescanner.data.ScanResult
import pl.mczark.invoicescanner.gmail.GmailAuthException
import java.io.IOException

/**
 * Sprawdza skrzynkę w tle i powiadamia o nowych fakturach do opłacenia.
 * Token Gmaila pobierany jest bez interakcji – działa, jeśli użytkownik już wcześniej udzielił zgody.
 */
class DailyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as InvoiceScannerApp
        // Konto nie zostało jeszcze połączone w aplikacji.
        if (app.repository.accountEmail.value == null) return Result.success()

        return try {
            val result = scanWithSilentAuth(app) ?: run {
                InvoiceNotifier.notifyReconnectNeeded(applicationContext)
                return Result.success()
            }
            val toPay = result.newInvoices.filter { it.status == PaymentStatus.TO_PAY }
            InvoiceNotifier.notifyNewInvoices(applicationContext, toPay)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Brak połączenia z Gmailem – spróbuję później", e)
            Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "Codzienne sprawdzanie nie powiodło się", e)
            Result.success()
        }
    }

    /** null – potrzebna ponowna zgoda użytkownika (tego nie da się zrobić w tle). */
    private suspend fun scanWithSilentAuth(app: InvoiceScannerApp): ScanResult? {
        val token = (app.authorizer.authorize() as? AuthState.Authorized)?.accessToken ?: return null
        return try {
            app.scanner.scan(token)
        } catch (e: GmailAuthException) {
            app.authorizer.invalidate(token)
            val retry = (app.authorizer.authorize() as? AuthState.Authorized)?.accessToken ?: return null
            app.scanner.scan(retry)
        }
    }

    private companion object {
        const val TAG = "DailyCheckWorker"
    }
}
