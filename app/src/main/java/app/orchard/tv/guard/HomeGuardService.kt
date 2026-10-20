package app.orchard.tv.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.InputAutomation

/**
 * Keeps Aerial as the home screen on firmwares that will not let a third-party app own HOME
 * (Xiaomi PatchWall, some TCL and Hisense builds), without disabling any system app:
 *
 * 1. When the stock launcher's home activity comes to the front (the Home key, an app exiting to
 *    it, the TV booting into it), Aerial is brought back on top.
 * 2. The viewer can still open the stock launcher on purpose (its tile in Aerial's grid, for the
 *    maker's own content). While it is in front the Home key is taken before the system sees it
 *    and answered with Aerial, since pressing Home there would otherwise just stay put.
 *
 * Key filtering is on only in that second case. With it on, every key press on the TV detours
 * through this service; some apps then see a press as held and move twice (云视听小电视 on Xiaomi
 * MiTV-MFTR0, reported by a user and gone once Aerial was uninstalled).
 *
 * Both only act while Aerial is not the resolved HOME app; once it is, the system does the work.
 * The service reads window-change events only; it never reads screen content.
 */
class HomeGuardService : AccessibilityService() {

    /** HOME activities that are not ours: package to class names. Refreshed on every connect. */
    private var stockHomes: Map<String, Set<String>> = emptyMap()

    /** Set when the user opens the stock launcher on purpose from Aerial's grid. */
    private var allowStockUntil = 0L

    /** The last window that came to the front, as package to class; null until one does. */
    private var lastWindow: Pair<String, String>? = null

