package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * How the focused app shows itself, named after tvOS's Accessibility > Increase Contrast > Focus Style.
 *
 * tvOS's standard focus relies on the Siri Remote's touch surface: the icon tilts with the thumb, and
 * that motion is what the eye follows. With the D-pad remotes these TVs ship with, the icon only
 * grows, and owners lost track of it. High Contrast adds a white frame and is the default here.
 */
enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HighContrast
    }
}

/** Aerial's own preferences. Compose state, written on the main thread only. */
object AerialSettings {
    private const val PREFS = "settings"
    private var started = false
    private lateinit var app: Context

    var focusStyle by mutableStateOf(FocusStyle.HighContrast); private set

    /** TV inputs pinned to Home as tiles, by TvInputInfo id. */
    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The one input the input automation acts on (in practice the HDMI port an Apple TV lives
     * on), by TvInputInfo id. Empty means no target: every automation below stays off.
     */
    var automationSourceId by mutableStateOf(""); private set

    /** After the TV boots and Aerial is up, switch straight to [automationSourceId]. */
    var bootToSource by mutableStateOf(false); private set

    /** When [automationSourceId] starts sending a signal, wake the TV and switch to it. */
    var wakeOnSignal by mutableStateOf(false); private set

    /** When the signal on [automationSourceId] is lost for 10 s while it is on screen, turn the TV off. */
    var powerOffOnSignalLoss by mutableStateOf(false); private set

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        focusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        automationSourceId = prefs.getString("automation_source", null) ?: ""
        bootToSource = prefs.getBoolean("automation_boot", false)
        wakeOnSignal = prefs.getBoolean("automation_wake", false)
        powerOffOnSignalLoss = prefs.getBoolean("automation_power_off", false)
    }

    fun chooseAutomationSource(inputId: String) {
        automationSourceId = inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("automation_source", inputId) }
    }

    fun setBootToSource(on: Boolean) {
        bootToSource = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("automation_boot", on) }
    }

    fun setWakeOnSignal(on: Boolean) {
        wakeOnSignal = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("automation_wake", on) }
    }

    fun setPowerOffOnSignalLoss(on: Boolean) {
        powerOffOnSignalLoss = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("automation_power_off", on) }
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseFocusStyle(style: FocusStyle) {
        focusStyle = style
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("focus_style", style.key) }
    }
}
