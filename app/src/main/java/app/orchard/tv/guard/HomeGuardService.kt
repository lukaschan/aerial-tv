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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.settings.AerialSettings

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

    /** When the screen last lit, for take-overs that are really a wake (issue #111). */
    private var screenOnAt = Long.MIN_VALUE / 2

    private var takeOverReason = ""
    private var takeOverOnWake = false
    private val takeOver = Runnable { takeOverIfStockHomeInFront(takeOverReason, takeOverOnWake) }

    // ---- Power off when the boot input's device turns off ----

    private var inputCallback: TvInputManager.TvInputCallback? = null

    /** The watched input that lost its signal, and what was in front when it went. */
    private var signalLossInput: String? = null
    private var signalLossFront: Pair<String, String>? = null

    /** Set once an episode has fired, until the signal returns: no repeated power dialogs. */
    private var signalLossFired = false
    private val signalLossCheck = Runnable { powerOffIfSignalStillLost() }

>>>>>>> 3817646 (Power off with the device: when the boot input's CEC device turns off while it is being watched and no signal returns for 10s, turn the TV off (goToSleep, else the power dialog's standby entry). Toggle in Input Settings; the guard watches the boot input plus its CEC child inputs, whose state is the one that reports a sleeping device on MiTV-MTEQ0. ADB test hook: CONFIG broadcast --ez power_off true. versionCode 24, versionName 1.20-wakefix.2)
    /**
     * Watched inputs seen CONNECTED since the guard started. A loss only counts as an episode
     * after a connection: a device that was off all along (the TV was turned on while the box
     * sleeps, and the boot input opens its no-signal screen) must not turn the TV back off.
     */
    private val signalSeenConnected = HashSet<String>()

    /**
     * Bound before unlock (Direct Boot), the guard takes over as soon as credential storage is
     * available, from a process that is already running: on TCL 75S365C (Amlogic t982) starting a
     * process right after unlock waits several seconds behind TCL's boot-time scheduling (PR #45:
     * unlock to Aerial 7.6 s -> 0.9 s). It looks at what is in front first, like every take-over, so
     * an input that HDMI-CEC switched to at power-on is left alone.
     */
    private val userUnlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Before unlock only Direct Boot aware launchers resolve.
            refreshStockHomes()
            // The ADB and screensaver checks were skipped while locked.
            GuardSetup.ensureEnabled(this@HomeGuardService)
            val skipReason = when {
                SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS -> "outside boot window"
                getSystemService<PowerManager>()?.isInteractive != true -> "screen off"
                else -> null
            }
            if (skipReason == null) takeOverOnceSettled("user unlocked", woke = true)
            else Log.i(TAG, "user unlocked; not taking over Home ($skipReason)")
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Firmwares put their own launcher back in front while the TV is in standby, and no
            // window change follows on wake. Look once things have settled after the screen lights.
            screenOnAt = SystemClock.elapsedRealtime()
            takeOverOnceSettled("screen on", woke = true)
        }
    }

    private val adbObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.ensureAdb(this@HomeGuardService)
        }
    }

    override fun onServiceConnected() {
        instance = this
        // Register before querying homes or checking the state so an unlock cannot be missed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        refreshStockHomes()
        filterKeys(false)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        registerSignalCallback()
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        GuardSetup.ensureAdb(this)
        if (!GuardSetup.isUserUnlocked(this)) {
            Log.i(TAG, "bound while user locked; deferred takeover until unlock")
            return
        }
        // Bound a little after boot, or brought back after the firmware killed Aerial: either way
        // the stock launcher is what's in front, and the window change that showed it is long gone.
        // Not after an update, though: installing over Aerial also kills it, and whoever is watching
        // an input or another app at that moment must not be pulled back to Home.
        val revived = SystemClock.elapsedRealtime() - GuardSetup.lastRevivedAt < 60_000 && !justUpdated()
        // Aerial died with its home screen in front (it crashed, was updated, or was killed) and the
        // stock launcher was uncovered; pressing Home there changes nothing the guard could see
        // (TCL MT5879, after the screensaver crash of 1.14). Updates count too: Aerial was in front.
        val diedInFront = GuardSetup.wasHomeInFront(this) &&
            (Build.VERSION.SDK_INT < 24 || SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000)
        // Revived with the screen off (standby): the screen-on check takes over at wake.
        // Not gated on being the default Home: Skyworth/Coolita (8R145, Android 11) starts its own
        // launcher at boot whatever Home resolves to (issue #43). The take-over looks first, and
        // bringing Aerial back over itself changes nothing.
        if ((SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || revived || diedInFront) &&
            getSystemService<PowerManager>()?.isInteractive == true
        ) {
            takeOverOnceSettled(
                when {
                    revived -> "re-enabled after being switched off"
                    diedInFront -> "back after Aerial died in front"
                    else -> "bound shortly after boot"
                },
                // Back from standby or a boot, not from a crash: the moment to open the input
                // the owner wants the TV to start on.
                woke = !diedInFront || revived,
            )
        }
    }

    /**
     * Takes Home back after a short wait, and only if nothing but the stock launcher came to the
     * front meanwhile. At power-on an HDMI-CEC source (an Apple TV) switches the TV to its input
     * just as the screen lights and the guard is bound again; taking Home at once bounced the
     * viewer straight back out of the input (reported by a user, reproduced on Xiaomi MiTV-MFTR0:
     * the input's ExternalSourceActivity started 130 ms before the guard was bound).
     */
    private fun takeOverOnceSettled(reason: String, woke: Boolean) {
        takeOverReason = reason
        takeOverOnWake = woke
        handler.removeCallbacks(takeOver)
        handler.postDelayed(takeOver, SETTLE_MS)
    }

    private fun hasBootSource(): Boolean = runCatching {
        AerialSettings.init(this)
        AerialSettings.bootSource != null
    }.getOrDefault(false)

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    /**
     * Also when Aerial is the default Home: that only steers the HOME intent. Xiaomi MiTV-ANSP0
     * (Android 9) accepts set-home-activity, yet when standby force-stops Aerial, PatchWall's old
     * task is simply uncovered and stays in front after wake (issue #6).
     */
    private fun takeOverIfStockHomeInFront(reason: String, woke: Boolean) {
        if (!GuardSetup.isUserUnlocked(this)) return
        val last = lastWindow
        // Unknown means we were only just bound, which happens after a standby kill. Any screen of
        // the stock launcher counts: when Aerial is killed, whatever was on top of the launcher's
        // task is uncovered, not always its home activity (PatchWall's StrategicLayerActivity).
        // Unless the firmware keeps stopping Aerial: then an unknown front is most likely the app
        // the viewer is in, and Home stays with the Home key (LeTV eUI, issue #63).
        if (last == null && GuardSetup.revivedOften(this)) {
            Log.i(TAG, "$reason, but Aerial keeps being stopped and the front is unknown; leaving it")
            return
        }
        val stockInFront = last == null || last.first in stockHomes
        // Aerial is in front after a wake because the guard already brought it back when the stock
        // launcher showed, or because it survived standby; either way the input the TV starts on
        // still has to open. Only with one chosen: bringing Aerial back also resets its focus.
        // Xiaomi MiTV-MTEQ0, where the guard is revived after the screen lights (issue #111).
        if (woke && last?.first == packageName && hasBootSource()) {
            Log.i(TAG, "$reason with Aerial itself in front; flagging the wake")
            goHome(woke = true)
            return
        }
        if (!stockInFront && last != null) Log.i(TAG, "$reason with ${last.first} in front; leaving it there")
        if (!stockInFront || SystemClock.uptimeMillis() < allowStockUntil) return
        Log.i(TAG, "$reason with the stock launcher in front; bringing Aerial back")
        goHome(woke)
    }

    override fun onDestroy() {
        inputCallback?.let { callback ->
            runCatching { getSystemService<TvInputManager>()?.unregisterCallback(callback) }
        }
        inputCallback = null
        runCatching { unregisterReceiver(screenOn) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { unregisterReceiver(userUnlocked) }
        }
        runCatching { contentResolver.unregisterContentObserver(adbObserver) }
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    /**
     * Filters keys only while the viewer is in the stock launcher on purpose; see the class comment.
     * Up to Android 11 always: there, once Home reaches the system, it holds every background
     * activity start for five seconds, so Aerial came back only after five seconds of the TCL
     * launcher (TCL MT5879, since 1.11 stopped filtering by default).
     */
    private fun filterKeys(on: Boolean) {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val want = if (!keysPassThrough && (on || Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)) info.flags or flag else info.flags and flag.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
        Log.i(TAG, if (want and flag != 0) "filtering keys" else "stopped filtering keys")
    }

    /**
     * Hisense VIDAA handles volume in its own firmware, which never sees keys that went through an
     * accessibility filter: with the filter on, the volume keys did nothing anywhere, though this
     * service passes them on (VIDAA_TV, RTD2851D, Android 10, issue #7). Home is no help there
     * either: VIDAA starts its launcher by component, or Aerial through persist.vt.use_home_pkg.
     */
    private val keysPassThrough by lazy {
        VIDAA_PACKAGES.any { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GuardSetup.isUserUnlocked(this)) return false
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
        // Only activities count as what is in front. Overlays and dialogs also raise this event:
        // TCL's voice assistant (com.tcl.walleve) does at boot on TCL 75S365C, and taking it for
        // an app the viewer chose kept the guard from taking over (PR #45).
        if (isActivity(pkg, cls)) lastWindow = pkg to cls
        if (!GuardSetup.isUserUnlocked(this)) return
        val allowed = SystemClock.uptimeMillis() < allowStockUntil
        if (pkg in stockHomes) {
            filterKeys(allowed)
            // Only the stock launcher's home activity itself, not its dialogs or other screens.
            // Not gated on isDefaultHome(): see takeOverIfStockHomeInFront.
            if (cls !in stockHomes.getValue(pkg) || allowed) return
            Log.i(TAG, "stock home $pkg came to the front; bringing Aerial back")
            // Shortly after the screen lit, this is the wake itself (issue #111).
            goHome(woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS)
            return
        }
        filterKeys(false)
        // Leaving the stock launcher for a real app ends the visit: Home goes to Aerial again.
        if (allowed && pkg != packageName && isApp(pkg)) allowStockUntil = 0L
    }

    private val activityCache = HashMap<Pair<String, String>, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut(pkg to cls) {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
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
        val standard = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        // Coocaa's launcher (Skyworth 9H89_G91 and 9S60_G60, Android 8) declares only private
        // categories such as android.intent.category.HOME.CC; the firmware starts it by component
        // (issues #69, #73). Every MAIN activity is listed so their filters can be read.
        val vendor = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN), PackageManager.GET_RESOLVED_FILTER)
                .filter { ri -> ri.filter?.categoriesIterator()?.asSequence()?.any { it.startsWith(VENDOR_HOME_PREFIX) } == true }
        }.getOrDefault(emptyList())
        stockHomes = (standard + vendor)
            // Negative priority entries are fallbacks shown only while nothing else can be Home.
            .filter { it.activityInfo.packageName != packageName && it.priority >= 0 }
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.name })
            .mapValues { it.value.toSet() }
    }

    private fun registerSignalCallback() {
        val tim = getSystemService<TvInputManager>() ?: return
        val callback = object : TvInputManager.TvInputCallback() {
            override fun onInputStateChanged(inputId: String, state: Int) =
                onInputSignalChanged(inputId, state)
        }
        runCatching { tim.registerCallback(callback, handler) }
            .onSuccess {
                inputCallback = callback
                // Seed the seen-connected set with the inputs' current states: the callback
                // only reports changes, so a device already on when the guard started (e.g.
                // woken with the TV over CEC) would never be "seen connected", and its first
                // sleep would be mistaken for a device that was off all along. CEC discovery
                // lags a cold boot by tens of seconds (on-device test, 2026-10-22: the child
                // input was not yet CONNECTED at registration), so seed again shortly after.
                seedSeenConnected(tim)
                handler.postDelayed({ seedSeenConnected(tim) }, 20_000)
                handler.postDelayed({ seedSeenConnected(tim) }, 60_000)
            }
            .onFailure { Log.w(TAG, "could not watch input states", it) }
    }

    /** Mark watched inputs that are connected right now as seen connected (see registerSignalCallback). */
    private fun seedSeenConnected(tim: TvInputManager) {
        val already = watchedInputs(tim).filter {
            runCatching { tim.getInputState(it) }.getOrNull() ==
                TvInputManager.INPUT_STATE_CONNECTED
        }
        if (already.isNotEmpty() && !signalSeenConnected.containsAll(already)) {
            signalSeenConnected.addAll(already)
            Log.i(TAG, "signal watcher: ${already.size} watched input(s) already connected")
        }
    }

    /** The signal-loss settings live in AerialSettings' file; read fresh, the guard outlives the UI. */
    private fun signalLossEnabled(): Boolean =
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("power_off_on_signal_loss", false)

    private fun bootSourceId(): String? =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getString("boot_source", null)

    /**
     * The boot input plus the CEC device inputs registered as its children. A sleeping device is
     * reported on its CEC child, not on the port: on MiTV-MTEQ0 the physical HW input stays
     * CONNECTED while the Apple TV sleeps, and only the child drops to CONNECTED_STANDBY
     * (sampled on the device, 2026-10-21).
     */
    private fun watchedInputs(tim: TvInputManager): Set<String> {
        val boot = bootSourceId() ?: return emptySet()
        val infos = runCatching { tim.tvInputList }.getOrNull() ?: return setOf(boot)
        return infos.filter { it.id == boot || it.parentId == boot }.mapTo(HashSet()) { it.id }
    }

    private fun onInputSignalChanged(inputId: String, state: Int) {
        if (!GuardSetup.isUserUnlocked(this)) return
        val tim = getSystemService<TvInputManager>() ?: return
        if (inputId !in watchedInputs(tim)) return
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            signalSeenConnected += inputId
            if (signalLossInput == inputId) {
                Log.i(TAG, "signal back on $inputId; cancelling the power-off")
                clearSignalLoss()
            }
            signalLossFired = false
            return
        }
        if (inputId !in signalSeenConnected) {
            Log.i(TAG, "no signal on $inputId, but it was never seen connected; not counting it")
            return
        }
        // Anything but CONNECTED counts as no signal; firmwares disagree on STANDBY vs DISCONNECTED.
        if (!signalLossEnabled() || signalLossFired) return
        if (signalLossInput == inputId) return // already counting this episode
        val front = lastWindow
        Log.i(TAG, "signal lost on $inputId (state $state) with ${front?.first ?: "nothing known"} in front")
        // Only when the viewer is sitting on a source player: in Aerial or the stock launcher the
        // device going to sleep in the background is normal and must not take the TV down.
        if (front == null || front.first == packageName || front.first in stockHomes) return
        signalLossInput = inputId
        signalLossFront = front
        handler.removeCallbacks(signalLossCheck)
        handler.postDelayed(signalLossCheck, SIGNAL_LOSS_POWER_OFF_MS)
        Log.i(TAG, "turning the TV off in ${SIGNAL_LOSS_POWER_OFF_MS / 1000}s unless the signal returns")
    }

    private fun clearSignalLoss() {
        signalLossInput = null
        signalLossFront = null
        handler.removeCallbacks(signalLossCheck)
    }

    private fun powerOffIfSignalStillLost() {
        val inputId = signalLossInput ?: return
        val frontAtLoss = signalLossFront
        clearSignalLoss()
        if (!signalLossEnabled()) return
        val tim = getSystemService<TvInputManager>() ?: return
        val state = runCatching { tim.getInputState(inputId) }
            .getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            Log.i(TAG, "signal is back on $inputId; not turning off")
            return
        }
        if (getSystemService<PowerManager>()?.isInteractive != true) {
            Log.i(TAG, "screen already off; not turning off")
            return
        }
        if (lastWindow != frontAtLoss) {
            Log.i(TAG, "the viewer moved on to ${lastWindow?.first}; not turning off")
            return
        }
        signalLossFired = true
        powerOffNow("no signal on $inputId for ${SIGNAL_LOSS_POWER_OFF_MS / 1000}s")
    }

    /** Turns the TV off the ways an app can: direct sleep first, else the power dialog. */
    private fun powerOffNow(reason: String) {
        Log.i(TAG, "turning the TV off: $reason")
        // Direct sleep needs DEVICE_POWER, which a retail app cannot hold; expected to be refused.
        val slept = runCatching {
            val pm = getSystemService<PowerManager>() ?: error("no PowerManager")
            pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType)
                .invoke(pm, SystemClock.uptimeMillis())
            true
        }.getOrElse {
            Log.i(TAG, "goToSleep refused (${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}); opening the power dialog")
            false
        }
        if (slept) return
        if (!performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)) {
            Log.w(TAG, "the power dialog did not open")
            return
        }
        handler.postDelayed({ tapPowerDialogEntry() }, POWER_DIALOG_SETTLE_MS)
    }

    /** Taps the standby entry of the power dialog; logs every entry when none matches, for the record. */
    private fun tapPowerDialogEntry(attempt: Int = 0) {
        val root = rootInActiveWindow
        if (root == null) {
            // The dialog can take a moment to draw (and to become the active window).
            if (attempt < 3) {
                handler.postDelayed({ tapPowerDialogEntry(attempt + 1) }, POWER_DIALOG_SETTLE_MS)
            } else {
                Log.w(TAG, "power dialog: no active window to read")
            }
            return
        }
        val texts = ArrayList<String>()
        val labelled = ArrayList<AccessibilityNodeInfo>()
        walkNodes(root) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                texts += text
                labelled += node
            }
        }
        for (label in POWER_OFF_LABELS) {
            val node = labelled.firstOrNull { it.text?.toString()?.trim() == label } ?: continue
            val target = clickableSelfOrParent(node) ?: node
            val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "power dialog: tapped \"$label\" (clicked=$clicked); entries were $texts")
            return
        }
        Log.w(TAG, "power dialog: no standby entry found; entries were $texts")
    }

    private fun walkNodes(node: AccessibilityNodeInfo, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 12) return
        visit(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walkNodes(it, depth + 1, visit) }
        }
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isClickable == true) return current
            current = current?.parent
        }
        return null
    }

    /**
     * Brings Aerial's one task to the front. No CATEGORY_HOME: on firmwares where Aerial is not the
     * system Home, that category made the system open a second, home-type task with a fresh
     * MainActivity every time, which lost the focused tile (Xiaomi MiTV-MFTR0, 1.11).
     */
    private fun goHome(woke: Boolean = false) {
        if (!GuardSetup.isUserUnlocked(this)) return
        // Starting an activity can light the screen: on OPPO K9 the TV came back on by itself a few
        // seconds into standby (issue #22). The screen-on check takes over at wake instead.
        if (getSystemService<PowerManager>()?.isInteractive == false) {
            Log.i(TAG, "screen off; leaving Home to the screen-on check")
            return
        }
        runCatching {
            startActivity(MainActivity.bringBackIntent(this).putExtra(MainActivity.EXTRA_WOKE, woke))
        }.onFailure { Log.w(TAG, "could not bring Aerial Home to the front", it) }
    }

    companion object {
        private const val TAG = "AerialGuard"
        private const val BOOT_WINDOW_MS = 3 * 60 * 1000L
        private const val SETTLE_MS = 1500L
        private const val WAKE_WINDOW_MS = 15_000L

        /** VIDAA's assistant and its launcher (聚好看), on the two VIDAA builds reported (issues #7, #40). */
        private val VIDAA_PACKAGES = listOf("com.hisense.vidaaassistant", "com.jamdeo.tv.vod")

        /** How long the boot input's signal must stay away before the TV turns itself off. */
        private const val SIGNAL_LOSS_POWER_OFF_MS = 10_000L
        /** The power dialog needs a moment to draw before its entries can be read and tapped. */
        private const val POWER_DIALOG_SETTLE_MS = 900L
        /** Power dialog entries that put the TV to sleep, most CEC-like first. */
        private val POWER_OFF_LABELS = listOf("待机", "睡眠", "Standby", "Sleep", "Power off", "关机")
        private const val VENDOR_HOME_PREFIX = "android.intent.category.HOME."

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
            allowStockLauncher()
            return s.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** Lets the stock launcher stay in front until Aerial is back: the viewer opened it on purpose. */
        fun allowStockLauncher() {
            instance?.allowStockUntil = SystemClock.uptimeMillis() + 60 * 60 * 1000L
        }

        /**
         * Turns the TV off now, the way the signal-loss watcher does. Backs the ADB test hook in
         * ConfigReceiver (`--ez power_off true`). False when the guard is not running.
         */
        fun powerOffNow(reason: String): Boolean {
            val s = instance ?: return false
            s.handler.post { s.powerOffNow(reason) }
            return true
        }

        /** Called when Aerial comes back to the front: the stock launcher is fair game again. */
        fun onAerialResumed() {
            val s = instance ?: return
            s.allowStockUntil = 0L
            s.filterKeys(false)
        }
    }
}