    private val handler = Handler(Looper.getMainLooper())

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Firmwares put their own launcher back in front while the TV is in standby, and no
            // window change follows on wake. Look once things have settled after the screen lights.
            handler.postDelayed({ takeOverIfStockHomeInFront("screen on") }, 1200)
        }
    }

    private val adbObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.ensureAdb(this@HomeGuardService)
        }
    }

    // ---- Input automation: watch the chosen input's signal (see InputAutomation) ----

    /** Last TvInputManager state per input id, snapshotted when the callback is registered. */
    private val inputStates = HashMap<String, Int>()

    private var signalLossCheck: Runnable? = null

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) {
            handler.post { onInputState(inputId, state) }
        }
    }

    private fun watchInputs() {
        val tim = getSystemService<TvInputManager>() ?: return
        runCatching {
            tim.tvInputList.forEach { info ->
                inputStates[info.id] = runCatching { tim.getInputState(info.id) }.getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
            }
            tim.registerCallback(inputCallback, handler)
        }.onFailure { Log.w(InputAutomation.TAG, "could not watch input states: $it") }
    }

    private fun onInputState(inputId: String, state: Int) {
        val prev = inputStates.put(inputId, state)
        // No previous value means this input was only just enumerated; that is the snapshot
        // settling, not a transition, and must not wake or switch anything.
        if (prev == null || prev == state) return
        AerialSettings.init(this)
        if (inputId != AerialSettings.automationSourceId) return
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            cancelSignalLossCheck()
            if (!AerialSettings.wakeOnSignal || isViewingTarget()) return
            val wasInteractive = getSystemService<PowerManager>()?.isInteractive == true
            Log.i(InputAutomation.TAG, "signal appeared on the watched input (was $prev, screen ${if (wasInteractive) "on" else "off"})")
            InputAutomation.wakeScreen(this)
            // A panel that was off needs a moment before the player can come to the front.
            handler.postDelayed(
                { InputAutomation.switchToTarget(this, "signal appeared") },
                if (wasInteractive) 0L else 900L,
            )
        } else if (prev == TvInputManager.INPUT_STATE_CONNECTED) {
            // CONNECTED_STANDBY and DISCONNECTED both mean the picture is gone; which one a
            // sleeping source reports is the firmware's choice (see InputAutomation).
            if (!AerialSettings.powerOffOnSignalLoss) return
            Log.i(InputAutomation.TAG, "signal lost on the watched input; turning off in ${InputAutomation.SIGNAL_LOSS_POWER_OFF_MS / 1000}s unless it returns")
            cancelSignalLossCheck()
            val check = Runnable {
                signalLossCheck = null
                val now = inputStates[inputId]
                if (now == TvInputManager.INPUT_STATE_CONNECTED) return@Runnable
                if (getSystemService<PowerManager>()?.isInteractive != true) return@Runnable
                if (!isViewingTarget()) {
                    Log.i(InputAutomation.TAG, "signal still gone, but the watched input is not on screen; leaving the TV on")
                    return@Runnable
                }
                Log.i(InputAutomation.TAG, "signal gone for ${InputAutomation.SIGNAL_LOSS_POWER_OFF_MS / 1000}s on the input being watched; turning the TV off")
                powerOff()
            }
            signalLossCheck = check
            handler.postDelayed(check, InputAutomation.SIGNAL_LOSS_POWER_OFF_MS)
        }
    }

    private fun cancelSignalLossCheck() {
        signalLossCheck?.let { handler.removeCallbacks(it) }
        signalLossCheck = null
    }

    /** True while the foreground window belongs to the player that shows the watched input. */
    private fun isViewingTarget(): Boolean {
        val pkg = InputAutomation.targetSource(this)?.intent?.component?.packageName ?: return false
        return lastWindow?.first == pkg
    }

    /**
     * Turns the TV off without a system permission: the direct goToSleep first, then the
     * system power dialog (a global accessibility action), choosing its Standby entry when it
     * has one and Power off otherwise — the same choice the remote's power key makes.
     */
    private fun powerOff() {
        if (InputAutomation.tryGoToSleep(this)) return
        val shown = runCatching { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }.getOrDefault(false)
        if (!shown) {
            Log.w(InputAutomation.TAG, "power off failed: goToSleep refused and the power dialog would not open")
            return
        }
        handler.postDelayed({ clickPowerOffEntry(attempt = 0) }, 800)
    }

    private fun clickPowerOffEntry(attempt: Int) {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val entry = root?.let { findPowerOffEntry(it) }
        if (entry == null) {
            // The dialog can be slow to lay out on these firmwares; look once more, then give up
            // and leave the dialog for the viewer rather than clicking around blindly.
            if (attempt == 0) handler.postDelayed({ clickPowerOffEntry(attempt = 1) }, 1_200)
            else Log.w(InputAutomation.TAG, "power dialog is open but no standby / power-off entry was found in it")
            return
        }
        var node: AccessibilityNodeInfo? = entry
        while (node != null && !node.isClickable) node = node.parent
        val clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        Log.i(InputAutomation.TAG, if (clicked) "chose '${entry.text ?: entry.contentDescription}' in the power dialog" else "found the power entry but could not click it")
    }

    private fun findPowerOffEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Standby is what the remote's power key does on these TVs; a full power off is the
        // fallback. Search in that order, over the whole dialog each time.
        for (pattern in POWER_OFF_PATTERNS) {
            var hit: AccessibilityNodeInfo? = null
            fun walk(node: AccessibilityNodeInfo) {
                if (hit != null) return
                val label = (node.text ?: node.contentDescription)?.toString()
                if (label != null && pattern.containsMatchIn(label)) { hit = node; return }
                for (i in 0 until node.childCount) { node.getChild(i)?.let { walk(it) } }
            }
            walk(root)
            if (hit != null) return hit
        }
        return null
    }

    override fun onServiceConnected() {
        instance = this
        refreshStockHomes()
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        GuardSetup.ensureAdb(this)
        AerialSettings.init(this)
        watchInputs()
        // Bound a little after boot, or brought back after the firmware killed Aerial: either way
        // the stock launcher is what's in front, and the window change that showed it is long gone.
        // Not after an update, though: installing over Aerial also kills it, and whoever is watching
        // an input or another app at that moment must not be pulled back to Home.
        val revived = SystemClock.elapsedRealtime() - GuardSetup.lastRevivedAt < 60_000 && !justUpdated()
        // Revived with the screen off (standby): the screen-on check takes over at wake.
        if ((SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && !isDefaultHome() || revived) &&
            getSystemService<PowerManager>()?.isInteractive == true
        ) {
            Log.i(TAG, if (revived) "re-enabled after being switched off; taking over Home" else "bound shortly after boot; taking over Home")
            goHome()
        }
    }

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    /**
     * Also when Aerial is the default Home: that only steers the HOME intent. Xiaomi MiTV-ANSP0
     * (Android 9) accepts set-home-activity, yet when standby force-stops Aerial, PatchWall's old
     * task is simply uncovered and stays in front after wake (issue #6).
     */
    private fun takeOverIfStockHomeInFront(reason: String) {
        val last = lastWindow
        // Unknown means we were only just bound, which happens after a standby kill.
        val stockInFront = last == null || last.second in (stockHomes[last.first] ?: emptySet())
        if (!stockInFront || SystemClock.uptimeMillis() < allowStockUntil) return
        Log.i(TAG, "$reason with the stock launcher in front; bringing Aerial back")
        goHome()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        runCatching { contentResolver.unregisterContentObserver(adbObserver) }
        runCatching { getSystemService<TvInputManager>()?.unregisterCallback(inputCallback) }
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    /** Filters keys only while the viewer is in the stock launcher on purpose; see the class comment. */
    private fun filterKeys(on: Boolean) {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val want = if (on) info.flags or flag else info.flags and flag.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
        Log.i(TAG, if (on) "filtering keys while the stock launcher is open" else "stopped filtering keys")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (isDefaultHome()) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            Log.i(TAG, "Home key taken before the system saw it")
            goHome()
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        lastWindow = pkg to cls
        val allowed = SystemClock.uptimeMillis() < allowStockUntil
        if (pkg in stockHomes) {
            filterKeys(allowed)
            // Only the stock launcher's home activity itself, not its dialogs or other screens.
            // Not gated on isDefaultHome(): see takeOverIfStockHomeInFront.
            if (cls !in stockHomes.getValue(pkg) || allowed) return
            Log.i(TAG, "stock home $pkg came to the front; bringing Aerial back")
            goHome()
            return
        }
        filterKeys(false)
        // Leaving the stock launcher for a real app ends the visit: Home goes to Aerial again.
        if (allowed && pkg != packageName && isApp(pkg)) allowStockUntil = 0L
    }

    /** True for packages with a launcher entry, i.e. apps rather than system overlays and dialogs. */
    private fun isApp(pkg: String): Boolean =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null

    private fun isDefaultHome(): Boolean =
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == packageName

    private fun refreshStockHomes() {
        stockHomes = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
            // Negative priority entries are fallbacks shown only while nothing else can be Home.
            .filter { it.activityInfo.packageName != packageName && it.priority >= 0 }
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.name })
            .mapValues { it.value.toSet() }
    }

    /**
     * Brings Aerial's one task to the front. No CATEGORY_HOME: on firmwares where Aerial is not the
     * system Home, that category made the system open a second, home-type task with a fresh
     * MainActivity every time, which lost the focused tile (Xiaomi MiTV-MFTR0, 1.11).
     */
    private fun goHome() {
        startActivity(MainActivity.bringBackIntent(this))
    }

    companion object {
        private const val TAG = "AerialGuard"
        private const val BOOT_WINDOW_MS = 3 * 60 * 1000L

        /** Power dialog entries, in the order they should be chosen (see powerOff). */
        private val POWER_OFF_PATTERNS = listOf(
            Regex("待机|Standby|Sleep", RegexOption.IGNORE_CASE),
            Regex("关机|关电视|Power ?off|Shut ?down|Turn ?off", RegexOption.IGNORE_CASE),
        )

        @Volatile
        private var instance: HomeGuardService? = null

        val isRunning: Boolean get() = instance != null

        /** True if [packageName] is a stock launcher the guard keeps away from the front. */
        fun isStockHome(packageName: String): Boolean = instance?.stockHomes?.containsKey(packageName) == true

        /**
         * Opens the stock launcher on purpose (its tile in Aerial's grid). Xiaomi's launcher
         * activities require a signature permission (com.mitv.tvhome.permission.HOME_STATE), so an
         * app cannot start them; the system Home action can. Returns false if the guard is not running.
         */
        fun openStockLauncher(): Boolean {
            val s = instance ?: return false
            s.allowStockUntil = SystemClock.uptimeMillis() + 60 * 60 * 1000L
            return s.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** Called when Aerial comes back to the front: the stock launcher is fair game again. */
        fun onAerialResumed() {
            val s = instance ?: return
            s.allowStockUntil = 0L
            s.filterKeys(false)
        }
    }
}
