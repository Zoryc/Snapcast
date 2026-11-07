package app.snapcast.cast.proxy

import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.proxy.ProxyConfig
import app.snapcast.proxy.StreamProblem
import app.snapcast.proxy.StreamProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single [StreamProxy]. The server starts on demand ([proxy]) on the port from the settings and
 * is kept alive by references: the casting service holds one while casting, and the controller holds one
 * while it prepares a load. When the last reference goes, the server stops after a short grace period
 * (which also bridges the gap between a load and the service taking its reference). The stopped proxy is
 * kept, with its signing secret, port and registrations, and started again by [proxy] or [restartIfStopped]:
 * the URLs a receiver still holds then work again, so a cast that outlived its service can be saved. A port
 * change in the settings replaces it as soon as nothing is casting.
 */
@Singleton
internal class ProxyManager @Inject constructor(
    okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    // Shorter than the receiver's own patience (about 10 s for an answer): an upstream that hangs gets a quick
    // error, which the receiver retries on a new request and the proxy records, rather than a receiver timeout.
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val defaultUserAgent = UserAgents.geckoViewMobile(Build.VERSION.RELEASE)

    private val lock = Any()
    private var proxy: StreamProxy? = null
    private var proxyPort = -1
    private var references = 0
    private var stopJob: Job? = null
    private val activeCount = MutableStateFlow(0L)

    /**
     * Emits when a receiver uses the proxy again after a minute or more without (see StreamProxy), from the time
     * it is collected (no replay).
     */
    val receiverActive: Flow<Long> = activeCount.drop(1)

    init {
        scope.launch {
            settingsRepository.settings
                .map { it.proxyPort }
                .distinctUntilChanged()
                .collect { port -> withContext(Dispatchers.IO) { stopIfPortChanged(port) } }
        }
    }

    /**
     * A running proxy, started (or restarted with the current port setting when nothing holds it) on the IO
     * dispatcher. Throws [IOException] when the server cannot be started.
     */
    suspend fun proxy(): StreamProxy = withContext(Dispatchers.IO) {
        val port = settingsRepository.settings.value.proxyPort
        synchronized(lock) {
            val current = proxy
            if (current != null && (proxyPort == port || references > 0)) {
                if (!current.isRunning) current.start()
                return@synchronized current
            }
            current?.let { discardQuietly(it) }
            proxy = null
            val created = StreamProxy(
                client = client,
                config = ProxyConfig(port = port, defaultUserAgent = defaultUserAgent),
                log = ::log,
                onReceiverActive = { activeCount.update { it + 1 } },
                // Goes on in deep sleep (the receiver may play on for an hour with the screen off) and ignores
                // clock changes.
                activityClock = SystemClock::elapsedRealtime,
            )
            created.start()
            proxy = created
            proxyPort = port
            created
        }
    }

    /** Why the proxy could not serve one of [streamIds] just now, taken once (see [StreamProxy.takeRecentProblem]). */
    fun takeRecentProblem(withinMs: Long, streamIds: Collection<String>): StreamProblem? =
        if (streamIds.isEmpty()) null else synchronized(lock) { proxy }?.takeRecentProblem(withinMs, streamIds)

    /**
     * Milliseconds since a receiver last used the proxy (0 while it is answering one), or null when none did.
     * See [StreamProxy.idleMillis].
     */
    fun receiverIdleMillis(): Long? = synchronized(lock) { proxy }?.idleMillis()

    /**
     * Starts the kept proxy again if it was stopped (see the class comment); does nothing when there is none or it
     * runs. Called by the casting service when it starts, so a resumed cast is served again.
     */
    suspend fun restartIfStopped() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = proxy ?: return@synchronized
            if (current.isRunning) return@synchronized
            try {
                current.start()
            } catch (e: IOException) {
                Log.w(TAG, "Restarting the proxy failed", e)
            }
        }
    }

    /** Keeps the proxy alive until the matching [release]. */
    fun acquire() {
        synchronized(lock) {
            references++
            stopJob?.cancel()
            stopJob = null
        }
    }

    fun release() {
        synchronized(lock) {
            if (references == 0) return
            references--
            if (references > 0) return
            stopJob?.cancel()
            stopJob = scope.launch(Dispatchers.IO) {
                delay(STOP_GRACE_MS)
                synchronized(lock) { if (references == 0) stopLocked() }
            }
        }
    }

    private fun stopIfPortChanged(port: Int) {
        synchronized(lock) {
            val current = proxy ?: return
            if (references == 0 && proxyPort != port) {
                proxy = null
                proxyPort = -1
                discardQuietly(current)
            }
        }
    }

    /**
     * Stops the server but keeps the proxy (secret, port, registrations) to be started again, unless the port
     * setting changed while it was in use: then it is replaced the next time it is needed.
     */
    private fun stopLocked() {
        val current = proxy ?: return
        if (settingsRepository.settings.value.proxyPort != proxyPort) {
            proxy = null
            proxyPort = -1
            discardQuietly(current)
            return
        }
        try {
            current.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Stopping the proxy failed", e)
        }
    }

    private fun discardQuietly(proxy: StreamProxy) {
        try {
            proxy.clear()
            proxy.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Stopping the proxy failed", e)
        }
    }

    private fun log(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "SnapcastProxy"
        const val CONNECT_TIMEOUT_S = 8L
        const val READ_TIMEOUT_S = 20L
        const val STOP_GRACE_MS = 15_000L
    }
}
