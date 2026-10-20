package app.orchard.tv.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.getSystemService
import app.orchard.tv.settings.AerialSettings

/**
 * Input automation around one chosen TV input (see AerialSettings.automationSourceId) — in
 * practice the HDMI port an Apple TV is permanently plugged into, on TVs whose own HDMI-CEC
 * power linking does not work (older Xiaomi sets are the known case):
 *
 * 1. Boot: once Aerial is up after a reboot, switch straight to that input.
 * 2. Signal appears: the input's TvInputManager state becomes CONNECTED — wake the screen if
 *    it is off and switch to the input, ahead of whatever is on screen.
 * 3. Signal lost: if the state stays away from CONNECTED for [SIGNAL_LOSS_POWER_OFF_MS] while
 *    that input is the one on screen, turn the TV off. The watching lives in HomeGuardService
 *    (it is the component that stays alive and can also press the power dialog); the stateless
 *    pieces live here.
 *
 * Everything is driven by TvInputManager input states, the only signal-presence signal a
 * non-system app can read: CONNECTED (device on and sending), CONNECTED_STANDBY (plugged in
 * but asleep) and DISCONNECTED. Whether a sleeping Apple TV shows up as the second or the
 * third is up to the TV's own input service, so both count as "signal gone". None of this can
 * run while the TV is fully off or in deep standby with Aerial's process killed — waking from
 * that still needs the TV's own CEC "power on with signal" setting, where it works.
 */
object InputAutomation {
    const val TAG = "AerialInput"

    /** How long the signal must be gone before the TV is turned off. */
    const val SIGNAL_LOSS_POWER_OFF_MS = 10_000L

    /** Boot switching is only attempted in the first minutes after a real reboot. */
    private const val BOOT_WINDOW_MS = 5 * 60 * 1000L
    private const val BOOT_DELAY_MS = 3_000L
    private val BOOT_RETRY_DELAYS_MS = longArrayOf(5_000L, 10_000L)

    private const val PREFS = "input_automation"
    private const val KEY_BOOT_SWITCHED_FOR = "boot_switched_for_boot_time"

    private val handler = Handler(Looper.getMainLooper())

    fun targetSource(context: Context): TvSource? {
        AerialSettings.init(context)
        val id = AerialSettings.automationSourceId
        if (id.isEmpty()) return null
        return SystemProbe.sources(context).firstOrNull { it.id == id }
    }

    /** Starts the chosen input's own player intent. False when there is no target or it vanished. */
    fun switchToTarget(context: Context, reason: String): Boolean {
        val source = targetSource(context) ?: return false
        return runCatching {
            context.startActivity(source.intent)
            Log.i(TAG, "switched to ${source.label} ($reason)")
            true
        }.getOrElse {
            Log.w(TAG, "could not switch to ${source.label}: $it")
            false
        }
    }

    /**
     * Boot switching, called from MainActivity.onCreate — every route that brings Aerial up
     * after a reboot (default Home, Home Guard, the boot receiver) funnels through there.
     * Deduped per boot through a persisted boot-time stamp, because Aerial's process is
     * force-stopped and revived on standby and must not switch again on every revival.
     */
    fun scheduleBootSwitch(context: Context) {
        AerialSettings.init(context)
        if (!AerialSettings.bootToSource || AerialSettings.automationSourceId.isEmpty()) return
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        val app = context.applicationContext
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (kotlin.math.abs(prefs.getLong(KEY_BOOT_SWITCHED_FOR, 0L) - bootTime) < BOOT_WINDOW_MS) return
        prefs.edit { putLong(KEY_BOOT_SWITCHED_FOR, bootTime) }
        handler.postDelayed({ bootSwitchAttempt(app, 0) }, BOOT_DELAY_MS)
    }

    private fun bootSwitchAttempt(context: Context, attempt: Int) {
        if (switchToTarget(context, "boot")) return
        // The TV input service may not have enumerated the inputs yet this soon after boot.
        val delay = BOOT_RETRY_DELAYS_MS.getOrNull(attempt) ?: return
        Log.i(TAG, "boot switch: target not ready, retrying in ${delay}ms")
        handler.postDelayed({ bootSwitchAttempt(context, attempt + 1) }, delay)
    }

    /** Lights the panel. A bright wake lock with ACQUIRE_CAUSES_WAKEUP is the pre-API-27 way, and the one TV firmwares honour. */
    fun wakeScreen(context: Context) {
        val pm = context.getSystemService<PowerManager>() ?: return
        if (pm.isInteractive) return
        runCatching {
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "aerial:signal-wake",
            )
            lock.acquire(5_000)
            Log.i(TAG, "woke the screen for the incoming signal")
        }.onFailure { Log.w(TAG, "wake lock failed: $it") }
    }

    /**
     * The direct route to standby. goToSleep needs DEVICE_POWER, which only system apps hold,
     * so on a normal install this throws and the caller falls back to the power dialog. It is
     * kept first because it is free to try and is the one route that works unchanged if a
     * firmware ever grants it.
     */
    fun tryGoToSleep(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return runCatching {
            PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
                .invoke(pm, SystemClock.uptimeMillis())
            Log.i(TAG, "goToSleep accepted")
            true
        }.getOrElse {
            Log.i(TAG, "goToSleep not available to a third-party app: ${it.cause ?: it}")
            false
        }
    }
}
