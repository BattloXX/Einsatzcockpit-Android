package cloud.einsatzleiter.smsgatewayplugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Parcelable
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/** API-Verzweigungen einschließlich der weiterhin nötigen Legacy-Pfade ab API 24. */
internal object Compat {
    fun getSmsManager(context: Context): SmsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
    fun <T : Parcelable> getParcelableExtraCompat(intent: Intent, key: String, type: Class<T>): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, type)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }

    fun showAlarmWhenLocked(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    fun requestAlarmFocus(
        audioManager: AudioManager,
        audioAttributes: AudioAttributes,
        focusListener: AudioManager.OnAudioFocusChangeListener,
        handler: Handler,
        onRequest: (AudioFocusRequest) -> Unit,
    ): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(focusListener, handler)
                .build()
            onRequest(request)
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    fun vibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        }
    }

    fun vibrateAlarm(device: Vibrator, audioAttributes: AudioAttributes) {
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

    fun abandonAlarmFocus(
        audioManager: AudioManager,
        focusRequest: AudioFocusRequest?,
        focusListener: AudioManager.OnAudioFocusChangeListener,
    ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
    }

    fun fallbackAlarmSound(builder: NotificationCompat.Builder, context: Context, fallback: Boolean) {
                if (fallback && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    builder.setSound(Uri.parse("android.resource://${context.packageName}/raw/einsatz_alarm"), AudioManager.STREAM_ALARM)
                    builder.setVibrate(longArrayOf(0, 700, 300, 700))
                }
    }
}
