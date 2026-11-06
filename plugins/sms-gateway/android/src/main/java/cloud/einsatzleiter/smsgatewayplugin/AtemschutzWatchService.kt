package cloud.einsatzleiter.smsgatewayplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

internal data class AsuewWatchSnapshot(
    val connection: String = "verbindet",
    val battery: String = "unbekannt",
    val alarmCount: Int = 0,
)

/** Lokale Fristen laufen unabhängig von den synchronen Netzwerkaufrufen weiter. */
class AtemschutzWatchService : Service() {
    companion object {
        const val ACTION_START = "cloud.einsatzleiter.smsgatewayplugin.ASUEW_START"
        const val ACTION_STOP = "cloud.einsatzleiter.smsgatewayplugin.ASUEW_STOP"
        const val ACTION_REFRESH = "cloud.einsatzleiter.smsgatewayplugin.ASUEW_REFRESH"
        const val ACTION_ACK = "cloud.einsatzleiter.smsgatewayplugin.ASUEW_ACK"
        const val EXTRA_INCIDENT_ID = "incidentId"
        private const val CHANNEL_ID = "ec_asuew"
        private const val BATTERY_CHANNEL_ID = "ec_asuew_akku"
        private const val NOTIFICATION_ID = 7305
        private const val BATTERY_NOTIFICATION_ID = 7309
        @Volatile internal var snapshot: AsuewWatchSnapshot = AsuewWatchSnapshot()
            private set
    }

