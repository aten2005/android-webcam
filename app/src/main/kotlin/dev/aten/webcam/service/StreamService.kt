package dev.aten.webcam.service

import android.Manifest
import android.annotation.SuppressLint
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
import android.net.LinkProperties
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.aten.webcam.R
import dev.aten.webcam.auth.AuditLog
import dev.aten.webcam.auth.LoginRateLimiter
import dev.aten.webcam.auth.SessionStore
import dev.aten.webcam.data.AppState
import dev.aten.webcam.data.ServiceStatus
import dev.aten.webcam.data.Settings
import dev.aten.webcam.media.MediaController
import dev.aten.webcam.server.AssetSource
import dev.aten.webcam.server.StaticAssets
import dev.aten.webcam.server.WebServer
import dev.aten.webcam.server.WebServerConfig
import dev.aten.webcam.session.Cancellable
import dev.aten.webcam.session.Scheduler
import dev.aten.webcam.session.SessionManager
import dev.aten.webcam.tls.CertStore
import dev.aten.webcam.tls.TlsContextFactory
import dev.aten.webcam.tls.TlsIdentity
import dev.aten.webcam.ui.MainActivity
import java.io.File
import java.io.IOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * Hosts the listener. It must be started while the activity is visible: only then does Android
 * let a camera/microphone foreground service open them later, from the background, when a viewer
 * connects. Until that moment the service is just a thread parked in accept().
 */
