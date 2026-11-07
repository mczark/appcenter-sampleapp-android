package pl.radar.spolek.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import pl.radar.spolek.MainActivity
import pl.radar.spolek.R
import pl.radar.spolek.data.Repository
import pl.radar.spolek.data.ResultStore
import pl.radar.spolek.data.SettingsStore
import pl.radar.spolek.domain.ScreenerEngine
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

class ScreeningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_MSG = "msg"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_WEEKLY = "weekly"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifications.scanning(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(2, n)
    }

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).load()
        val engine = ScreenerEngine(Repository(applicationContext, settings.fmpKey, settings.universe), settings.config)
        // Skan całego rynku może trwać kilka minut – prosimy o pracę pierwszoplanową (powiadomienie w trakcie).
        // Na nowszych Androidach start z tła bywa zablokowany – wtedy skan i tak idzie dalej.
        try { setForeground(getForegroundInfo()) } catch (_: Exception) { }
        return try {
            val result = engine.screen { msg, p -> setProgress(workDataOf(KEY_MSG to msg, KEY_PROGRESS to p)) }
            ResultStore(applicationContext).save(result)
            if (inputData.getBoolean(KEY_WEEKLY, false)) {
                Notifications.showResult(applicationContext, result.recommendations.map { it.symbol })
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2 && inputData.getBoolean(KEY_WEEKLY, false)) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }
}

object Scheduler {
    const val WEEKLY = "weekly_screen"
    const val NOW = "screen_now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<ScreeningWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Co tydzień, w sobotę ok. 9:00 czasu lokalnego (po zamknięciu piątkowej sesji w USA). */
    fun scheduleWeekly(context: Context, replace: Boolean = false) {
        val now = ZonedDateTime.now()
        var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).with(LocalTime.of(9, 0))
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val delay = Duration.between(now, next).toMinutes()
        val req = PeriodicWorkRequestBuilder<ScreeningWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setInputData(workDataOf(ScreeningWorker.KEY_WEEKLY to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WEEKLY,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    fun cancelWeekly(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WEEKLY)
}

object Notifications {
    private const val CHANNEL = "recommendations"
    private const val CHANNEL_SCAN = "scan"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rekomendacje tygodniowe", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SCAN, "Skanowanie w toku", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun scanning(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Radar Spółek")
            .setContentText("Skanowanie rynku USA…")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    fun showResult(context: Context, symbols: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (symbols.isEmpty()) "W tym tygodniu żadna spółka nie spełnia wszystkich kryteriów."
        else "Spółki spełniające kryteria (${symbols.size}): ${symbols.joinToString(", ")}"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Radar Spółek – nowe rekomendacje")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1, n)
        } catch (_: SecurityException) { }
    }
}