    private lateinit var localThread: HandlerThread
    private lateinit var networkThread: HandlerThread
    private lateinit var local: Handler
    private lateinit var network: Handler
    private lateinit var api: AsuewApi
    @Volatile private var destroyed: Boolean = false
    @Volatile private var stopping: Boolean = false
    private var incidentId: Long = 0L
    private var state: AsuewState? = null
    private var tracker: AsuewAlarmTracker = AsuewAlarmTracker()
    private var activeAlarms: List<AsuewAlarm> = emptyList()
    private var offsetMs: Long = 0L
    private var clockWallMs: Long = 0L
    private var clockElapsedMs: Long = 0L
    private var startedElapsedMs: Long = 0L
    private var lastOkElapsedMs: Long? = null
    private var inactiveSinceMs: Long? = null
    private var noTroopsSinceMs: Long? = null
    private var nextFetchMs: Long = 0L
    private var nextHeartbeatMs: Long = 0L
    private var networkBusy: Boolean = false
    private var fetchSessionFailures: Int = 0
    private var heartbeatSessionFailures: Int = 0
    private var connection: String = "verbindet"
    private var battery: String = "unbekannt"
    private var batteryPercent: Int? = null
    private var charging: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var nextWakeRenewMs: Long = 0L

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || stopping || incidentId <= 0L) return
            safely("Neubewertung") { evaluate() }
            if (!destroyed && !stopping) local.postDelayed(this, 5_000L)
        }
    }

    private val networkTick: Runnable = Runnable {
        if (!destroyed && !stopping && incidentId > 0L) safely("Netzplanung") { scheduleNetwork() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NativeLog.init(applicationContext)
        localThread = HandlerThread("asuew-lokal").apply { start() }
        networkThread = HandlerThread("asuew-netz").apply { start() }
        local = Handler(localThread.looper)
        network = Handler(networkThread.looper)
        api = AsuewApi(this)
        safely("Kanäle") { createChannels() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Auch STOP/ACK können einen zuvor beendeten FGS neu erzeugen.
        try {
            startForegroundCompat(buildNotification(AsuewStore.incidentId(this) ?: 0L, emptyList()))
        } catch (error: Exception) {
            NativeLog.append("ASÜW Foreground: ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        val action: String? = intent?.action
        val requestedId: Long = intent?.getLongExtra(EXTRA_INCIDENT_ID, 0L) ?: 0L
        local.post {
            if (destroyed || stopping) return@post
            safely("Aktion") {
                if (action == ACTION_STOP) {
                    finishWatch()
                    return@safely
                }
                val id: Long = if (requestedId > 0L && action == ACTION_START) requestedId
                    else AsuewStore.incidentId(this) ?: 0L
                if (id <= 0L || (action != ACTION_START && !AsuewStore.isActive(this))) {
                    finishWatch()
                    return@safely
                }
                if (action == ACTION_START && (!AsuewStore.isActive(this) || AsuewStore.incidentId(this) != id)) {
                    AsuewStore.activate(this, id)
                }
                if (!AsuewStore.isActive(this)) {
                    finishWatch()
                    return@safely
                }
                if (incidentId != id) initializeWatch(id)
                if (action == ACTION_ACK) {
                    // Vor dem Quittieren auch gerade neu fällig gewordene Fristen erfassen.
                    evaluate(fire = false)
                    for (alarm: AsuewAlarm in activeAlarms) tracker.ack(alarm.key)
                    AsuewStore.saveAcked(this, tracker.serializeAcked())
                    EinsatzAlarmService.stop(this)
                }
                if (action == ACTION_REFRESH || action == ACTION_START) {
                    nextFetchMs = 0L
                    nextHeartbeatMs = 0L
                }
                local.removeCallbacks(tick)
                local.post(tick)
                local.removeCallbacks(networkTick)
                local.post(networkTick)
            }
        }
        return START_STICKY
    }

    private fun initializeWatch(id: Long) {
        incidentId = id
        state = null
        tracker = AsuewAlarmTracker().apply { restoreAcked(AsuewStore.acked(this@AtemschutzWatchService)) }
        activeAlarms = emptyList()
        startedElapsedMs = SystemClock.elapsedRealtime()
        clockElapsedMs = startedElapsedMs
        clockWallMs = System.currentTimeMillis()
        offsetMs = 0L
        val savedOkMs: Long = AsuewStore.lastOkMs(this)
        lastOkElapsedMs = if (savedOkMs > 0L) startedElapsedMs - (clockWallMs - savedOkMs).coerceAtLeast(0L) else null
        inactiveSinceMs = null
        noTroopsSinceMs = startedElapsedMs
        fetchSessionFailures = 0
        heartbeatSessionFailures = 0
        connection = "verbindet"
        battery = "unbekannt"
        nextFetchMs = 0L
        nextHeartbeatMs = 0L
    }

    private fun scheduleNetwork() {
        val now: Long = SystemClock.elapsedRealtime()
        if (networkBusy) return
        val fetch: Boolean = now >= nextFetchMs
        val heartbeat: Boolean = now >= nextHeartbeatMs
        if (!fetch && !heartbeat) {
            local.removeCallbacks(networkTick)
            local.postDelayed(networkTick, minOf(nextFetchMs, nextHeartbeatMs) - now)
            return
        }
        val id: Long = incidentId
        val percent: Int? = batteryPercent
        val isCharging: Boolean = charging
        networkBusy = true
        if (fetch) nextFetchMs = now + 12_000L
        if (heartbeat) nextHeartbeatMs = now + 15_000L
        network.post {
            var fetched: AsuewFetchResult? = null
            var beat: AsuewHeartbeatResult? = null
            var receivedWall: Long = 0L
            var receivedElapsed: Long = 0L
            try {
                if (fetch) {
                    fetched = api.fetchState(id)
                    receivedWall = System.currentTimeMillis()
                    receivedElapsed = SystemClock.elapsedRealtime()
                }
                if (heartbeat && !destroyed && !stopping) beat = api.postHeartbeat(percent, isCharging, null)
            } catch (error: Exception) {
                NativeLog.append("ASÜW Netzwerk: ${error.javaClass.simpleName}")
            } finally {
                val fetchResult: AsuewFetchResult? = fetched
                val heartbeatResult: AsuewHeartbeatResult? = beat
                val wall: Long = receivedWall
                val elapsed: Long = receivedElapsed
                local.post {
                    networkBusy = false
                    if (!destroyed && !stopping && incidentId == id) {
                        safely("Netzantwort") {
                            applyResults(fetchResult, heartbeatResult, wall, elapsed)
                            evaluate()
                        }
                    }
                    if (!destroyed && !stopping && incidentId > 0L) {
                        val delay: Long = (minOf(nextFetchMs, nextHeartbeatMs) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                        local.removeCallbacks(networkTick)
                        local.postDelayed(networkTick, delay)
                    }
                }
            }
        }
    }

    private fun applyResults(fetch: AsuewFetchResult?, heartbeat: AsuewHeartbeatResult?, wall: Long, elapsed: Long) {
        val now: Long = SystemClock.elapsedRealtime()
        if (fetch != null) {
            // Nur eine ununterbrochene Folge von 403/404 beendet den Einsatz lokal.
            inactiveSinceMs = if (AsuewTexts.isInactiveResponse(fetch)) inactiveSinceMs ?: now else null
            when (fetch) {
                is AsuewFetchResult.Success -> {
                    if (fetch.state.incidentId == incidentId) {
                        state = fetch.state
                        offsetMs = AsuewLogic.clockOffsetMs(fetch.state.serverTimeMs, wall)
                        clockWallMs = wall
                        clockElapsedMs = elapsed
                        saveSuccess(wall, elapsed)
                        fetchSessionFailures = 0
                        if (AsuewTexts.hasActiveTroops(state)) noTroopsSinceMs = null
                        else if (noTroopsSinceMs == null) noTroopsSinceMs = now
                    } else NativeLog.append("ASÜW: Antwort für anderen Einsatz verworfen")
                }
                is AsuewFetchResult.NoSession -> fetchSessionFailures = (fetchSessionFailures + 1).coerceAtMost(3)
                is AsuewFetchResult.Failure -> NativeLog.append("ASÜW Abruf: ${fetch.reason}")
            }
        }
        when (heartbeat) {
            is AsuewHeartbeatResult.Success -> {
                saveSuccess(System.currentTimeMillis(), now)
                heartbeatSessionFailures = 0
            }
            AsuewHeartbeatResult.SessionEnded -> heartbeatSessionFailures = (heartbeatSessionFailures + 1).coerceAtMost(3)
            is AsuewHeartbeatResult.Failure -> NativeLog.append("ASÜW Heartbeat: ${heartbeat.reason}")
            null -> Unit
        }
    }

    private fun saveSuccess(wall: Long, elapsed: Long) {
        lastOkElapsedMs = elapsed
        AsuewStore.saveLastOk(this, wall)
    }

    private fun evaluate(fire: Boolean = true) {
        if (!AsuewStore.isActive(this)) {
            finishWatch()
            return
        }
        val now: Long = SystemClock.elapsedRealtime()
        readBattery()
        // Ohne ersten Erfolg wird ab Dienststart dieselbe Offline-Eskalation verwendet.
        val nextConnection: String = AsuewLogic.connectionStatus(lastOkElapsedMs ?: startedElapsedMs, now)
        if (AsuewTexts.newConnectionEvent(connection, nextConnection) == "gestoert") safely("Vibration") { vibrateBriefly() }
        connection = nextConnection
        val nextBattery: String = AsuewLogic.batteryStatus(batteryPercent, charging)
        if (nextBattery != battery && nextBattery in listOf("warnung", "kritisch")) safely("Akkuwarnung") { postBatteryWarning() }
        battery = nextBattery
        val hasTroops: Boolean = AsuewTexts.hasActiveTroops(state)
        if (hasTroops) noTroopsSinceMs = null
        if (AsuewTexts.shouldStop(now, inactiveSinceMs, noTroopsSinceMs)) {
            finishWatch()
            return
        }
        safely("WakeLock") { updateWakeLock(hasTroops, now) }
        val serverNow: Long = AsuewLogic.serverNow(clockWallMs + (now - clockElapsedMs), offsetMs)
        val alarms: MutableList<AsuewAlarm> = (state?.let { AsuewLogic.activeAlarms(it, serverNow) } ?: emptyList()).toMutableList()
        if (connection == "verloren") alarms.add(AsuewAlarm(0L, "", "connection_lost", "verbindung:verloren"))
        if (battery == "kritisch") alarms.add(AsuewAlarm(0L, "", "battery_critical", "akku:kritisch"))
        if (fetchSessionFailures >= 3 || heartbeatSessionFailures >= 3) alarms.add(AsuewAlarm(0L, "", "session_ended", "sitzung:beendet"))
        activeAlarms = alarms.toList()
        // Vor dem ersten Zustand dürfen gespeicherte Trupp-Quittierungen nicht verloren gehen.
        val activeKeys: Set<String> = AsuewTexts.retainedAlarmKeys(alarms, tracker.acked, state != null)
        tracker.prune(activeKeys)
        if (fire) {
            for (alarm: AsuewAlarm in tracker.due(alarms, now)) {
                safely("Alarm") {
                    val body: String = AsuewTexts.alarmBody(alarm.troopName, alarm.kind)
                    EinsatzAlarmService.start(this, incidentId, AsuewTexts.alarmTitle(alarm.kind), body,
                        url = "/einsatz/$incidentId/atemschutz", tone = "sirene")
                    tracker.markFired(alarm.key, now)
                    // Geräte-/Sitzungsereignisse einmalig, bis die Ursache verschwunden ist.
                    if (alarm.troopId == 0L) tracker.ack(alarm.key)
                }
            }
        }
        AsuewStore.saveAcked(this, tracker.serializeAcked())
        snapshot = AsuewWatchSnapshot(connection, battery, alarms.size)
        val lines: MutableList<String> = (state?.let { AsuewLogic.notificationLines(it, serverNow) } ?: emptyList()).toMutableList()
        if (fetchSessionFailures > 0 || heartbeatSessionFailures > 0) lines.add("Überwachungssitzung beendet - App öffnen")
        lines.add(AsuewTexts.statusLine(connection, batteryPercent))
        safely("Benachrichtigung") { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(incidentId, lines)) }
    }

    private fun readBattery() {
        safely("Akku lesen") {
            val sticky: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level: Int = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val managerPercent: Int = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100)
                else managerPercent.takeIf { it in 0..100 }
            val status: Int = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
                (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    }

    private fun updateWakeLock(hasTroops: Boolean, now: Long) {
        if (!AsuewTexts.shouldHoldWakeLock(hasTroops, lastOkElapsedMs, now)) {
            releaseWakeLock()
            return
        }
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "einsatzcockpit:asuew").apply { setReferenceCounted(false) }
        if (now >= nextWakeRenewMs || wakeLock?.isHeld != true) {
            wakeLock?.acquire(10 * 60_000L)
            nextWakeRenewMs = now + 60_000L
        }
    }

    private fun releaseWakeLock() {
        safely("WakeLock freigeben") { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        nextWakeRenewMs = 0L
    }

    @Suppress("DEPRECATION")
    private fun vibrateBriefly() {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(250L, VibrationEffect.DEFAULT_AMPLITUDE))
        else vibrator.vibrate(250L)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager: NotificationManager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Atemschutzüberwachung", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(BATTERY_CHANNEL_ID, "Atemschutz: Akkuwarnung", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotification(id: Long, lines: List<String>): Notification {
        val launch: Intent? = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EinsatzLiveNotifier.EXTRA_EC_URL, EinsatzAlarmNotification.absoluteUrl(this@AtemschutzWatchService, "/einsatz/$id/atemschutz"))
        }
        val ack: PendingIntent = PendingIntent.getService(this, 7305,
            Intent(this, AtemschutzWatchService::class.java).setAction(ACTION_ACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Atemschutzüberwachung")
            .setContentText(lines.lastOrNull() ?: "Überwachung wird gestartet")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Alarm quittieren", ack)
            .apply {
                launch?.let { setContentIntent(PendingIntent.getActivity(this@AtemschutzWatchService, 7305, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            }.build()
    }

    private fun postBatteryWarning() {
        val notification: Notification = NotificationCompat.Builder(this, BATTERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Akku niedrig - Ladegerät anschließen")
            .setContentText("Atemschutzüberwachung: ${batteryPercent ?: 0} % Akku")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(BATTERY_NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun finishWatch() {
        stopping = true
        local.removeCallbacks(tick)
        local.removeCallbacks(networkTick)
        safely("Deaktivieren") { AsuewStore.deactivate(this) }
        releaseWakeLock()
        safely("Benachrichtigung entfernen") {
            @Suppress("DEPRECATION")
            stopForeground(true)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            getSystemService(NotificationManager::class.java).cancel(BATTERY_NOTIFICATION_ID)
        }
        snapshot = AsuewWatchSnapshot()
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        local.removeCallbacksAndMessages(null)
        network.removeCallbacksAndMessages(null)
        // Auf demselben Thread wie acquire(), damit beim Beenden kein Lock nachträglich entsteht.
        local.post {
            releaseWakeLock()
            localThread.quitSafely()
        }
        networkThread.quitSafely()
        snapshot = AsuewWatchSnapshot()
        super.onDestroy()
    }

    private inline fun safely(operation: String, block: () -> Unit) {
        try { block() } catch (error: Exception) {
            NativeLog.append("ASÜW $operation: ${error.javaClass.simpleName}")
        }
    }
}