class StreamService : Service() {
    private lateinit var settings: Settings
    private lateinit var locks: PowerLocks
    private lateinit var media: MediaController
    private lateinit var sessionManager: SessionManager
    private lateinit var certStore: CertStore

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "service-worker") }
        .apply { removeOnCancelPolicy = true }
    private val sessions = SessionStore()
    private val audit = AuditLog()

    private var server: WebServer? = null
    private var networkCallbackRegistered = false
    private var batteryReceiverRegistered = false

    @Volatile private var identity: TlsIdentity? = null
    @Volatile private var sslContext: SSLContext? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        locks = PowerLocks(this)
        media = MediaController(this) { settings.quality }
        certStore = CertStore(File(filesDir, "tls"))
        val scheduler = Scheduler { delayMs, task ->
            val future = worker.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            Cancellable { future.cancel(false) }
        }
        sessionManager = SessionManager(media, scheduler) { streaming, viewers ->
            mainHandler.post { onSessionChanged(streaming, viewers) }
        }
        sessionManager.admission = {
            !isBatteryTooLow(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }
        audit.onChange = { AppState.audit.value = audit.snapshot() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_listener), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settings.listenerEnabled = false
                stopSelf()
            }
            ACTION_PASSWORD_CHANGED -> {
                sessions.revokeAll()
                server?.closeWebSockets("password changed")
            }
            ACTION_SETTINGS_CHANGED -> if (server != null) locks.setStandbyMode(settings.standbyMode)
            else -> startListening()
        }
        if (server == null && intent?.action != null) stopSelf()
        // A restart by the system would come from the background, where camera access is refused anyway.
        return START_NOT_STICKY
    }

    private fun startListening() {
        if (!enterForeground()) {
            stopSelf()
            return
        }
        if (server != null) return
        settings.listenerEnabled = true
        locks.setStandbyMode(settings.standbyMode)
        worker.execute {
            try {
                refreshIdentity()
                val config = WebServerConfig(
                    port = settings.port,
                    sslContext = { sslContext!! },
                    certificateDer = { identity!!.certificate.encoded },
                    password = { settings.password },
                    trustProxyHeaders = { settings.trustProxyHeaders },
                    onConnectionAccepted = locks::onConnectionAccepted,
                )
                val assets = StaticAssets(AssetSource { name -> readAsset(name) })
                val started = WebServer(
                    config, assets, sessions, LoginRateLimiter(), audit, sessionManager::open,
                ) { message, error -> Log.w(TAG, message, error) }
                started.start()
                server = started
                publishStatus()
                mainHandler.post(::registerNetworkCallback)
            } catch (e: Exception) {
                Log.e(TAG, "listener failed to start", e)
                AppState.status.value = ServiceStatus(error = "Could not start listener: ${e.message}")
                mainHandler.post(::stopSelf)
            }
        }
    }

    /** Only the types whose runtime permission is granted may be requested, or Android 14+ throws. */
    @SuppressLint("InlinedApi") // The type constants are only handed to the platform on API 30+, below.
    private fun enterForeground(): Boolean {
        var types = 0
        if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA == 0) {
            AppState.status.value = ServiceStatus(error = "Camera permission is required")
            return false
        }
        return try {
            val notification = buildNotification(streaming = false, viewers = emptyList())
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not enter foreground", e)
            AppState.status.value = ServiceStatus(error = "Open the app to start the listener")
            false
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isBatteryTooLow(intent)) {
                sessionManager.disconnectAll(SessionManager.CLOSE_BATTERY_LOW, "device battery is low")
            }
        }
    }

    private fun isBatteryTooLow(battery: Intent?): Boolean {
        val threshold = settings.minBatteryPercent
        if (threshold <= 0 || battery == null) return false
        if (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return false
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return level >= 0 && scale > 0 && level * 100 / scale < threshold
    }

    /** The battery is only watched while streaming; its sticky broadcast answers admission checks on demand. */
    private fun watchBattery(watch: Boolean) {
        if (watch && !batteryReceiverRegistered && settings.minBatteryPercent > 0) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
        } else if (!watch && batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
    }

    private fun onSessionChanged(streaming: Boolean, viewers: List<String>) {
        locks.setStreaming(streaming)
        watchBattery(streaming)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(streaming, viewers))
        AppState.status.value = AppState.status.value.copy(streaming = streaming, viewers = viewers)
    }

    /** Runs on the worker: keeps the certificate's names in step with the device's current addresses. */
    private fun refreshIdentity() {
        val names = NetworkInfo.localAddresses() + settings.hostnames
        val current = certStore.loadOrCreate(names)
        if (current.fingerprint != identity?.fingerprint) {
            identity = current
            sslContext = TlsContextFactory.serverContext(current)
        }
    }

    private fun publishStatus() {
        val running = server ?: return
        val previous = AppState.status.value
        AppState.status.value = ServiceStatus(
            running = true,
            streaming = previous.streaming,
            viewers = previous.viewers,
            port = running.port,
            addresses = NetworkInfo.localAddresses(),
            fingerprint = identity?.fingerprint.orEmpty(),
        )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
    }

    private fun onNetworkChanged() {
        if (worker.isShutdown) return
        worker.execute {
            try {
                refreshIdentity()
                publishStatus()
            } catch (e: Exception) {
                Log.w(TAG, "network refresh failed", e)
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered || server == null) return
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
    }

    override fun onDestroy() {
        if (networkCallbackRegistered) {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        }
        audit.onChange = null
        watchBattery(false)
        val stopping = server
        server = null
        sessionManager.shutdown()
        media.release()
        worker.shutdownNow()
        // Closing TLS sockets writes to the network, which Android forbids on the main thread.
        thread(name = "service-stop") { stopping?.stop() }
        locks.releaseAll()
        val error = AppState.status.value.error
        AppState.status.value = ServiceStatus(error = error)
        super.onDestroy()
    }

    private fun buildNotification(streaming: Boolean, viewers: List<String>): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (streaming) {
            getString(R.string.notification_streaming, viewers.distinct().joinToString(", "))
        } else {
            getString(R.string.notification_standby, settings.port)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()
    }

    private fun readAsset(name: String): ByteArray? = try {
        assets.open("web/$name").use { it.readBytes() }
    } catch (_: IOException) {
        null
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "listener"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.aten.webcam.STOP"
        const val ACTION_PASSWORD_CHANGED = "dev.aten.webcam.PASSWORD_CHANGED"
        const val ACTION_SETTINGS_CHANGED = "dev.aten.webcam.SETTINGS_CHANGED"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StreamService::class.java))
        }

        /** Delivers [action] only when the service is already running; it must never be started by these. */
        fun notify(context: Context, action: String) {
            if (AppState.status.value.running) {
                context.startService(Intent(context, StreamService::class.java).setAction(action))
            }
        }
    }
}
