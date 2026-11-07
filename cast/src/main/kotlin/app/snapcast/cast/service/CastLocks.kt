package app.snapcast.cast.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Wi-Fi and CPU locks held while the receiver plays, so the proxy keeps serving it with the screen off
 * ([WakeLockPolicy] decides when). Not reference counted; safe to acquire and release repeatedly.
 */
internal class CastLocks(context: Context) : ServiceLocks {
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(wifiLockMode(), LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private val wakeLock: PowerManager.WakeLock? = runCatching {
        context.applicationContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private var wakeLockAcquiredAt = 0L

    override fun acquire() {
        runCatching { wifiLock?.takeUnless { it.isHeld }?.acquire() }
        acquireWakeLock()
    }

    /**
     * Extends the wake lock's timeout while playback continues (it is time-limited so a lost release cannot
     * drain the battery). Cheap to call often: it only renews once half of the timeout has passed.
     */
    fun refresh() {
        val lock = wakeLock ?: return
        val renewDue = SystemClock.elapsedRealtime() - wakeLockAcquiredAt > WAKE_LOCK_TIMEOUT_MS / 2
        if (lock.isHeld && renewDue) acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLockAcquiredAt = SystemClock.elapsedRealtime()
        }
    }

    override fun release() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    private companion object {
        const val LOCK_TAG = "Snapcast:Casting"
        const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        /**
         * HIGH_PERF keeps Wi-Fi out of power save with the screen off and the app in the background (casting's
         * case) up to Android 13; from Android 14 it does nothing. LOW_LATENCY only applies while the screen is
         * on and the app is in the foreground, so it is used where nothing else works (and costs nothing with
         * the screen off).
         */
        fun wifiLockMode(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
    }
}
