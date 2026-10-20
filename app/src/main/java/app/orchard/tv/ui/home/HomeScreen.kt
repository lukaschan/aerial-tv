package app.orchard.tv.ui.home

import android.content.Intent
import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.control.ControlCenter
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlin.math.roundToInt

/** How many tiles fit across the screen; also the tile size the single row uses. */
private const val VISIBLE_TILES = 5

/**
 * Shelf geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas.
 * Home is a single row (a fork change: upstream wraps apps into rows of five): tiles keep
 * the size the five-across grid gave them, and the row slides sideways instead of the
 * screen scrolling down.
 */
private class GridMetrics(screenW: Dp, screenH: Dp) {
    val margin = 56.dp
    val gap = 28.dp
    val tileW = (screenW - margin * 2 - gap * (VISIBLE_TILES - 1)) / VISIBLE_TILES
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap

    /** The row sits a little past the middle, under the clock, as on tvOS. */
    val topRowY = screenH * 0.555f
    val screenW = screenW

    fun slot(index: Int) = Pair(margin + pitchX * index, topRowY)

    /** Sideways shift for [index]: resting position once the row has moved is the third slot. */
    fun scrollFor(index: Int, count: Int): Dp {
        if (index <= 0 || count <= 0) return 0.dp
        val wanted = pitchX * index - pitchX * 2
        val max = margin * 2 + pitchX * (count - 1) + tileW - screenW
        return wanted.coerceIn(0.dp, maxOf(max, 0.dp))
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onStartScreensaver: () -> Unit,
) {
    val apps = vm.apps
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val now by rememberNow()

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    var pillFocused by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val gridEnabled = overlay == Overlay.None

    fun focusApp(index: Int) {
        val app = apps.getOrNull(index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))) ?: return
        runCatching { requester(app.key).requestFocus() }
    }

    // First focus once the apps are in; afterwards, put focus back wherever it was when an overlay closes.
    LaunchedEffect(vm.loaded, overlay) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        val index = apps.indexOfFirst { it.key == focusedKey }
        if (pillFocused && focusedKey != null) runCatching { pillRequester.requestFocus() } else focusApp(if (index >= 0) index else 0)
    }
    // An app vanished from under the focus (uninstalled or hidden): take its neighbour.
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey } && overlay == Overlay.None) {
            withFrameNanos { }
            focusApp(lastFocusedIndex)
        }
        // While moving, focus follows the app to its new slot.
        moving?.let { withFrameNanos { }; runCatching { requester(it).requestFocus() } }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded) {
        if (windowFocused && vm.homeResetPending && vm.loaded) {
            withFrameNanos { }
            focusApp(0)
            vm.homeResetPending = false
        }
    }

    BackHandler(enabled = overlay == Overlay.None && moving == null) {
        // tvOS: Back on the top row starts the screensaver. The single row is always the top row.
        onStartScreensaver()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || moving != null) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        vm.overlay = if (overlay is Overlay.ControlCenter) Overlay.None else Overlay.ControlCenter()
                        true
                    }
                    NativeKeyEvent.KEYCODE_TV_INPUT -> {
                        vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val metrics = remember(maxWidth, maxHeight) { GridMetrics(maxWidth, maxHeight) }

        val scroll = remember { Animatable(0f) }
        val target = with(density) { metrics.scrollFor(focusedIndex.coerceAtLeast(0), apps.size).toPx() }
        LaunchedEffect(target) {
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 170f))
        }

        val backdropBlur by animateDpAsState(
            if (overlay is Overlay.ControlCenter || overlay is Overlay.HiddenApps) 30.dp else 0.dp,
            tween(320, easing = Motion.EaseOut), label = "backdrop-blur",
        )

        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val r = backdropBlur.toPx()
                    renderEffect = if (r > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(r, r, TileMode.Clamp) else null
                },
        ) {
            AmbientBackground(
                // The chosen wallpaper; in "Follow App" mode, light from the focused app.
                // The row slides sideways and never goes up under the clock, so no dimming.
                backdrop = Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                dim = { 0f },
            )

            Box(Modifier.fillMaxSize().offset { IntOffset(-scroll.value.roundToInt(), 0) }) {
                ShelfClock(
                    now = now,
                    modifier = Modifier
                        .padding(start = metrics.margin, top = metrics.topRowY - 196.dp),
                )

                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = metrics.slot(index)
                        val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                        val slot = animateIntOffsetAsState(
                            slotPx,
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                            label = "slot",
                        )
                        AppTile(
                            app = app,
                            width = metrics.tileW,
                            height = metrics.tileH,
                            focusRequester = requester(app.key),
                            enabled = gridEnabled,
                            moving = moving == app.key,
                            moveStride = 0,
                            appearDelayMs = (index * 28).coerceAtMost(320),
                            onFocused = {
                                focusedKey = app.key
                                lastFocusedIndex = index
                                pillFocused = false
                            },
                            onClick = { bounds -> onLaunch(app, bounds) },
                            onLongPress = { bounds ->
                                vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                            },
                            onMove = vm::moveBy,
                            onMoveDone = vm::endMove,
                            modifier = Modifier.offset { slot.value },
                        )
                    }
                }
            }
        }

        StatusPill(
            now = now,
            network = vm.network,
            // The big clock never collapses away now that the row cannot scroll up into it.
            showTime = false,
            enabled = gridEnabled && moving == null,
            focusRequester = pillRequester,
            onFocused = { gained ->
                // The window hands its first focus to the top-most control; the top row is where Home starts.
                if (gained) {
                    if (focusedKey == null) {
                        if (apps.isNotEmpty()) focusApp(0)
                    } else {
                        pillFocused = true
                    }
                }
            },
            onClick = { vm.overlay = Overlay.ControlCenter() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = metrics.margin - 14.dp)
                .graphicsLayer { alpha = if (overlay is Overlay.ControlCenter) 0f else 1f },
        )

        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            BasicText(
                stringResource(R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Glass)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        AppMenu(
            app = menu?.let { m -> apps.firstOrNull { it.key == m.key } },
            anchor = menu?.anchor,
            onMove = { vm.startMove(it) },
            onHide = { key ->
                vm.overlay = Overlay.None
                vm.hide(key)
            },
            onInfo = { vm.overlay = Overlay.None; onAppInfo(it) },
            onUninstall = { vm.overlay = Overlay.None; onUninstall(it) },
            onUnpin = { key ->
                vm.overlay = Overlay.None
                apps.firstOrNull { it.key == key }?.source?.let { vm.setSourcePinned(it, false) }
            },
            onDismiss = { vm.overlay = Overlay.None },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.None
            },
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
        )

        WallpaperSheet(
            visible = overlay is Overlay.Wallpaper,
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
            onStartScreensaver = {
                vm.overlay = Overlay.None
                onStartScreensaver()
            },
        )

        Screensaver(visible = vm.screensaverVisible, apps = apps, now = now, skips = vm.screensaverSkips)
    }
}
