package app.fluxo.financas.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.fluxo.financas.R
import app.fluxo.financas.data.Money
import app.fluxo.financas.ui.MainActivity

object Notifier {
    const val CHANNEL_ID = "imports"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Confirmação de lançamentos criados a partir das notificações do banco."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun imported(context: Context, amountCents: Long, isExpense: Boolean, merchant: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val signal = if (isExpense) "−" else "+"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(if (isExpense) "Despesa registrada" else "Receita registrada")
            .setContentText("$signal ${Money.format(amountCents)} · $merchant")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 10_000).toInt(), notification)
        } catch (_: SecurityException) {
        }
    }
}
