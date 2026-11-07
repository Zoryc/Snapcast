package app.snapcast.cast.proxy

import android.os.Build
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * (which also bridges the gap between a load and the service taking its reference). A port change in the
 * settings restarts the server the next time it is needed, as soon as nothing is casting.
 */
@Singleton
internal class ProxyManager @Inject constructor(
    okHttpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val defaultUserAgent = UserAgents.geckoViewMobile(Build.VERSION.RELEASE)

    private val lock = Any()
    private var proxy: StreamProxy? = null
    private var proxyPort = -1
    private var references = 0
    private var stopJob: Job? = null

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
            if (current != null && current.isRunning && (proxyPort == port || references > 0)) {
                return@synchronized current
            }
            current?.let { stopQuietly(it) }
            proxy = null
            val created = StreamProxy(
                client = client,
                config = ProxyConfig(port = port, defaultUserAgent = defaultUserAgent),
                log = ::log,
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
     * Milliseconds since a receiver last used the proxy (0 while it is answering one), or null when none did or
     * the proxy is stopped. See [StreamProxy.idleMillis].
     */
    fun receiverIdleMillis(): Long? = synchronized(lock) { proxy }?.idleMillis()

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
            if (references == 0 && proxy != null && proxyPort != port) stopLocked()
        }
    }

    private fun stopLocked() {
        val current = proxy ?: return
        proxy = null
        proxyPort = -1
        stopQuietly(current)
    }

    private fun stopQuietly(proxy: StreamProxy) {
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
        const val READ_TIMEOUT_S = 60L
        const val STOP_GRACE_MS = 15_000L
    }
}
