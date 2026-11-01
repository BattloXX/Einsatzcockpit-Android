package cloud.einsatzleiter.smsgatewayplugin

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground-Service: Hält eine persistente WebSocket-Verbindung zur Main App
 * und versendet SMS-Aufträge (sms.send) über die eingebaute SIM-Karte.
 * Optional (Org-Schalter + Berechtigung) empfängt sie zusätzlich SMS und
 * leitet sie an den Server weiter.
 *
 * Protokoll: identisch mit Einsatzcockpit SMS-Gateway (PROTOCOL.md)
 *   - hello → ping/pong → sms.send → SmsManager → sms.result
 *   - config (Server→App: receive_enabled) → sms.received → sms.received.ack
 */
class SmsGatewayService : Service() {

    companion object {
        const val ACTION_START            = "cloud.einsatzleiter.smsgatewayplugin.START"
        const val ACTION_STOP             = "cloud.einsatzleiter.smsgatewayplugin.STOP"
        const val ACTION_REFRESH_RECEIVER = "cloud.einsatzleiter.smsgatewayplugin.REFRESH_RECEIVER"
        const val EXTRA_URL    = "url"
        const val EXTRA_TOKEN  = "token"

        private const val NOTIF_ID         = 7301
        private const val WAKE_LOCK_TAG    = "einsatzcockpit:smsgw"
        private const val CONNECT_STUCK_TIMEOUT_MS = 25_000L
        // Konservativer Default; das Server-Ping-Intervall ist hier nicht bekannt.
        private const val HEARTBEAT_STALE_MS       = 120_000L
        private const val APP_PING_INTERVAL_MS     = 45_000L   // < HEARTBEAT_STALE_MS (120s), damit mind. ein Ping-Zyklus Puffer bleibt

        // SMS-Empfang: Server-gemeldeter Soll-Zustand (persistiert für Neustart nach Boot)
        // und lokal gepufferte, noch nicht bestätigte Empfangs-SMS.
        private const val PREF_RECEIVE_ENABLED = "el_gateway_receive_enabled"
        private const val PREF_INBOX_QUEUE     = "el_gateway_inbox_queue"
        private val inboundLock = Any()

        // ── Zustand (von Plugin abgelesen) ────────────────────────────────────
        @Volatile var isConnected = false
        /** Read by the update worker so an APK install never interrupts a reconnect. */
        @Volatile var connectingSince: Long = 0L
        @Volatile var lastError: String? = null
        val sentCount = AtomicInteger(0)
        @Volatile var lastSentTo: String? = null
        @Volatile var lastSentAt: Long = 0

        // ── Plugin-Event-Listener ─────────────────────────────────────────────
        private val listeners = CopyOnWriteArrayList<(event: String, data: JSObject) -> Unit>()

        fun addPluginListener(l: (String, JSObject) -> Unit) { listeners.add(l) }
        fun removePluginListener(l: (String, JSObject) -> Unit) { listeners.remove(l) }

        internal fun emit(event: String, build: JSObject.() -> Unit = {}) {
            val data = JSObject().apply(build)
            listeners.forEach { it(event, data) }
        }

        internal fun log(msg: String) {
            NativeLog.append(msg)
            emit("logEvent") {
                put("msg", msg)
                put("ts", System.currentTimeMillis())
            }
        }

        // ── Direkter SMS-Versand (Test-Button, ohne WebSocket-Job) ────────────
        fun sendDirectSms(context: Context, to: String, text: String,
                          callback: (ok: Boolean, msg: String) -> Unit) {
            SmsGatewaySender.sendDirectSms(context, to, text, callback, requestCounter)
        }

        private val requestCounter = AtomicInteger(10000)


    }

    // ── Instanzfelder ─────────────────────────────────────────────────────────
    private var wsUrl  = ""
    private var token  = ""
    private var ws: WebSocket? = null

    // @Volatile: running wird von OkHttp-Threads und dem Main-Thread gelesen/geschrieben
    @Volatile private var running = false

    // Verhindert dass mehrere Quellen (Watchdog, Reconnect, NetworkCallback) gleichzeitig
    // einen WebSocket aufbauen und dadurch doppelte/tote Verbindungen entstehen.
    @Volatile private var connecting = false
    @Volatile private var lastMessageAt: Long = 0L

    private var reconnectDelay = 1000L   // ms, verdoppelt bis max 30 000 ms
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler  = Handler(Looper.getMainLooper())
    private val appPingHandler   = Handler(Looper.getMainLooper())

