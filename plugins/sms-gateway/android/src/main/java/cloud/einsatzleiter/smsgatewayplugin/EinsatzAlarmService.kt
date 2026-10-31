package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import kotlin.math.ceil

/** Zeitlich begrenzte Sirene auf dem Alarm-Audiostream. */
class EinsatzAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val audioManager: AudioManager
        get() = getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusRequested = false
    private var originalVolume: Int? = null
    private var currentAlarm: Intent? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            SmsGatewayService.log("Alarm: Audiofokus verloren")
            player?.pause()
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            player?.start()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarm(acknowledged = true)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        releaseAlarm()
        currentAlarm = Intent(intent)
        val previewSeconds = intent.getIntExtra(EXTRA_PREVIEW_SECONDS, 0).coerceIn(0, 60)
        try {
            ServiceCompat.startForeground(
                this, EinsatzAlarmNotification.NOTIFICATION_ID,
                EinsatzAlarmNotification.build(this, intent),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: SecurityException) {
            foregroundFailed(intent, e)
            return START_NOT_STICKY
        } catch (e: IllegalStateException) {
            foregroundFailed(intent, e)
            return START_NOT_STICKY
        }
        SmsGatewayService.log("Alarm: gestartet")
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:NativeAlarm")
                .apply { acquire(70_000L) }
            requestFocus()
            val pref = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
                .getString("el_alarm_volume_percent", "80")?.toIntOrNull() ?: 80
            val percent = intent.getIntExtra(EXTRA_VOLUME_PERCENT, pref).coerceIn(0, 100)
            SmsGatewayService.log("Alarm: Lautstaerke $percent%")
            if (percent > 0) {
                try {
                    val volume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    originalVolume = volume
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM, ceil(maximum * percent / 100.0).toInt().coerceAtLeast(1), 0,
                    )
                } catch (_: SecurityException) {
                    SmsGatewayService.log("Alarm: Systemlautstaerke darf nicht geaendert werden")
                }
            }
            val tone = AlarmTone.normalize(intent.getStringExtra(EXTRA_TONE)
                ?: getSharedPreferences("CapacitorStorage", MODE_PRIVATE).getString(AlarmTone.PREF_KEY, null))
            SmsGatewayService.log("Alarm: Ton $tone")
            try {
                startTone(tone)
            } catch (e: Exception) {
                if (tone == AlarmTone.DEFAULT) throw e
                SmsGatewayService.log("Alarm: Ton $tone konnte nicht geladen werden -> Standard-Alarm (${e.javaClass.simpleName})")
                player?.release()
                player = null
                SmsGatewayService.log("Alarm: Ton ${AlarmTone.DEFAULT}")
                startTone(AlarmTone.DEFAULT)
            }
            startVibration()
            SmsGatewayService.log("Alarm: Ton und Vibration gestartet")
        } catch (e: Exception) {
            SmsGatewayService.log("Alarm: Wiedergabe fehlgeschlagen: ${e.javaClass.simpleName}")
            stopAlarm(acknowledged = false)
            return START_NOT_STICKY
        }
        handler.postDelayed({
            SmsGatewayService.log(if (previewSeconds > 0) "Alarm: Vorschau beendet" else "Alarm: automatisch beendet nach 60 s")
            stopAlarm(acknowledged = false)
        }, if (previewSeconds > 0) previewSeconds * 1000L else 60_000L)
        return START_NOT_STICKY
    }

    private fun startTone(tone: String) {
        // Vor prepare() speichern, damit auch bei einem Fehler release() erreicht wird.
        val media = MediaPlayer()
        player = media
        media.apply {
            setAudioAttributes(audioAttributes)
            resources.openRawResourceFd(rawRes(tone)).use { file ->
                setDataSource(file.fileDescriptor, file.startOffset, file.length)
            }
            isLooping = true
            setOnErrorListener { _, what, extra ->
                SmsGatewayService.log("Alarm: Wiedergabefehler $what/$extra")
                stopAlarm(acknowledged = false)
                true
            }
            prepare()
            start()
        }
    }

    private fun requestFocus() {
        focusRequested = true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        SmsGatewayService.log("Alarm: Audiofokus angefordert ($result)")
    }

    private fun startVibration() {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }
        vibrator = device
        val pattern = longArrayOf(0, 700, 300, 700, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.vibrate(VibrationEffect.createWaveform(pattern, 0), VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM).build())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            device.vibrate(VibrationEffect.createWaveform(pattern, 0), audioAttributes)
        } else {
            @Suppress("DEPRECATION")
            device.vibrate(pattern, 0, audioAttributes)
        }
    }

    private fun foregroundFailed(alarm: Intent, error: Exception) {
        SmsGatewayService.log("Alarm: FGS verweigert -> Fallback (${error.javaClass.simpleName})")
        releaseAlarm()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        postFallback(this, alarm)
        currentAlarm = null
        stopSelf()
    }

    private fun stopAlarm(acknowledged: Boolean) {
        val alarm = currentAlarm
        currentAlarm = null
        releaseAlarm()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val manager = getSystemService(NotificationManager::class.java)
        if (acknowledged) {
            SmsGatewayService.log("Alarm: quittiert")
            manager.cancel(EinsatzAlarmNotification.NOTIFICATION_ID)
        } else if (alarm != null && alarm.getIntExtra(EXTRA_PREVIEW_SECONDS, 0) == 0) {
            try {
                manager.notify(EinsatzAlarmNotification.NOTIFICATION_ID, EinsatzAlarmNotification.build(this, alarm, finished = true))
            } catch (_: SecurityException) {
                SmsGatewayService.log("Alarm: Abschlussmeldung nicht erlaubt")
            }
        }
        AlarmActivity.finishAlarm()
        stopSelf()
    }

    private fun releaseAlarm() {
        handler.removeCallbacksAndMessages(null)
        player?.let { media ->
            try { media.stop() } catch (_: IllegalStateException) { }
            media.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        originalVolume?.let { volume ->
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
                SmsGatewayService.log("Alarm: Systemlautstaerke wiederhergestellt")
            } catch (_: SecurityException) {
                SmsGatewayService.log("Alarm: Systemlautstaerke konnte nicht wiederhergestellt werden")
            }
        }
        originalVolume = null
        if (focusRequested) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        }
        focusRequest = null
        focusRequested = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseAlarm()
        AlarmActivity.finishAlarm()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "cloud.einsatzleiter.smsgatewayplugin.ALARM_START"
        const val ACTION_STOP = "cloud.einsatzleiter.smsgatewayplugin.ALARM_STOP"
        const val EXTRA_INCIDENT_ID = "incidentId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_URL = "url"
        const val EXTRA_IS_TEST = "isTest"
        const val EXTRA_PREVIEW_SECONDS = "previewSeconds"
        const val EXTRA_VOLUME_PERCENT = "percent"
        const val EXTRA_TONE = "tone"
        const val EXTRA_STARTED_AT = "startedAt"

        fun start(
            context: Context,
            incidentId: Long = 0,
            title: String,
            body: String,
            url: String,
            isTest: Boolean = false,
            previewSeconds: Int = 0,
            percent: Int? = null,
            tone: String? = null,
        ): Boolean {
            val intent = Intent(context, EinsatzAlarmService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_INCIDENT_ID, incidentId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_URL, EinsatzAlarmNotification.absoluteUrl(context, url))
                putExtra(EXTRA_IS_TEST, isTest)
                putExtra(EXTRA_STARTED_AT, SystemClock.elapsedRealtime())
                putExtra(EXTRA_PREVIEW_SECONDS, previewSeconds.coerceIn(0, 60))
                percent?.let { putExtra(EXTRA_VOLUME_PERCENT, it.coerceIn(0, 100)) }
                tone?.let { putExtra(EXTRA_TONE, it) }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                return true
            } catch (e: SecurityException) {
                SmsGatewayService.log("Alarm: FGS verweigert -> Fallback (${e.javaClass.simpleName})")
            } catch (e: IllegalStateException) {
                // Enthaelt ForegroundServiceStartNotAllowedException ab Android 12.
                SmsGatewayService.log("Alarm: FGS verweigert -> Fallback (${e.javaClass.simpleName})")
            }
            postFallback(context, intent)
            return false
        }

        fun stop(context: Context) {
            // stopService funktioniert auch im Hintergrund; onDestroy raeumt den Ton auf.
            context.stopService(Intent(context, EinsatzAlarmService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(EinsatzAlarmNotification.NOTIFICATION_ID)
            AlarmActivity.finishAlarm()
            SmsGatewayService.log("Alarm: quittiert")
        }

        private fun postFallback(context: Context, alarm: Intent) {
            if (alarm.getIntExtra(EXTRA_PREVIEW_SECONDS, 0) > 0) return
            try {
                context.getSystemService(NotificationManager::class.java).notify(
                    EinsatzAlarmNotification.NOTIFICATION_ID,
                    EinsatzAlarmNotification.build(context, alarm, fallback = true),
                )
            } catch (_: SecurityException) {
                SmsGatewayService.log("Alarm: Fallback-Benachrichtigung nicht erlaubt")
            }
        }
    }
}
