package com.example.truenasapp.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.InputDevice
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/** Phone-sized, small-tablet / unfolded, or big tablet / desktop. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

data class LayoutInfo(
    val width: WidthClass = WidthClass.COMPACT,
    /** Samsung DeX, Android desktop windows, or a big screen with a mouse attached. */
    val isDesktop: Boolean = false,
    /** A book-style foldable whose fold physically splits the screen into left/right halves. */
    val foldSplitsScreen: Boolean = false,
    val hingeWidth: Dp = 0.dp,
)

val LocalLayoutInfo = staticCompositionLocalOf { LayoutInfo() }

/** Re-evaluated whenever the window is resized, rotated, folded or unfolded. */
@Composable
fun rememberLayoutInfo(activity: Activity): LayoutInfo {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val windowLayout by remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
        .collectAsStateWithLifecycle<WindowLayoutInfo?>(initialValue = null)

    val widthDp = config.screenWidthDp
    val width = when {
        widthDp >= 840 -> WidthClass.EXPANDED
        widthDp >= 600 -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
    val deskMode = (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK
    val mouse = remember(config) { hasMouse() }

    val fold = windowLayout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
    val splits = fold != null && fold.isSeparating && fold.orientation == FoldingFeature.Orientation.VERTICAL
    val hinge = if (splits && fold != null) with(density) { fold.bounds.width().toDp() } else 0.dp

    return LayoutInfo(
        width = width,
        isDesktop = deskMode || (mouse && width == WidthClass.EXPANDED),
        foldSplitsScreen = splits,
        hingeWidth = hinge,
    )
}

private fun hasMouse(): Boolean = InputDevice.getDeviceIds().any { id ->
    val d = InputDevice.getDevice(id)
    d != null && !d.isVirtual && d.supportsSource(InputDevice.SOURCE_MOUSE)
}

/** Cards side by side when there's room; exactly two columns either side of a foldable's hinge. */
@Composable
fun adaptiveGridCells(minCardWidth: Dp = 340.dp): GridCells {
    val layout = LocalLayoutInfo.current
    return if (layout.foldSplitsScreen) GridCells.Fixed(2) else GridCells.Adaptive(minCardWidth)
}

/** Gap between grid columns; on a split foldable it's at least as wide as the hinge. */
@Composable
fun gridGap(): Dp = max(12.dp, LocalLayoutInfo.current.hingeWidth)

/** Keeps forms and settings a comfortable reading width on tablets and desktops, centred. */
fun Modifier.readableWidth(maxWidth: Dp = 720.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxWidth).fillMaxWidth()
