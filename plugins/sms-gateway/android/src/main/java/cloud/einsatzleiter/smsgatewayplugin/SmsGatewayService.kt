package cloud.einsatzleiter.smsgatewayplugin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
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
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
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

        private const val NOTIF_CHANNEL_ID = "ec_sms_gateway"
        private const val NOTIF_ID         = 7301
        private const val WAKE_LOCK_TAG    = "einsatzcockpit:smsgw"

        // SMS-Empfang: Server-gemeldeter Soll-Zustand (persistiert für Neustart nach Boot)
        // und lokal gepufferte, noch nicht bestätigte Empfangs-SMS.
        private const val PREF_RECEIVE_ENABLED = "el_gateway_receive_enabled"
        private const val PREF_INBOX_QUEUE     = "el_gateway_inbox_queue"
        private const val INBOX_QUEUE_MAX      = 200

        // ── Zustand (von Plugin abgelesen) ────────────────────────────────────
        @Volatile var isConnected = false
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
            emit("logEvent") {
                put("msg", msg)
                put("ts", System.currentTimeMillis())
            }
        }

        // ── Direkter SMS-Versand (Test-Button, ohne WebSocket-Job) ────────────
        fun sendDirectSms(context: Context, to: String, text: String,
                          callback: (ok: Boolean, msg: String) -> Unit) {
            try {
                val smsManager = getSmsManager(context)
                val parts = smsManager.divideMessage(text) ?: arrayListOf(text)
                val action = "cloud.einsatzleiter.smsgatewayplugin.TEST_SMS_SENT"
                val partCount = parts.size
                val sentParts = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val counter = requestCounter

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val done = sentParts.incrementAndGet()
                        if (resultCode != android.app.Activity.RESULT_OK) failed.incrementAndGet()
                        if (done >= partCount) {
                            try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                            val ok = failed.get() == 0
                            callback(ok, if (ok) "OK" else "SmsManager resultCode=$resultCode")
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

                val intents = ArrayList<PendingIntent>(partCount)
                repeat(partCount) {
                    intents.add(PendingIntent.getBroadcast(
                        context, counter.getAndIncrement(),
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE))
                }
                if (partCount == 1) smsManager.sendTextMessage(to, null, text, intents[0], null)
                else smsManager.sendMultipartTextMessage(to, null, parts, intents, null)
            } catch (e: Exception) {
                callback(false, e.message ?: "Unknown error")
            }
        }

        private val requestCounter = AtomicInteger(10000)

        private fun getSmsManager(context: Context): SmsManager =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
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

    private var reconnectDelay = 1000L   // ms, verdoppelt bis max 30 000 ms
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler  = Handler(Looper.getMainLooper())

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

    // ── Service-Lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Gespeicherte Konfig einlesen (für Neustart nach System-Kill)
        val prefs = getSharedPreferences("CapacitorStorage", MODE_PRIVATE)
        wsUrl = prefs.getString("el_gateway_url", "") ?: ""
        token = prefs.getString("el_gateway_token", "") ?: ""
        // Empfangs-Receiver ggf. sofort wieder aktivieren (Neustart nach Boot/Kill)
        updateSmsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                wsUrl  = intent.getStringExtra(EXTRA_URL)   ?: wsUrl
                token  = intent.getStringExtra(EXTRA_TOKEN) ?: token
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
                ws?.close(1000, "Gestoppt")
                ws = null
                isConnected = false
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
        updateSmsReceiver()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopWatchdog()
        reconnectHandler.removeCallbacksAndMessages(null)
        ws?.close(1000, "Service zerstört")
        ws = null
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
        val notif = buildNotification(status)
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
            if (!isConnected) {
                log("Watchdog: nicht verbunden – erzwinge Reconnect")
                reconnectDelay = 1000L
                reconnectHandler.removeCallbacksAndMessages(null)
                connect()
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

    // ── NetworkCallback ───────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkCallback() {
        try {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    // ── WebSocket-Verbindung ──────────────────────────────────────────────────

    private fun connect() {
        if (!running) return
        // Nur eine Verbindung gleichzeitig: Watchdog, scheduleReconnect und
        // NetworkCallback können connect() parallel aufrufen – ohne diesen Guard
        // entstünden mehrere WebSockets (und serverseitig tote Doppel-Registrierungen).
        if (connecting || isConnected) return
        connecting = true
        reconnectHandler.removeCallbacksAndMessages(null)

        // Eventuell noch offenen alten Socket hart schließen, bevor ein neuer entsteht.
        ws?.cancel()
        ws = null

        val wsUri = toWsUrl(wsUrl)
        log("Verbinde mit $wsUri?token=…")
        val request = Request.Builder()
            .url("$wsUri?token=$token")
            .header("Authorization", "Bearer $token")
            .build()

        val connectTime = System.currentTimeMillis()

        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting = false
                isConnected = true
                lastError = null
                reconnectDelay = 1000L
                log("✓ Verbunden – hello gesendet")
                webSocket.send("""{"type":"hello","role":"sms-gateway","version":"1.0"}""")
                updateNotification("Verbunden ✓")
                emitStatus()
                // Waehrend der Trennung empfangene, noch nicht bestaetigte SMS nachsenden
                flushInboundQueue(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connecting = false
                isConnected = false
                lastError = t.message ?: "Verbindungsfehler"
                log("✗ Verbindungsfehler: ${lastError?.take(120)}")
                updateNotification("Getrennt – ${lastError?.take(40)}")
                emitStatus()
                // Backoff zurücksetzen wenn Verbindung ≥60 s stabil war
                if (System.currentTimeMillis() - connectTime >= 60_000) reconnectDelay = 1000L
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connecting = false
                isConnected = false
                log("WebSocket geschlossen (Code $code: $reason)")
                emitStatus()
                if (running) scheduleReconnect()
            }
        })
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }

        when (msg.optString("type")) {
            "ping" -> {
                ws.send("""{"type":"pong"}""")
                log("← ping → pong")
            }
            "pong" -> { /* ignorieren */ }
            "sms.send" -> {
                val jobId = msg.optString("id").ifEmpty { return }
                val to    = msg.optString("to").ifEmpty { return }
                val body  = msg.optString("text")
                log("← SMS-Auftrag #${jobId.take(8)} an $to (${body.length} Zeichen)")
                emit("smsQueued") { put("jobId", jobId) }
                sendSmsForJob(ws, jobId, to, body)
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

    private fun sendSmsForJob(ws: WebSocket, jobId: String, to: String, text: String) {
        try {
            val smsManager = getSmsManager(applicationContext)
            val parts = smsManager.divideMessage(text) ?: arrayListOf(text)
            val partCount = parts.size
            val sentParts = AtomicInteger(0)
            val failedParts = AtomicInteger(0)
            val action = "cloud.einsatzleiter.smsgatewayplugin.SMS_SENT.$jobId"

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val done = sentParts.incrementAndGet()
                    if (resultCode != android.app.Activity.RESULT_OK) failedParts.incrementAndGet()
                    if (done >= partCount) {
                        try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        val ok = failedParts.get() == 0
                        ws.send(buildSmsResult(jobId, ok,
                            if (!ok) "SmsManager resultCode=$resultCode" else null))
                        if (ok) {
                            log("✓ SMS versendet an $to ($partCount Teil(e))")
                            sentCount.incrementAndGet()
                            lastSentTo = to
                            lastSentAt = System.currentTimeMillis()
                            emit("smsSent") {
                                put("to", maskNumber(to))
                                put("parts", partCount)
                                put("at", lastSentAt)
                            }
                        } else {
                            log("✗ SMS-Versand fehlgeschlagen: resultCode=$resultCode")
                        }
                        emitStatus()
                    }
                }
            }
            ContextCompat.registerReceiver(
                applicationContext, receiver,
                IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

            val intents = ArrayList<PendingIntent>(partCount)
            repeat(partCount) {
                intents.add(PendingIntent.getBroadcast(
                    applicationContext,
                    requestCounter.getAndIncrement(),
                    Intent(action).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE))
            }

            if (partCount == 1) {
                smsManager.sendTextMessage(to, null, text, intents[0], null)
            } else {
                smsManager.sendMultipartTextMessage(to, null, parts, intents, null)
            }
        } catch (e: Exception) {
            log("✗ SMS-Exception: ${e.message?.take(80)}")
            ws.send(buildSmsResult(jobId, false, "Exception: ${e.message}"))
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
        val entry = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("from", from)
            put("text", text)
            put("sent_at", System.currentTimeMillis())
        }
        enqueueInboundSms(entry)
        log("→ SMS empfangen von ${maskNumber(from)} (${text.length} Zeichen)")
        emit("smsReceived") {
            put("from", maskNumber(from))
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
            .putString(PREF_INBOX_QUEUE, arr.toString()).apply()
    }

    private fun enqueueInboundSms(entry: JSONObject) {
        val arr = loadInboundQueue()
        arr.put(entry)
        // Aelteste Eintraege verwerfen wenn das Limit ueberschritten wird (Speicher begrenzen)
        val start = maxOf(0, arr.length() - INBOX_QUEUE_MAX)
        if (start == 0) {
            saveInboundQueue(arr)
        } else {
            val trimmed = JSONArray()
            for (i in start until arr.length()) trimmed.put(arr.get(i))
            saveInboundQueue(trimmed)
        }
    }

    private fun removeFromInboundQueue(id: String) {
        val arr = loadInboundQueue()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("id") != id) kept.put(obj)
        }
        saveInboundQueue(kept)
    }

    private fun flushInboundQueue(socket: WebSocket) {
        val arr = loadInboundQueue()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val payload = JSONObject(obj.toString()).apply { put("type", "sms.received") }
            socket.send(payload.toString())
        }
    }

    // ── Hilfs-Methoden ────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (!running) return
        log("Reconnect in ${reconnectDelay / 1000} s…")
        updateNotification("Reconnect in ${reconnectDelay / 1000}s…")
        reconnectHandler.postDelayed({
            reconnectDelay = minOf(reconnectDelay * 2, 30_000L)
            connect()
        }, reconnectDelay)
    }

    private fun toWsUrl(url: String): String = url
        .replace("https://", "wss://")
        .replace("http://", "ws://")
        .trimEnd('/')
        .plus("/ws/sms-gateway")

    private fun buildSmsResult(jobId: String, ok: Boolean, error: String?): String =
        if (ok) """{"type":"sms.result","id":"$jobId","ok":true,"provider_response":"OK"}"""
        else    """{"type":"sms.result","id":"$jobId","ok":false,"error":"${error?.replace("\"","\\\"")}"}"""

    private fun maskNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 6) "*".repeat(number.length - 4) + number.takeLast(4)
               else "****"
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

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "SMS-Gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Einsatzcockpit SMS-Gateway-Status"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val tapPending = if (tapIntent != null)
            PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else null
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("SMS-Gateway aktiv")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { tapPending?.let { setContentIntent(it) } }
            .build()
    }

    private fun updateNotification(status: String) {
        notificationManager.notify(NOTIF_ID, buildNotification(status))
    }
}
