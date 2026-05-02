package com.muxy.app.ui.terminal

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.muxy.app.data.PaneSession
import com.muxy.app.data.SessionRepository
import com.muxy.app.model.PaneOwner
import com.muxy.app.ui.theme.MuxyTheme
import kotlinx.coroutines.launch

private const val FONT_PATH = "fonts/JetBrainsMonoNerdFontMono-Regular.ttf"
private const val FONT_PATH_BOLD = "fonts/JetBrainsMonoNerdFontMono-Bold.ttf"

/**
 * Top-level terminal pane. Owns the AndroidView that renders the emulator and
 * captures IME/hardware input. Shows a take-over overlay when another client
 * (Mac or another remote) currently owns this pane.
 */
@Composable
fun TerminalView(
    paneID: String,
    session: SessionRepository,
    modifier: Modifier = Modifier,
) {
    val theme by session.deviceTheme.collectAsState()
    val palette = MuxyTheme.from(theme)
    val owners by session.paneOwners.collectAsState()
    val myID by session.myClientID.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val regular = remember { Typeface.createFromAsset(context.assets, FONT_PATH) }
    val bold = remember { Typeface.createFromAsset(context.assets, FONT_PATH_BOLD) }
    val fontPx = with(density) { 13.sp.toPx() }

    val accessory = remember { AccessoryState() }
    var pane by remember { mutableStateOf<PaneSession?>(null) }
    @OptIn(ExperimentalLayoutApi::class)
    val keyboardVisible = WindowInsets.isImeVisible
    var canCopy by remember { mutableStateOf(false) }
    val surfaceRef = remember { Ref<TerminalSurfaceView>() }

    // Mirrors iOS's `reportedCols` / `reportedRows`: surface fills these in
    // once it has actually laid out, and we use them to drive takeOverPane.
    var measuredCols by remember(paneID) { mutableStateOf<Int?>(null) }
    var measuredRows by remember(paneID) { mutableStateOf<Int?>(null) }
    // Mirrors iOS's `autoTakenPaneID`: ensures the auto-takeover fires exactly
    // once per (re)appearance for a given paneID. After that, the user must
    // tap Take Over manually — we don't fight the Mac on every owner change.
    var autoTakenPaneID by remember { mutableStateOf<String?>(null) }

    val owner = owners[paneID]
    val isOwnedBySelf = remember(owner, myID) {
        val mine = myID
        owner is PaneOwner.Remote && mine != null && owner.deviceID == mine
    }

    // Pane lifecycle: open on enter, release on leave/paneID change.
    DisposableEffect(paneID) {
        // Use a 2x2 sentinel so the emulator can be constructed, but the real
        // takeOverPane is deferred until `measuredCols`/`measuredRows` arrive
        // from the surface. iOS does the same — `attemptAutoTakeOver` no-ops
        // until `reportedCols` is set.
        val opened = session.openPane(paneID, 2, 2)
        pane = opened
        autoTakenPaneID = null
        onDispose {
            session.closePane(paneID)
            pane = null
            measuredCols = null
            measuredRows = null
        }
    }

    // Re-apply theme to the emulator when it changes mid-session.
    LaunchedEffect(theme, pane) {
        val p = pane ?: return@LaunchedEffect
        val t = theme ?: return@LaunchedEffect
        p.applyTheme(t.fg, t.bg, t.palette)
    }

    // First measurement → first takeOverPane. Mirrors iOS's
    // `attemptAutoTakeOver(reportedCols, reportedRows)`:
    //   - cols/rows must be known
    //   - guard against firing twice for the same paneID
    LaunchedEffect(paneID, pane, measuredCols, measuredRows) {
        val p = pane ?: return@LaunchedEffect
        val c = measuredCols ?: return@LaunchedEffect
        val r = measuredRows ?: return@LaunchedEffect
        if (autoTakenPaneID == paneID) return@LaunchedEffect
        autoTakenPaneID = paneID
        p.takeOver(c, r)
    }

    Column(
        modifier
            .background(palette.background)
            .imePadding(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Surface
            AndroidView(
                factory = { ctx ->
                    TerminalSurfaceView(ctx).apply {
                        typefaceRegular = regular
                        typefaceBold = bold
                        fontSizePx = fontPx
                        modifierProvider = { accessory.consume() }
                        onSelectionChanged = { canCopy = it }
                        onMeasured = { c, r ->
                            measuredCols = c
                            measuredRows = r
                        }
                        surfaceRef.value = this
                    }
                },
                update = { v ->
                    v.pane = pane
                    v.invalidate()
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Redraw whenever the emulator buffer changes.
            val tickFlow = pane?.tick
            val tick by (tickFlow?.collectAsState() ?: remember { mutableStateOf(0L) })
            LaunchedEffect(tick) { surfaceRef.value?.invalidate() }

            // Auto-show keyboard when the surface attaches.
            LaunchedEffect(pane) {
                surfaceRef.value?.showSoftKeyboard()
            }

            if (!isOwnedBySelf && owner != null) {
                TakeOverOverlay(
                    ownerName = owner.displayName,
                    foreground = palette.foreground,
                    background = palette.background,
                    onTakeOver = {
                        val p = pane ?: return@TakeOverOverlay
                        val c = measuredCols ?: return@TakeOverOverlay
                        val r = measuredRows ?: return@TakeOverOverlay
                        scope.launch { p.takeOver(c, r) }
                    },
                )
            }
        }

        AccessoryBar(
            state = accessory,
            foreground = palette.foreground,
            background = palette.background,
            keyboardVisible = keyboardVisible,
            onSendBytes = { bytes -> pane?.sendBytes(bytes) },
            onPaste = {
                pasteFromClipboardText(context)?.let { surfaceRef.value?.pasteText(it) }
            },
            onCopy = {
                val v = surfaceRef.value ?: return@AccessoryBar
                v.selectedText()?.let { copyToClipboard(context, it) }
                v.clearSelection()
            },
            canCopy = canCopy,
            onToggleKeyboard = {
                val v = surfaceRef.value ?: return@AccessoryBar
                if (keyboardVisible) v.hideSoftKeyboard() else v.showSoftKeyboard()
            },
        )
    }
}

@Composable
private fun TakeOverOverlay(
    ownerName: String,
    foreground: Color,
    background: Color,
    onTakeOver: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(background.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(foreground.copy(alpha = 0.08f))
                .border(width = 1.dp, color = foreground.copy(alpha = 0.2f), shape = RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.Computer, contentDescription = null, tint = foreground, modifier = Modifier.size(28.dp))
            Text("Controlled on $ownerName", color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "This terminal is currently being used on $ownerName. Take over to control it from here.",
                color = foreground.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(foreground)
                    .clickable(onClick = onTakeOver)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text("Take Over", color = background, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

private class Ref<T> {
    var value: T? = null
}