    // Hält die CPU wach, damit Timer und WebSocket-Pings auch im Hintergrund feuern
    private var wakeLock: PowerManager.WakeLock? = null

    // Dynamisch registrierter Empfangs-Receiver – nur aktiv wenn Server-Config +
    // RECEIVE_SMS-Berechtigung beide zutreffen (siehe updateSmsReceiver()).
    private var smsReceiver: BroadcastReceiver? = null

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)     // WS-Ping alle 20 s
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)     // tote Verbindungen schneller erkennen
            .readTimeout(0, TimeUnit.SECONDS)        // kein Lese-Timeout (persistente Verbindung)
            .build()
    }

    // Erkennt Netzwechsel (WLAN→Mobil u. ä.) und löst sofortigen Reconnect aus
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (running && !isConnected) {
                log("Netzwerk verfügbar – verbinde neu…")
                reconnectDelay = 1000L
                reconnectHandler.removeCallbacksAndMessages(null)
                reconnectHandler.post { connect() }
            }
        }
        override fun onLost(network: Network) {
            if (running) {
                log("Netzwerk verloren – WebSocket schließen")
                ws?.close(1001, "Netzwerk verloren")
                ws = null
                isConnected = false
                reconnectDelay = 1000L
                emitStatus()
                // onClosed des alten WS löst scheduleReconnect() aus;
                // onAvailable triggert sofort connect() wenn Netz zurückkommt
            }
        }
    }

    private val connectionEpoch = AtomicLong(0)
    private lateinit var dispatchJournal: SmsDispatchJournal
    private lateinit var dispatchProtocol: SmsDispatchProtocol
    private val reportHandler = Handler(Looper.getMainLooper())
    private val pendingSendReceivers = CopyOnWriteArrayList<BroadcastReceiver>()
    private fun dispatchScope() = SmsDispatchJournal.scope(wsUrl, token)
    private var lastDispatchPurgeAt: Long? = null
    @Synchronized private fun maintainDispatchJournal() {
        val now = System.currentTimeMillis()
        dispatchJournal.expireStaleAttempts(now)
        if (lastDispatchPurgeAt == null || now - lastDispatchPurgeAt!! >= 60 * 60_000L) {
            dispatchJournal.purge(now)
            lastDispatchPurgeAt = now
        }
    }
    private val reportTick = object : Runnable {
        override fun run() {
            if (running) maintainDispatchJournal()
            if (running && isConnected) dispatchProtocol.pump(dispatchScope())
            if (running) reportHandler.postDelayed(this, 5_000)
        }
    }

    // ── Service-Lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NativeLog.init(applicationContext)
        SmsGatewayNotification.createChannel(notificationManager)
        dispatchJournal = SmsDispatchJournal(applicationContext, ::dispatchScope)
        dispatchProtocol = SmsDispatchProtocol(dispatchJournal, object : SmsDispatchProtocol.Modem {
            override fun preflight(recipient: String, body: String): String? =
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.SEND_SMS) !=
                    PackageManager.PERMISSION_GRANTED) "sms_permission_missing" else null
            override fun submit(recipient: String, body: String, callback: (Int, Int, Boolean) -> Unit) {
                submitJournaledSms(recipient, body, callback)
            }
        }) { scope, message ->
            if (scope == dispatchScope() && isConnected) ws?.send(message.toString())
        }
        // Gespeicherte Konfig einlesen (für Neustart nach System-Kill)
        val prefs = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
        wsUrl = prefs.getString("el_gateway_url", "") ?: ""
        token = prefs.getString("el_gateway_token", "") ?: ""
        dispatchJournal.recover()
        if (wsUrl.isNotEmpty() && token.isNotEmpty()) maintainDispatchJournal()
        // Empfangs-Receiver ggf. sofort wieder aktivieren (Neustart nach Boot/Kill)
        updateSmsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val nextUrl = intent.getStringExtra(EXTRA_URL) ?: wsUrl
                if (!UrlPolicy.isAllowedRemoteUrl(nextUrl)) {
                    log("Gateway-URL nicht erlaubt")
                    // Per startForegroundService gestartet: ohne stopSelf droht ein FGS-Timeout-Crash.
                    stopSelf()
                    return START_NOT_STICKY
                }
                val nextToken = intent.getStringExtra(EXTRA_TOKEN) ?: token
                if (nextUrl != wsUrl || nextToken != token) {
                    connectionEpoch.incrementAndGet()
                    ws?.cancel()
                    ws = null
                    connecting = false
                    isConnected = false
                    stopAppPing()
                }
                wsUrl = nextUrl
                token = nextToken
                if (wsUrl.isEmpty() || token.isEmpty()) return START_NOT_STICKY
                running = true
                log("Service gestartet")
                startForegroundCompat("Verbinde…")
                acquireWakeLock()
                registerNetworkCallback()
                connect()
                startWatchdog()
            }
            ACTION_STOP -> {
                running = false
                stopWatchdog()
                stopAppPing()
                ws?.close(1000, "Gestoppt")
                ws = null
                isConnected = false
                connectingSince = 0L
                lastMessageAt = 0L
                emit("statusChanged") {
                    put("connected", false)
                    put("lastError", "")
                    put("sentCount", sentCount.get())
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_RECEIVER -> {
                // Vom Plugin nach Berechtigungs-Erteilung/-Entzug aufgerufen –
                // registriert/entfernt den Empfangs-Receiver ohne den Rest neu zu starten.
                updateSmsReceiver()
                return START_NOT_STICKY
            }
            null -> {
                // System-Neustart (START_STICKY) – Konfig aus SharedPrefs
                if (wsUrl.isNotEmpty() && !UrlPolicy.isAllowedRemoteUrl(wsUrl)) {
                    log("Gateway-URL nicht erlaubt")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (wsUrl.isNotEmpty() && token.isNotEmpty()) {
                    running = true
                    log("Service neu gestartet (System/Boot)")
                    startForegroundCompat("Verbinde (Neustart)…")
                    acquireWakeLock()
                    registerNetworkCallback()
                    connect()
                    startWatchdog()
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        reportHandler.removeCallbacksAndMessages(null)
        reportHandler.post(reportTick)
        updateSmsReceiver()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        reportHandler.removeCallbacksAndMessages(null)
        pendingSendReceivers.forEach { try { unregisterReceiver(it) } catch (_: Exception) {} }
        pendingSendReceivers.clear()
        stopWatchdog()
        stopAppPing()
        reconnectHandler.removeCallbacksAndMessages(null)
        ws?.close(1000, "Service zerstört")
        ws = null
        connectingSince = 0L
        lastMessageAt = 0L
        unregisterNetworkCallback()
        releaseWakeLock()
        httpClient.dispatcher.cancelAll()
        smsReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        smsReceiver = null
        super.onDestroy()
    }

    /**
     * Defensive Absicherung: Sollte das System künftig (oder bei anderem FGS-Typ) ein
     * Laufzeitlimit durchsetzen, wird hier statt eines Kills die Verbindung sauber neu
     * aufgebaut. Mit foregroundServiceType=specialUse greift derzeit kein Limit.
     */
    override fun onTimeout(startId: Int) {
        log("onTimeout vom System – Foreground erneuern & reconnecten")
        if (!running) { stopSelf(); return }
        try { startForegroundCompat("Verbinde (Neustart)…") } catch (_: Exception) {}
        acquireWakeLock()
        isConnected = false
        reconnectDelay = 1000L
        connect()
    }

    /**
     * startForeground mit explizitem FGS-Typ ab Android 14 (API 34, ab dem
     * FOREGROUND_SERVICE_TYPE_SPECIAL_USE existiert). Darunter genügt die
     * Manifest-Deklaration.
     */
    private fun startForegroundCompat(status: String) {
        val notif = SmsGatewayNotification.build(this, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also {
                it.setReferenceCounted(false)
                it.acquire(24 * 60 * 60 * 1000L)   // max. 24 h; Watchdog re-acquires bei Bedarf
            }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────
    // Unabhängige 30-s-Schleife: stellt sicher dass ein Reconnect auch dann
    // ausgelöst wird wenn Handler-Callbacks durch Doze verschluckt wurden.

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            // WakeLock bei JEDEM Durchlauf erneuern – sonst läuft der 24-h-Lock im
            // dauerhaft verbundenen Zustand aus und das Gerät kann schlafen/dozen.
            acquireWakeLock()
            val now = System.currentTimeMillis()
            when {
                !isConnected && !connecting -> {
                    log("Watchdog: nicht verbunden – erzwinge Reconnect")
                    reconnectDelay = 1000L
                    reconnectHandler.removeCallbacksAndMessages(null)
                    connect()
                }
                connecting && connectingSince != 0L && now - connectingSince > CONNECT_STUCK_TIMEOUT_MS -> {
                    forceReconnect("Verbindungsaufbau hängt seit ${(now - connectingSince) / 1000}s")
                }
                isConnected && lastMessageAt != 0L && now - lastMessageAt > HEARTBEAT_STALE_MS -> {
                    forceReconnect("kein Lebenszeichen seit ${(now - lastMessageAt) / 1000}s – Verbindung wirkt tot")
                }
            }
            watchdogHandler.postDelayed(this, 30_000L)
        }
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacksAndMessages(null)
        watchdogHandler.postDelayed(watchdogRunnable, 30_000L)
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacksAndMessages(null)
    }

    private val appPingRunnable = object : Runnable {
        override fun run() {
            if (!running || !isConnected) return
            ws?.send("""{"type":"ping"}""")
            appPingHandler.postDelayed(this, APP_PING_INTERVAL_MS)
        }
    }

    private fun startAppPing() {
        appPingHandler.removeCallbacksAndMessages(null)
        appPingHandler.postDelayed(appPingRunnable, APP_PING_INTERVAL_MS)
    }

    private fun stopAppPing() {
        appPingHandler.removeCallbacksAndMessages(null)
    }

    // ── NetworkCallback ───────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            // Nur das Standardnetz beobachten, damit der Verlust eines anderen Netzes den WebSocket nicht trennt.
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    // ── WebSocket-Verbindung ──────────────────────────────────────────────────

    @Synchronized private fun connect() {
        if (!running) return
        maintainDispatchJournal()
        // Nur eine Verbindung gleichzeitig: Watchdog, scheduleReconnect und
        // NetworkCallback können connect() parallel aufrufen – ohne diesen Guard
        // entstünden mehrere WebSockets (und serverseitig tote Doppel-Registrierungen).
        if (connecting || isConnected) return
        connecting = true
        connectingSince = System.currentTimeMillis()
        reconnectHandler.removeCallbacksAndMessages(null)

        val epoch = connectionEpoch.incrementAndGet()
        // Eventuell noch offenen alten Socket hart schließen, bevor ein neuer entsteht.
        ws?.cancel()
        ws = null

        val wsUri = SmsGatewayLogic.toWsUrl(wsUrl)
        val scope = dispatchScope()
        log("Verbinde mit $wsUri")
        val request = Request.Builder()
            .url(wsUri)
            .header("Authorization", "Bearer $token")
            .build()

        val connectTime = System.currentTimeMillis()

        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (epoch != connectionEpoch.get()) { webSocket.cancel(); return }
                this@SmsGatewayService.ws = webSocket
                connecting = false
                connectingSince = 0L
                isConnected = true
                lastMessageAt = System.currentTimeMillis()
                startAppPing()
                lastError = null
                reconnectDelay = 1000L
                log("✓ Verbunden – hello gesendet")
                webSocket.send(dispatchProtocol.hello(scope).toString())
                dispatchProtocol.pump(scope)
                updateNotification("Verbunden ✓")
                emitStatus()
                // Waehrend der Trennung empfangene, noch nicht bestaetigte SMS nachsenden
                flushInboundQueue(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (epoch != connectionEpoch.get()) return
                handleMessage(webSocket, text, scope)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (epoch != connectionEpoch.get()) return
                connecting = false
                connectingSince = 0L
                isConnected = false
                stopAppPing()
                lastError = t.message ?: "Verbindungsfehler"
                log("✗ Verbindungsfehler: ${lastError?.take(120)}")
                updateNotification("Getrennt – ${lastError?.take(40)}")
                emitStatus()
                // Backoff zurücksetzen wenn Verbindung ≥60 s stabil war
                if (System.currentTimeMillis() - connectTime >= 60_000) reconnectDelay = 1000L
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (epoch != connectionEpoch.get()) return
                connecting = false
                connectingSince = 0L
                isConnected = false
                stopAppPing()
                log("WebSocket geschlossen (Code $code: $reason)")
                emitStatus()
                if (running) scheduleReconnect()
            }
        })
    }

    private fun handleMessage(ws: WebSocket, text: String, scope: String) {
        if (scope != dispatchScope()) return
        lastMessageAt = System.currentTimeMillis()
        val msg = try { JSONObject(text) } catch (_: Exception) { return }

        when (msg.optString("type")) {
            "ping" -> {
                ws.send("""{"type":"pong"}""")
                log("← ping → pong")
            }
            "pong" -> { /* ignorieren */ }
            "sms.send", "sms.prepare", "sms.commit", "sms.result.ack", "sms.abandon" -> {
                if (msg.optString("type") in listOf("sms.send", "sms.prepare")) {
                    log("← SMS-Auftrag #${msg.optString("id").take(8)}")
                    emit("smsQueued") { put("jobId", msg.optString("id")) }
                }
                dispatchProtocol.handle(scope, msg)
            }
            "sms.received.ack" -> {
                val id = msg.optString("id")
                if (id.isNotEmpty()) removeFromInboundQueue(id)
            }
            "config" -> {
                applyReceiveEnabled(msg.optBoolean("receive_enabled", false))
            }
        }
    }

    // ── SMS-Versand ───────────────────────────────────────────────────────────

    private fun submitJournaledSms(to: String, text: String, callback: (Int, Int, Boolean) -> Unit) {
        val smsManager = Compat.getSmsManager(applicationContext)
        val parts = smsManager.divideMessage(text) ?: arrayListOf(text)
        val action = "cloud.einsatzleiter.smsgatewayplugin.SMS_SENT.${UUID.randomUUID()}"
        val seen = mutableSetOf<Int>()
        var allAccepted = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val part = intent.getIntExtra("part", -1)
                if (part !in parts.indices) return
                synchronized(seen) {
                    if (!seen.add(part)) return
                    allAccepted = allAccepted && resultCode == android.app.Activity.RESULT_OK
                    callback(part, parts.size, resultCode == android.app.Activity.RESULT_OK)
                    if (seen.size == parts.size) {
                        if (allAccepted) {
                            sentCount.incrementAndGet()
                            lastSentTo = to
                            lastSentAt = System.currentTimeMillis()
                            log("✓ SMS vom Modem angenommen: ${SmsGatewayLogic.maskNumber(to)} (${parts.size} Teil(e))")
                            emit("smsSent") {
                                put("to", SmsGatewayLogic.maskNumber(to))
                                put("parts", parts.size)
                                put("at", lastSentAt)
                            }
                        } else log("SMS-Versandergebnis unklar; kein automatischer Neuversand")
                        emitStatus()
                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        pendingSendReceivers.remove(this)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(applicationContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        pendingSendReceivers.add(receiver)
        val intents = ArrayList<PendingIntent>(parts.size)
        repeat(parts.size) { part ->
            intents.add(PendingIntent.getBroadcast(applicationContext, requestCounter.getAndIncrement(),
                Intent(action).setPackage(packageName).putExtra("part", part), PendingIntent.FLAG_IMMUTABLE))
        }
        try {
            if (parts.size == 1) smsManager.sendTextMessage(to, null, text, intents[0], null)
            else smsManager.sendMultipartTextMessage(to, null, parts, intents, null)
        } catch (error: Exception) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            pendingSendReceivers.remove(receiver)
            throw error // journal remains uncertain; no automatic resubmission
        }
    }

    // ── SMS-Empfang ───────────────────────────────────────────────────────────
    // Optional (Org-Schalter am Server + RECEIVE_SMS-Berechtigung). Der Server
    // teilt den Soll-Zustand per "config"-Nachricht mit; erst wenn beides zutrifft
    // wird ein dynamischer Receiver registriert. Empfangene SMS werden lokal
    // gepuffert und erst nach Server-Bestaetigung (sms.received.ack) verworfen –
    // so gehen SMS bei Verbindungsabbruch nicht verloren.

    /**
     * Registriert/entfernt den Empfangs-Receiver je nach Soll-Zustand (SharedPrefs)
     * und tatsaechlich erteilter RECEIVE_SMS-Berechtigung. Idempotent.
     */
    private fun updateSmsReceiver() {
        val desired = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
            .getBoolean(PREF_RECEIVE_ENABLED, false)
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

        if (desired && granted) {
            if (smsReceiver == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        handleSmsReceivedIntent(intent)
                    }
                }
                // WICHTIG: RECEIVER_EXPORTED (nicht NOT_EXPORTED). SMS_RECEIVED wird vom
                // SYSTEM (Telephony-Prozess, fremde UID) gesendet; mit NOT_EXPORTED würde
                // der Broadcast ab Android 13/14 (targetSdk 34+) NIE zugestellt → kein
                // Empfang. Sicher, da SMS_RECEIVED ein geschützter Broadcast ist (nur das
                // System darf ihn senden, keine Fremd-App kann ihn fälschen).
                ContextCompat.registerReceiver(
                    this, receiver,
                    IntentFilter("android.provider.Telephony.SMS_RECEIVED"),
                    ContextCompat.RECEIVER_EXPORTED)
                smsReceiver = receiver
                log("SMS-Empfang aktiviert")
            }
        } else {
            val receiver = smsReceiver
            if (receiver != null) {
                try { unregisterReceiver(receiver) } catch (_: Exception) {}
                smsReceiver = null
                log("SMS-Empfang deaktiviert")
            }
        }
    }

    /** Persistiert den vom Server gemeldeten Soll-Zustand und wendet ihn an. */
    private fun applyReceiveEnabled(serverEnabled: Boolean) {
        getSharedPreferences("CapacitorStorage", MODE_PRIVATE).edit()
            .putBoolean(PREF_RECEIVE_ENABLED, serverEnabled).apply()
        updateSmsReceiver()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        emit("configChanged") {
            put("receiveEnabled", serverEnabled)
            put("receivePermissionGranted", granted)
        }
    }

    private fun handleSmsReceivedIntent(intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val from = messages[0].originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        forwardReceivedSms(from, body)
    }

    private fun forwardReceivedSms(from: String, text: String) {
        val entry = SmsGatewayLogic.inboundEntry(from, text)
        enqueueInboundSms(entry)
        log("→ SMS empfangen von ${SmsGatewayLogic.maskNumber(from)} (${text.length} Zeichen)")
        emit("smsReceived") {
            put("from", SmsGatewayLogic.maskNumber(from))
            put("preview", text.take(60))
            put("at", System.currentTimeMillis())
        }
        ws?.let { flushInboundQueue(it) }
    }

    // ── Empfangs-Warteschlange (SharedPreferences, ueberlebt Reconnect/Neustart) ──

    private fun loadInboundQueue(): JSONArray {
        val raw = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
            .getString(PREF_INBOX_QUEUE, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun saveInboundQueue(arr: JSONArray) {
        getSharedPreferences("CapacitorStorage", MODE_PRIVATE).edit()
            .putString(PREF_INBOX_QUEUE, arr.toString()).commit()
    }

    private fun enqueueInboundSms(entry: JSONObject) {
        synchronized(inboundLock) {
            saveInboundQueue(InboundQueue.enqueue(loadInboundQueue(), entry))
        }
    }

    private fun removeFromInboundQueue(id: String) {
        synchronized(inboundLock) {
            saveInboundQueue(InboundQueue.remove(loadInboundQueue(), id))
        }
    }

    private fun flushInboundQueue(socket: WebSocket) {
        // loadInboundQueue parst eine unabhaengige Kopie; Versand erfolgt ohne Lock.
        val arr = synchronized(inboundLock) { loadInboundQueue() }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val payload = SmsGatewayLogic.inboundPayload(obj)
            socket.send(payload.toString())
        }
    }

    // ── Hilfs-Methoden ────────────────────────────────────────────────────────

    private fun forceReconnect(reason: String) {
        log("Erzwinge Reconnect: $reason")
        ws?.cancel()
        ws = null
        connecting = false
        isConnected = false
        connectingSince = 0L
        stopAppPing()
        reconnectDelay = 1000L
        reconnectHandler.removeCallbacksAndMessages(null)
        emitStatus()
        connect()
    }

    private fun scheduleReconnect() {
        if (!running) return
        log("Reconnect in ${reconnectDelay / 1000} s…")
        updateNotification("Reconnect in ${reconnectDelay / 1000}s…")
        reconnectHandler.postDelayed({
            reconnectDelay = SmsGatewayLogic.nextReconnectDelay(reconnectDelay)
            connect()
        }, reconnectDelay)
    }

    private fun emitStatus() {
        emit("statusChanged") {
            put("connected", isConnected)
            put("lastError", lastError ?: "")
            put("sentCount", sentCount.get())
            put("lastSentTo", lastSentTo ?: "")
            put("lastSentAt", lastSentAt)
        }
    }

    // ── Benachrichtigungen ────────────────────────────────────────────────────

    private fun updateNotification(status: String) {
        notificationManager.notify(NOTIF_ID, SmsGatewayNotification.build(this, status))
    }
}
