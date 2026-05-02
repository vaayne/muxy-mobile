package com.muxy.app.ui.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.muxy.app.data.PaneSession
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Custom Android View that hosts the Termux terminal renderer and bridges Android
 * IME / keyboard input into PTY bytes that we send via PaneSession.
 *
 * Why a real Android View instead of pure Compose Canvas:
 *  - Compose's onKeyEvent does not receive IME (soft keyboard) events at all.
 *    Soft keyboards send InputConnection commits/composing text, not KeyEvents.
 *  - SwiftTerm on iOS conforms to UIKeyInput which is the equivalent IME hook.
 *    To match behavior we need onCreateInputConnection on the Android side too.
 */
class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pane: PaneSession? = null
    var modifierProvider: () -> AccessoryModifier? = { null }

    var fontSizePx: Float = context.resources.displayMetrics.scaledDensity * 13f
        set(value) {
            field = value
            measurePaint.textSize = value
            recomputeCellSize()
            requestLayout()
            invalidate()
        }

    var typefaceRegular: Typeface = Typeface.MONOSPACE
        set(value) {
            field = value
            measurePaint.typeface = value
            recomputeCellSize()
            invalidate()
        }
    var typefaceBold: Typeface = Typeface.MONOSPACE

    private val measurePaint = Paint().apply {
        textSize = fontSizePx
        typeface = typefaceRegular
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textSize = fontSizePx
        isAntiAlias = true
    }
    private val bgPaint = Paint()

    private var cellWidth: Float = 0f
    private var cellHeight: Float = 0f
    private var cellAscent: Float = 0f

    private var cols: Int = 80
    private var rows: Int = 24

    /** Rows above the visible top of the screen, drawn from the transcript. 0 = stuck to bottom. */
    private var scrollbackOffset: Int = 0
    private var scrollAccumulator: Float = 0f

    /** Active text selection in screen-space columns/rows. Null when no selection. */
    private var selStartCol: Int = -1
    private var selStartRow: Int = -1
    private var selEndCol: Int = -1
    private var selEndRow: Int = -1
    val hasSelection: Boolean
        get() = selStartCol >= 0 && (selStartCol != selEndCol || selStartRow != selEndRow)

    /** Listener notified when the user makes/clears a selection. */
    var onSelectionChanged: (Boolean) -> Unit = {}

    fun selectedText(): String? {
        if (!hasSelection) return null
        val em = pane?.emulator ?: return null
        synchronized(em) {
            val sel = normalizedSelection()
            return em.getSelectedText(sel[0], sel[1] - scrollbackOffset, sel[2], sel[3] - scrollbackOffset)
        }
    }

    fun clearSelection() {
        if (selStartCol < 0) return
        selStartCol = -1
        selStartRow = -1
        selEndCol = -1
        selEndRow = -1
        onSelectionChanged(false)
        invalidate()
    }

    /** Use the emulator's bracketed-paste-aware paste so vim/etc. handle it. */
    fun pasteText(text: String) {
        synchronized(pane?.emulator ?: return) {
            pane!!.emulator.paste(text)
        }
    }

    private fun normalizedSelection(): IntArray {
        val startsFirst = selStartRow < selEndRow ||
            (selStartRow == selEndRow && selStartCol <= selEndCol)
        val x1 = if (startsFirst) selStartCol else selEndCol
        val y1 = if (startsFirst) selStartRow else selEndRow
        val x2 = if (startsFirst) selEndCol else selStartCol
        val y2 = if (startsFirst) selEndRow else selStartRow
        return intArrayOf(x1, y1, x2, y2)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        recomputeCellSize()
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (hasSelection) { clearSelection(); return true }
            showSoftKeyboard()
            performClick()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val cell = cellAt(e.x, e.y) ?: return
            selStartCol = cell.first; selStartRow = cell.second
            selEndCol = cell.first; selEndRow = cell.second
            onSelectionChanged(true)
            invalidate()
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val em = pane?.emulator ?: return false
            // Mouse-tracking apps (htop, vim with mouse, less): emit wheel events.
            if (em.isMouseTrackingActive) {
                scrollAccumulator += distanceY
                val ticks = (scrollAccumulator / cellHeight).toInt()
                if (ticks != 0) {
                    scrollAccumulator -= ticks * cellHeight
                    val button = if (ticks > 0) 4 else 5
                    val col = em.mColumns / 2
                    val row = em.mRows / 2
                    repeat(kotlin.math.abs(ticks)) {
                        synchronized(em) { em.sendMouseEvent(button, col, row, false) }
                    }
                }
                return true
            }
            // Otherwise scroll through the on-device transcript.
            val deltaRows = distanceY / cellHeight
            scrollAccumulator += deltaRows
            val rowsToScroll = scrollAccumulator.toInt()
            if (rowsToScroll != 0) {
                scrollAccumulator -= rowsToScroll
                val maxOffset = em.screen?.activeTranscriptRows ?: 0
                scrollbackOffset = (scrollbackOffset + rowsToScroll).coerceIn(0, maxOffset)
                invalidate()
            }
            return true
        }
    })

    private fun recomputeCellSize() {
        cellWidth = measurePaint.measureText("M")
        val fm = measurePaint.fontMetrics
        cellHeight = ceil(fm.descent - fm.ascent).coerceAtLeast(1f)
        cellAscent = -fm.ascent
        textPaint.textSize = fontSizePx
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cellWidth <= 0 || cellHeight <= 0) return
        val newCols = floor(w / cellWidth).toInt().coerceAtLeast(2)
        val newRows = floor(h / cellHeight).toInt().coerceAtLeast(2)
        if (newCols != cols || newRows != rows) {
            cols = newCols
            rows = newRows
            pane?.resize(newCols, newRows)
        }
    }

    fun showSoftKeyboard() {
        if (!isFocused) requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    // --- IME bridge ---

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL keeps hardware-keyboard key events flowing through onKeyDown
        // with their modifier metadata intact (Ctrl/Alt/arrows). With a typed
        // input class, Android can route printable keys through commitText()
        // instead, stripping modifiers — which breaks Ctrl-C, Ctrl-D, etc.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, true)
    }

    /** Called by TerminalInputConnection when text is committed by IME. */
    fun commitText(text: CharSequence) {
        if (text.isEmpty()) return
        val pane = pane ?: return
        val modifier = modifierProvider()
        val transformed = if (modifier != null) {
            applyModifierToText(text.toString(), modifier)
        } else text.toString()
        pane.sendBytes(transformed.toByteArray(Charsets.UTF_8))
    }

    /** Called when IME requests delete-before-cursor. */
    fun deleteBeforeCursor(): Boolean {
        // BS character — terminal usually handles it as backspace
        pane?.sendBytes(byteArrayOf(0x7F))
        return true
    }

    /** Called when IME or hardware keyboard sends a non-text key (Enter, Tab, arrows, etc.) */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val pane = pane ?: return false
        var modifiers = 0
        if (event.isCtrlPressed) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT

        val em = pane.emulator
        val cursorApp = em.isCursorKeysApplicationMode
        val keypadApp = em.isKeypadApplicationMode
        val seq = KeyHandler.getCode(keyCode, modifiers, cursorApp, keypadApp)
        if (seq != null) {
            pane.sendBytes(seq.toByteArray(Charsets.UTF_8))
            return true
        }
        val unicode = event.getUnicodeChar(event.metaState)
        if (unicode > 0) {
            val ch = unicode.toChar()
            val accessory = modifierProvider()
            val payload: ByteArray = if (accessory != null) {
                applyModifierToText(ch.toString(), accessory).toByteArray(Charsets.UTF_8)
            } else if (modifiers and KeyHandler.KEYMOD_CTRL != 0) {
                ctrlByte(ch)
            } else {
                ch.toString().toByteArray(Charsets.UTF_8)
            }
            val withAlt = if (modifiers and KeyHandler.KEYMOD_ALT != 0) byteArrayOf(0x1B) + payload else payload
            pane.sendBytes(withAlt)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Selection drag: when there's an active selection, finger movement extends it.
        if (hasSelection && event.action == MotionEvent.ACTION_MOVE) {
            val cell = cellAt(event.x, event.y) ?: return true
            if (cell.first != selEndCol || cell.second != selEndRow) {
                selEndCol = cell.first; selEndRow = cell.second
                invalidate()
            }
            return true
        }
        if (event.action == MotionEvent.ACTION_DOWN) scrollAccumulator = 0f
        return gestureDetector.onTouchEvent(event)
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (cellWidth <= 0 || cellHeight <= 0) return null
        val col = (x / cellWidth).toInt().coerceIn(0, (cols - 1).coerceAtLeast(0))
        val row = (y / cellHeight).toInt().coerceIn(0, (rows - 1).coerceAtLeast(0))
        return col to row
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        val em = pane?.emulator ?: return
        synchronized(em) {
            val screen = em.screen ?: return
            val palette = em.mColors.mCurrentColors
            val defaultFg = palette[TextStyle.COLOR_INDEX_FOREGROUND]
            val defaultBg = palette[TextStyle.COLOR_INDEX_BACKGROUND]

            bgPaint.color = defaultBg
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val rowsToDraw = em.mRows
            val colsToDraw = em.mColumns

            val sel = if (hasSelection) normalizedSelection() else intArrayOf(-1, -1, -1, -1)
            val sx1 = sel[0]; val sy1 = sel[1]; val sx2 = sel[2]; val sy2 = sel[3]

            for (y in 0 until rowsToDraw) {
                val externalRow = y - scrollbackOffset
                val internal = screen.externalToInternalRow(externalRow)
                val row = screen.allocateFullLineIfNecessary(internal)
                val text = row.mText
                val space = row.spaceUsed
                var col = 0
                var i = 0
                while (col < colsToDraw && i < space) {
                    val style = row.getStyle(col)
                    val fgIdx = TextStyle.decodeForeColor(style)
                    val bgIdx = TextStyle.decodeBackColor(style)
                    val effects = TextStyle.decodeEffect(style)
                    val isBold = (effects and TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0
                    val isInverse = (effects and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0
                    val isUnderline = (effects and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0

                    var fg = resolveColor(fgIdx, palette, defaultFg)
                    var bg = resolveColor(bgIdx, palette, defaultBg)
                    if (isInverse) { val tmp = fg; fg = bg; bg = tmp }

                    val cellX = col * cellWidth
                    val cellY = y * cellHeight

                    val inSelection = hasSelection && cellInRange(col, y, sx1, sy1, sx2, sy2)
                    if (inSelection) {
                        bgPaint.color = (0x60FFFFFF.toInt())
                        canvas.drawRect(cellX, cellY, cellX + cellWidth, cellY + cellHeight, bgPaint)
                    } else if (bg != defaultBg) {
                        bgPaint.color = bg
                        canvas.drawRect(cellX, cellY, cellX + cellWidth, cellY + cellHeight, bgPaint)
                    }
                    val cp = text[i].code
                    if (cp > 0 && cp != ' '.code) {
                        textPaint.color = fg
                        textPaint.typeface = if (isBold) typefaceBold else typefaceRegular
                        canvas.drawText(text, i, 1, cellX, cellY + cellAscent, textPaint)
                    }
                    if (isUnderline) {
                        textPaint.color = fg
                        canvas.drawRect(cellX, cellY + cellHeight - 1.5f, cellX + cellWidth, cellY + cellHeight, textPaint)
                    }
                    col++
                    i++
                }
            }

            if (em.shouldCursorBeVisible() && scrollbackOffset == 0) {
                val cx = em.cursorCol * cellWidth
                val cy = em.cursorRow * cellHeight
                bgPaint.color = palette[TextStyle.COLOR_INDEX_CURSOR]
                bgPaint.alpha = 160
                canvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, bgPaint)
                bgPaint.alpha = 255
            }
        }
    }

    private fun cellInRange(col: Int, row: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (row < y1 || row > y2) return false
        if (row == y1 && row == y2) return col in x1..x2
        if (row == y1) return col >= x1
        if (row == y2) return col <= x2
        return true
    }

    private fun resolveColor(value: Int, palette: IntArray, fallback: Int): Int {
        // TextStyle.decodeFore/BackColor returns either a small palette index
        // (0..258) or a 24-bit truecolor with alpha=0xFF set. The truecolor form
        // appears as a negative signed int (high bit set).
        if (value and 0xFF000000.toInt() == 0xFF000000.toInt()) return value
        if (value in 0 until palette.size) return palette[value]
        return fallback
    }
}

private fun ctrlByte(ch: Char): ByteArray {
    val lc = Character.toLowerCase(ch).code
    return if (lc in 'a'.code..'z'.code) byteArrayOf(((lc - 'a'.code + 1) and 0x1F).toByte())
    else byteArrayOf(ch.code.toByte())
}

private fun applyModifierToText(text: String, modifier: AccessoryModifier): String {
    return when (modifier) {
        AccessoryModifier.CTRL -> {
            if (text.length != 1) return text
            val ch = text[0]
            val v = ch.code
            when {
                v in 0x40..0x5F -> (v - 0x40).toChar().toString()
                v in 0x61..0x7A -> (v - 0x60).toChar().toString()
                v == 0x20 -> " "
                else -> text
            }
        }
        AccessoryModifier.SHIFT -> text.uppercase()
        AccessoryModifier.ALT -> "\u001B" + text
        AccessoryModifier.CMD -> text
    }
}

/**
 * Minimal InputConnection: just forwards committed text to commitText() and
 * delete-before-cursor to deleteBeforeCursor(). We intentionally never store an
 * editable; the terminal *is* the editor.
 */
private class TerminalInputConnection(
    private val view: TerminalSurfaceView,
    fullEditor: Boolean,
) : BaseInputConnection(view, fullEditor) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null) view.commitText(text)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // We don't display composing text; commit immediately.
        if (text != null) view.commitText(text)
        return true
    }

    override fun finishComposingText(): Boolean = true

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceAtLeast(0)) { view.deleteBeforeCursor() }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            view.onKeyDown(event.keyCode, event)
            return true
        }
        return super.sendKeyEvent(event)
    }
}
