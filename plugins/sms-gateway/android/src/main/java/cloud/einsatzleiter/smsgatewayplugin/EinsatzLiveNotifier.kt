package cloud.einsatzleiter.smsgatewayplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class EinsatzLiveNotifier(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "ec_einsatz_live"
        const val NOTIFICATION_ID = 7303
        const val EXTRA_EC_URL = "cloud.einsatzleiter.smsgatewayplugin.EXTRA_EC_URL"
        const val EXTRA_INCIDENT_ID = "cloud.einsatzleiter.smsgatewayplugin.EXTRA_INCIDENT_ID"
        private const val CONTENT_REQUEST_CODE = 7303
        private const val DELETE_REQUEST_CODE = 7304
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Laufender Einsatz",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Status und Fortschritt eines laufenden Einsatzes"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun post(state: EinsatzLiveState, baseUrl: String, staleText: String? = null) {
        val dismissed = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .getString(EinsatzLivePoller.PREF_DISMISSED_INCIDENT_ID, null)
        if (dismissed == state.id.toString()) return
        manager.notify(NOTIFICATION_ID, buildTier1(state, baseUrl, staleText).build())
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun buildTier1(
        state: EinsatzLiveState,
        baseUrl: String,
        staleText: String?
    ): NotificationCompat.Builder {
        val location = state.address.substringAfterLast(',').trim().ifEmpty { state.address }
        val title = listOf(state.alarmTypeCode, location).filter { it.isNotBlank() }.joinToString(" · ")
        val detail = staleText ?: buildString {
            append(state.address.ifBlank { "Laufender Einsatz" })
            if (state.incidentCount > 1) append(" · +${state.incidentCount - 1} weitere Einsätze")
        }
        val elapsed = (android.os.SystemClock.elapsedRealtime() - state.chronometerBase).coerceAtLeast(0L)
        val deepLink = baseUrl.trimEnd('/') + "/" + state.url.trimStart('/')
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_EC_URL, deepLink)
            }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, CONTENT_REQUEST_CODE, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val deleteIntent = PendingIntent.getBroadcast(
            context,
            DELETE_REQUEST_CODE,
            Intent(context, EinsatzLiveDismissReceiver::class.java)
                .putExtra(EXTRA_INCIDENT_ID, state.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ec_live)
            .setContentTitle(title.ifBlank { "Laufender Einsatz" })
            .setContentText(state.phaseLabel)
            .setSubText(detail)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - elapsed)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setProgress(3, state.phaseIndex.coerceIn(0, 3), false)
            .setDeleteIntent(deleteIntent)
        contentIntent?.let { builder.setContentIntent(it) }
        return promoteIfPossible(builder, state)
    }

    private fun promoteIfPossible(
        builder: NotificationCompat.Builder,
        state: EinsatzLiveState
    ): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT < 36) return builder
        return try {
            if (!manager.canPostPromotedNotifications()) return builder
            val style = NotificationCompat.ProgressStyle()
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100))
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(100))
                .setProgress(state.phaseIndex.coerceIn(0, 3) * 100)
                .setStyledByProgress(false)
                .addProgressPoint(NotificationCompat.ProgressStyle.Point(200))
            builder
                .setStyle(style)
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(state.alarmTypeCode.take(7))
        } catch (_: Throwable) {
            builder
        }
    }
}
