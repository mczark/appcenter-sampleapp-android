package pl.mczark.invoicescanner.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Codzienne sprawdzanie skrzynki w tle (WorkManager). Domyślnie włączone. */
object DailyCheck {

    private const val WORK_NAME = "daily-invoice-check"
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "daily_check_enabled"

    /** Godzina, o której (mniej więcej) system uruchamia sprawdzanie. */
    val CHECK_TIME: LocalTime = LocalTime.of(8, 0)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Planuje sprawdzanie, jeśli jest włączone. Istniejący harmonogram zostaje bez zmian. */
    fun scheduleIfEnabled(context: Context) {
        if (isEnabled(context)) schedule(context)
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyCheckWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNextCheck().toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun delayUntilNextCheck(): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(CHECK_TIME)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
