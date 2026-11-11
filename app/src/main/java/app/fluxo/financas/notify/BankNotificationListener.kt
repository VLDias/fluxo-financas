package app.fluxo.financas.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.fluxo.financas.data.KnownApps
import app.fluxo.financas.data.Pending
import app.fluxo.financas.data.Prefs
import app.fluxo.financas.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (_: Exception) {
            // Nunca deixar uma notificação malformada derrubar o serviço.
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val prefs = Prefs(this)
        if (!prefs.autoImport) return
        val knownBank = KnownApps.isKnownBank(pkg)
        if (prefs.onlyBanks && !knownBank) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val parts = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val body = parts.joinToString(". ")
        if (title.isBlank() && body.isBlank()) return

        val appLabel = KnownApps.labelFor(pkg)
        val text = listOf(title, body).filter { it.isNotBlank() }.joinToString(" — ")
        val parsed = NotificationParser.parse(title, body)
        val repo = Repo.get(this)
        val hash = repo.hashFor(pkg, text, sbn.postTime)

        if (parsed == null) {
            if (knownBank && NotificationParser.looksLikeMoney(text)) {
                scope.launch {
                    repo.addPending(
                        Pending(
                            packageName = pkg,
                            appLabel = appLabel,
                            title = title,
                            text = body.ifBlank { title },
                            postedAt = sbn.postTime,
                            confidence = 0f,
                            sourceHash = hash
                        )
                    )
                }
            }
            return
        }

        scope.launch {
            val canAuto = parsed.directionKnown && parsed.confidence >= 0.7f && (knownBank || !prefs.onlyBanks)
            if (canAuto) {
                val inserted = repo.insertImported(
                    pkg = pkg,
                    appLabel = appLabel,
                    rawText = text,
                    amountCents = parsed.amountCents,
                    isExpense = parsed.isExpense,
                    merchant = parsed.merchant,
                    categoryName = parsed.category,
                    postedAt = sbn.postTime,
                    sourceHash = hash
                )
                if (inserted) {
                    Notifier.imported(this@BankNotificationListener, parsed.amountCents, parsed.isExpense, parsed.merchant)
                }
            } else {
                repo.addPending(
                    Pending(
                        packageName = pkg,
                        appLabel = appLabel,
                        title = title,
                        text = body.ifBlank { title },
                        postedAt = sbn.postTime,
                        parsedAmount = parsed.amountCents,
                        parsedExpense = if (parsed.directionKnown) parsed.isExpense else null,
                        merchant = parsed.merchant,
                        categoryName = parsed.category,
                        confidence = parsed.confidence,
                        sourceHash = hash
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
