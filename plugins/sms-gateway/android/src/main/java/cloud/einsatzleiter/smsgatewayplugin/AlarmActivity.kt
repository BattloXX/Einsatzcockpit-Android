package cloud.einsatzleiter.smsgatewayplugin

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference

/** Alarmanzeige ohne WebView, auch ueber dem Sperrbildschirm. */
class AlarmActivity : AppCompatActivity() {
    private var opening = false
    private var openFromNotification = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        visible = WeakReference(this)
        Compat.showAlarmWhenLocked(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_einsatz_alarm)
        SafeWindowInsets.install(window)
        window.decorView.setBackgroundColor(Color.rgb(183, 28, 28))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        findViewById<Button>(R.id.alarm_acknowledge).setOnClickListener { acknowledge() }
        findViewById<Button>(R.id.alarm_open).setOnClickListener { openIncident() }
        showAlarm()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        opening = false
        showAlarm()
    }

    private fun showAlarm() {
        findViewById<TextView>(R.id.alarm_header).text =
            if (intent.getBooleanExtra(EinsatzAlarmService.EXTRA_IS_TEST, false)) "TESTALARM" else "EINSATZALARM"
        findViewById<TextView>(R.id.alarm_title).text = intent.getStringExtra(EinsatzAlarmService.EXTRA_TITLE).orEmpty()
        findViewById<TextView>(R.id.alarm_body).text = intent.getStringExtra(EinsatzAlarmService.EXTRA_BODY).orEmpty()
        findViewById<Chronometer>(R.id.alarm_elapsed).apply {
            base = intent.getLongExtra(EinsatzAlarmService.EXTRA_STARTED_AT, SystemClock.elapsedRealtime())
            start()
        }
        openFromNotification = intent.action == ACTION_OPEN
        if (openFromNotification && hasWindowFocus()) {
            openFromNotification = false
            openIncident()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Die Entsperr-Anfrage braucht ein bereits sichtbares Fenster.
        if (hasFocus && openFromNotification) {
            openFromNotification = false
            openIncident()
        }
    }

    private fun acknowledge() {
        EinsatzAlarmService.stop(this)
        finish()
    }

    private fun openIncident() {
        if (opening) return
        opening = true
        EinsatzAlarmService.stop(this)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launchApp()
                override fun onDismissError() = launchApp()
                override fun onDismissCancelled() { finish() }
            })
        } else {
            launchApp()
        }
    }

    private fun launchApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            launch.putExtra(EinsatzLiveNotifier.EXTRA_EC_URL, EinsatzAlarmNotification.absoluteUrl(
                this, intent.getStringExtra(EinsatzAlarmService.EXTRA_URL).orEmpty(),
            ))
            startActivity(launch)
        }
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            acknowledge()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        findViewById<Chronometer>(R.id.alarm_elapsed).stop()
        if (visible?.get() === this) visible = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "cloud.einsatzleiter.smsgatewayplugin.ALARM_OPEN"
        private var visible: WeakReference<AlarmActivity>? = null

        fun finishAlarm() {
            val alarmActivity = visible?.get() ?: return
            Handler(Looper.getMainLooper()).post {
                if (!alarmActivity.opening) alarmActivity.finish()
            }
        }
    }
}
