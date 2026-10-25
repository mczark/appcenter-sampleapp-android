package pl.mczark.invoicescanner.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pl.mczark.invoicescanner.MainActivity
import pl.mczark.invoicescanner.R
import pl.mczark.invoicescanner.data.Invoice
import pl.mczark.invoicescanner.ui.formatAmount
import pl.mczark.invoicescanner.ui.formatDate

object InvoiceNotifier {

    private const val CHANNEL_ID = "new_invoices"
    private const val SUMMARY_ID = 1
    private const val RECONNECT_ID = 2

    /** Extra intencji otwierającej aplikację na konkretnej fakturze. */
    const val EXTRA_MESSAGE_ID = "pl.mczark.invoicescanner.MESSAGE_ID"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Nowe faktury", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Powiadomienia o nowych fakturach do opłacenia znalezionych w Gmailu"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyNewInvoices(context: Context, invoices: List<Invoice>) {
        if (invoices.isEmpty()) return
        val notification = if (invoices.size == 1) {
            val invoice = invoices.single()
            builder(context, openInvoiceIntent(context, invoice.messageId))
                .setContentTitle("Nowa faktura do zapłaty")
                .setContentText(describe(invoice))
                .setStyle(NotificationCompat.BigTextStyle().bigText(describe(invoice)))
                .build()
        } else {
            val style = NotificationCompat.InboxStyle()
            invoices.take(6).forEach { style.addLine(describe(it)) }
            if (invoices.size > 6) style.setSummaryText("i ${invoices.size - 6} więcej")
            builder(context, openInvoiceIntent(context, null))
                .setContentTitle("Nowe faktury do zapłaty: ${invoices.size}")
                .setContentText(invoices.joinToString(", ") { it.recipientName })
                .setStyle(style)
                .build()
        }
        post(context, SUMMARY_ID, notification)
    }

    fun notifyReconnectNeeded(context: Context) {
        val notification = builder(context, openInvoiceIntent(context, null))
            .setContentTitle("Nie udało się sprawdzić Gmaila")
            .setContentText("Otwórz aplikację i odśwież listę, aby ponownie połączyć konto.")
            .build()
        post(context, RECONNECT_ID, notification)
    }

    private fun describe(invoice: Invoice): String = buildString {
        append(invoice.recipientName)
        append(" – ")
        append(formatAmount(invoice.amountValue, invoice.currency))
        invoice.dueDateValue?.let { append(", termin ").append(formatDate(it)) }
    }

    private fun builder(context: Context, intent: PendingIntent) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    private fun openInvoiceIntent(context: Context, messageId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { messageId?.let { putExtra(EXTRA_MESSAGE_ID, it) } }
        return PendingIntent.getActivity(
            context,
            messageId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission") // sprawdzone poniżej
    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
