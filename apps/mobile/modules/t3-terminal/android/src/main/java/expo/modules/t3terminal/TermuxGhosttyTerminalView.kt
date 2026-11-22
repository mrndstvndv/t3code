package expo.modules.t3terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSessionIO
import com.termux.terminal.compose.TerminalComposeView
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.session.TerminalSessionBackend
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

private const val TRANSCRIPT_ROWS = 4000
private const val FEED_CHUNK_SIZE = 16 * 1024
private const val MIN_GRID_DIMENSION = 4
private const val FONT_SIZE_PILL_HIDE_DELAY_MS = 900L
private const val FONT_SIZE_PILL_FADE_MS = 150L
private const val FONT_SIZE_PILL_ALPHA = 0xE6
private val OPENABLE_URL_SCHEMES = setOf("http", "https", "mailto")

/**
 * Fork-owned terminal surface rendered by termux-ghostty's compose canvas. Same props and
 * events as [T3TerminalView], plus pinch-to-zoom reported through `onFontSizeChange`.
 */
@Suppress("TooManyFunctions")
class TermuxGhosttyTerminalView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  // Compose adds its inner AndroidComposeView and GlesTerminalTextureView after attach and drives
  // size updates through requestLayout(); without this, Fabric swallows those passes.
  override val shouldUseAndroidLayout: Boolean = true

  private val container = FrameLayout(context)
  private val terminalView = TerminalComposeView(context)
  private val fontSizePill = TextView(context)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var needsLayoutOnAttach = false

  private val onInput by EventDispatcher()
  private val onResize by EventDispatcher()
  private val onCapture by EventDispatcher()
  private val onPaste by EventDispatcher()
  private val onFontSizeChange by EventDispatcher()

  // appendOutput blocks while the session's input queue is full, so bytes reach it off the main thread,
  // in order. A session generation lets queued feeds for a replaced session drop out.
  private val feedExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "t3-terminal-feed").apply { isDaemon = true }
  }
  private val sessionGeneration = AtomicLong(0)
  private var session: TerminalSession? = null
  private var backend: TerminalSessionBackend? = null
  private var sessionReady = false

  @Volatile private var isCleanedUp = false
  private var streamedOutput: TerminalOutputBuffer? = null
  private var fedBuffer = ""
  private var hasPendingBuffer = false
  private var measuredCols = 0
  private var measuredRows = 0
  private var pinchedFontSizeSp: Float? = null
  private val minFontPx: Int
  private val maxFontPx: Int

  private var backgroundColorValue = Color.parseColor("#24292E")
  private var foregroundColorValue = Color.parseColor("#D1D5DA")
  private var cursorColorValue = Color.parseColor("#009FFF")

  private val hideFontSizePill = Runnable {
    fontSizePill.animate().alpha(0f).setDuration(FONT_SIZE_PILL_FADE_MS).withEndAction {
      fontSizePill.visibility = View.GONE
    }
  }
  private val autoFocusRunnable = Runnable {
    if (!isCleanedUp && autoFocus && !readOnly) {
      terminalView.requestTerminalFocus()
      terminalView.showSoftKeyboard()
    }
  }

  var terminalKey: String = ""
    set(value) {
      if (field == value) return
      val hadKey = field.isNotEmpty()
      field = value
      contentDescription = "t3-terminal-$value"
      if (hadKey) {
        streamedOutput = null
        recreateSession()
      }
    }

  // Expo sets a batch's props in no fixed order; the buffer is fed once the batch lands.
  var initialBuffer: String = ""
    set(value) {
      if (field == value) return
      field = value
      hasPendingBuffer = true
    }

  var fontSize: Float = 10f
    set(value) {
      field = value
      applyFontSize()
    }

  var appearanceScheme: String = "dark"

  // The PTY grid shared by every attached client, or empty before the server reports one.
  // The canvas is sized to draw this grid, clamped to the view's own fit; onResize still
  // reports the view's own fit.
  var sharedGrid: List<Int> = emptyList()
    set(value) {
      if (field == value) return
      field = value
      layoutTerminalChildren()
    }

  var themeConfig: String = ""
    set(value) {
      field = value
      applyTheme()
    }

  var focusRequest: Double = 0.0
    set(value) {
      val previous = field
      field = value
      if (value != previous && value > 0 && !readOnly) {
        terminalView.requestTerminalFocus()
        terminalView.showSoftKeyboard()
      }
    }

  var readOnly: Boolean = false
    set(value) {
      field = value
      terminalView.setAutoShowKeyboardOnTap(!value)
      terminalView.setUnconditionalKeyboardOnTap(!value)
      if (value) terminalView.hideSoftKeyboard()
    }

  var autoFocus: Boolean = true
    set(value) {
      field = value
      removeCallbacks(autoFocusRunnable)
      if (value) {
        // Before attachment, post() runs once the view is attached.
        post(autoFocusRunnable)
      } else {
        terminalView.hideSoftKeyboard()
      }
    }

  var backgroundColorHex: String = "#24292E"
    set(value) {
      field = value
      backgroundColorValue = parseHexColor(value) ?: backgroundColorValue
      applyTheme()
    }

  var foregroundColorHex: String = "#D1D5DA"
    set(value) {
      field = value
      foregroundColorValue = parseHexColor(value) ?: foregroundColorValue
      applyTheme()
    }

  var mutedForegroundColorHex: String = "#959DA5"

  var captureRequest: Double = 0.0
    set(value) {
      if (field == value || value <= 0) return
      field = value
      val text = terminalView.getText().toString().lines().joinToString("\n") { it.trimEnd() }
      onCapture(mapOf("text" to text))
    }

  private val scaledDensity: Float
    get() = resources.displayMetrics.density * resources.configuration.fontScale

  private val terminalListener = object : TerminalComposeView.Listener {
    override fun onOpenUrl(url: String) {
      val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
      if (uri.scheme?.lowercase() !in OPENABLE_URL_SCHEMES) return
      runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    }

    override fun onCopyRequest(selectedText: String) {
      clipboard()?.setPrimaryClip(ClipData.newPlainText("terminal", selectedText))
    }

    override fun onPasteRequest() {
      if (readOnly) return
      val text = clipboard()?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
      if (!text.isNullOrEmpty()) onPaste(mapOf("text" to text))
    }

    override fun onFontSizeChanged(fontSize: Int) {
      val snapped = fontSizePxToSnappedSp(fontSize, scaledDensity)
      pinchedFontSizeSp = snapped
      showFontSizePill(snapped)
      onFontSizeChange(mapOf("fontSize" to snapped.toDouble()))
      onGeometryChanged()
    }

    override fun onKeyDown(event: KeyEvent): Boolean = readOnly

    override fun onCodePoint(codePoint: Int, controlDown: Boolean, altDown: Boolean): Boolean = readOnly
  }

  init {
    TerminalTypefaces.ensureLoaded(context)
    terminalView.setTypeface(TerminalTypefaces.regular)
    minFontPx = (MIN_TERMINAL_FONT_SIZE_SP * scaledDensity).roundToInt().coerceAtLeast(1)
    maxFontPx = (MAX_TERMINAL_FONT_SIZE_SP * scaledDensity).roundToInt().coerceAtLeast(minFontPx)
    terminalView.setFontSizeBounds(minFontPx, maxFontPx)
    terminalView.setFontSize(spToFontSizePx(fontSize, scaledDensity, minFontPx, maxFontPx))
    terminalView.listener = terminalListener

    configureFontSizePill()
    container.addView(
      terminalView,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    container.addView(
      fontSizePill,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
      ),
    )
    addView(container, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    applyTheme()
    createSession()
  }

  // Fabric does not run Android layout passes for children of a custom view, so this view places them itself.
  // Skip measuring/laying out TerminalComposeView while detached: AbstractComposeView needs a
  // ViewTreeLifecycleOwner on first composition, so defer until onAttachedToWindow().
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    if (!isAttachedToWindow) {
      needsLayoutOnAttach = true
      setMeasuredDimension(
        MeasureSpec.getSize(widthMeasureSpec),
        MeasureSpec.getSize(heightMeasureSpec),
      )
      return
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    measureTerminalChildren(measuredWidth, measuredHeight)
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    if (!isAttachedToWindow) {
      needsLayoutOnAttach = true
      return
    }
    container.layout(0, 0, right - left, bottom - top)
    placeTerminalChildren()
    if (changed || measuredCols == 0) onGeometryChanged()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (needsLayoutOnAttach) {
      needsLayoutOnAttach = false
      post { requestLayout() }
    }
  }

  fun writeOutput(data: String, reset: Boolean) {
    if (isCleanedUp) return
    val bytes = data.toByteArray(Charsets.UTF_8)
    val output = streamedOutput ?: TerminalOutputBuffer()
    output.write(bytes, reset)
    streamedOutput = output
    if (reset) {
      recreateSession()
    } else if (sessionReady) {
      feed(bytes)
    }
  }

  fun resetInput() {
    terminalView.resetInput()
  }

  fun applyPendingBuffer() {
    if (!hasPendingBuffer) return
    hasPendingBuffer = false
    feedPendingBuffer()
  }

  fun cleanup() {
    if (isCleanedUp) return
    isCleanedUp = true
    mainHandler.removeCallbacksAndMessages(null)
    removeCallbacks(autoFocusRunnable)
    removeCallbacks(hideFontSizePill)
    terminalView.listener = null
    destroySession()
    feedExecutor.shutdownNow()
  }

  private fun createSession() {
    if (isCleanedUp) return
    val generation = sessionGeneration.incrementAndGet()
    val nextSession = TerminalSession(TRANSCRIPT_ROWS, SessionClient(generation), SessionIO(generation))
    nextSession.applyColorScheme(currentColorScheme())
    val nextBackend = TerminalSessionBackend(nextSession)
    session = nextSession
    backend = nextBackend
    sessionReady = false
    fedBuffer = ""
    terminalView.setBackend(nextBackend)
  }

  private fun destroySession() {
    val previousSession = session ?: return
    val previousBackend = backend
    // Bumping the generation stops a queued feed; closing the session releases one blocked in appendOutput.
    sessionGeneration.incrementAndGet()
    session = null
    backend = null
    sessionReady = false
    terminalView.setBackend(null)
    previousBackend?.release()
    previousSession.close()
  }

  private fun recreateSession() {
    destroySession()
    createSession()
  }

  private fun onSessionReady(readySession: TerminalSession, generation: Long) {
    if (isCleanedUp || readySession !== session || generation != sessionGeneration.get()) return
    sessionReady = true
    val output = streamedOutput
    if (output != null) {
      feed(output.data)
    } else if (initialBuffer.isNotEmpty()) {
      feed(initialBuffer.toByteArray(Charsets.UTF_8))
      fedBuffer = initialBuffer
    }
  }

  private fun feedPendingBuffer() {
    if (streamedOutput != null || initialBuffer == fedBuffer) return
    if (!initialBuffer.startsWith(fedBuffer)) {
      recreateSession()
      return
    }
    if (!sessionReady) return
    feed(initialBuffer.substring(fedBuffer.length).toByteArray(Charsets.UTF_8))
    fedBuffer = initialBuffer
  }

  private fun feed(bytes: ByteArray) {
    val target = session ?: return
    if (bytes.isEmpty() || isCleanedUp) return
    val generation = sessionGeneration.get()
    runCatching {
      feedExecutor.execute {
        var offset = 0
        while (offset < bytes.size && !isCleanedUp && sessionGeneration.get() == generation) {
          val count = minOf(FEED_CHUNK_SIZE, bytes.size - offset)
          target.appendOutput(bytes, offset, count)
          offset += count
        }
      }
    }
  }

  private fun applyFontSize() {
    if (pinchedFontSizeSp == fontSize) {
      // The pinch already applied this size; converting it back to pixels could nudge it.
      pinchedFontSizeSp = null
      return
    }
    pinchedFontSizeSp = null
    val px = spToFontSizePx(fontSize, scaledDensity, minFontPx, maxFontPx)
    if (px == terminalView.getFontSize()) return
    terminalView.setFontSize(px)
    onGeometryChanged()
  }

  private fun onGeometryChanged() {
    emitResize()
    layoutTerminalChildren()
  }

  private fun currentMetrics(widthPx: Int, heightPx: Int): TerminalMetrics =
    TerminalMetrics.from(
      fontSizePx = terminalView.getFontSize().toFloat(),
      typeface = TerminalTypefaces.regular,
      viewportWidthPx = widthPx,
      viewportHeightPx = heightPx,
    )

  private fun emitResize() {
    if (width <= 0 || height <= 0 || isCleanedUp) return
    val metrics = currentMetrics(width, height)
    val nextCols = (width / metrics.measuredCellWidthPx).toInt().coerceAtLeast(MIN_GRID_DIMENSION)
    val nextRows = ((height - metrics.lineSpacingAndAscentPx) / metrics.cellHeightPx)
      .toInt()
      .coerceAtLeast(MIN_GRID_DIMENSION)
    if (nextCols == measuredCols && nextRows == measuredRows) return
    measuredCols = nextCols
    measuredRows = nextRows
    onResize(mapOf("cols" to measuredCols, "rows" to measuredRows))
  }

  private fun terminalViewportPx(fullWidth: Int, fullHeight: Int): Pair<Int, Int> {
    if (measuredCols <= 0 || measuredRows <= 0) return fullWidth to fullHeight
    val metrics = currentMetrics(fullWidth, fullHeight)
    return computeSharedGridViewportPx(
      fullWidthPx = fullWidth,
      fullHeightPx = fullHeight,
      measuredCols = measuredCols,
      measuredRows = measuredRows,
      sharedGrid = sharedGrid,
      measuredCellWidthPx = metrics.measuredCellWidthPx,
      cellHeightPx = metrics.cellHeightPx,
      lineSpacingAndAscentPx = metrics.lineSpacingAndAscentPx,
    )
  }

  private fun measureTerminalChildren(fullWidth: Int, fullHeight: Int) {
    container.measure(
      MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(fullHeight, MeasureSpec.EXACTLY),
    )
    val (terminalWidth, terminalHeight) = terminalViewportPx(fullWidth, fullHeight)
    terminalView.measure(
      MeasureSpec.makeMeasureSpec(terminalWidth, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(terminalHeight, MeasureSpec.EXACTLY),
    )
    fontSizePill.measure(
      MeasureSpec.makeMeasureSpec(fullWidth, MeasureSpec.AT_MOST),
      MeasureSpec.makeMeasureSpec(fullHeight, MeasureSpec.AT_MOST),
    )
  }

  private fun placeTerminalChildren() {
    terminalView.layout(0, 0, terminalView.measuredWidth, terminalView.measuredHeight)
    val pillLeft = (container.width - fontSizePill.measuredWidth) / 2
    val pillTop = (container.height - fontSizePill.measuredHeight) / 2
    fontSizePill.layout(
      pillLeft,
      pillTop,
      pillLeft + fontSizePill.measuredWidth,
      pillTop + fontSizePill.measuredHeight,
    )
  }

  private fun layoutTerminalChildren() {
    if (width <= 0 || height <= 0 || isCleanedUp) return
    if (!isAttachedToWindow) {
      needsLayoutOnAttach = true
      return
    }
    measureTerminalChildren(width, height)
    placeTerminalChildren()
  }

  private fun configureFontSizePill() {
    val density = resources.displayMetrics.density
    fontSizePill.visibility = View.GONE
    fontSizePill.alpha = 0f
    fontSizePill.isClickable = false
    fontSizePill.isFocusable = false
    fontSizePill.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    fontSizePill.gravity = Gravity.CENTER
    fontSizePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    val horizontal = (16 * density).roundToInt()
    val vertical = (8 * density).roundToInt()
    fontSizePill.setPadding(horizontal, vertical, horizontal, vertical)
    fontSizePill.background = GradientDrawable().apply { cornerRadius = 999 * density }
  }

  private fun showFontSizePill(fontSizeSp: Float) {
    if (isCleanedUp) return
    removeCallbacks(hideFontSizePill)
    fontSizePill.text = formatFontSizeBadge(fontSizeSp)
    fontSizePill.animate().cancel()
    if (fontSizePill.visibility != View.VISIBLE) {
      fontSizePill.visibility = View.VISIBLE
      fontSizePill.animate().alpha(1f).setDuration(FONT_SIZE_PILL_FADE_MS)
    } else {
      fontSizePill.alpha = 1f
    }
    layoutTerminalChildren()
    postDelayed(hideFontSizePill, FONT_SIZE_PILL_HIDE_DELAY_MS)
  }

  private fun currentColorScheme(): IntArray =
    parseGhosttyColorScheme(foregroundColorValue, backgroundColorValue, cursorColorValue, themeConfig)

  private fun applyTheme() {
    setBackgroundColor(backgroundColorValue)
    container.setBackgroundColor(backgroundColorValue)
    terminalView.setInitialBackgroundColor(backgroundColorValue)
    session?.applyColorScheme(currentColorScheme())
    fontSizePill.setTextColor(backgroundColorValue)
    (fontSizePill.background as? GradientDrawable)?.setColor(
      (foregroundColorValue and 0x00FFFFFF) or (FONT_SIZE_PILL_ALPHA shl 24),
    )
  }

  private fun clipboard(): ClipboardManager? =
    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

  private fun dispatchInput(text: String, generation: Long) {
    if (isCleanedUp || readOnly || generation != sessionGeneration.get()) return
    onInput(mapOf("data" to text))
  }

  private inner class SessionIO(private val generation: Long) : TerminalSessionIO {
    // The session worker writes terminal replies from its own thread.
    override fun write(data: ByteArray, offset: Int, count: Int) {
      if (readOnly || count <= 0 || isCleanedUp) return
      val text = String(data, offset, count, Charsets.UTF_8)
      if (Looper.myLooper() == Looper.getMainLooper()) {
        dispatchInput(text, generation)
      } else {
        mainHandler.post { dispatchInput(text, generation) }
      }
    }

    // onResize reports the full view's fit, not the canvas clamped to the shared grid.
    override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit

    override fun onClose() = Unit
  }

  @Suppress("TooManyFunctions")
  private inner class SessionClient(private val generation: Long) : TerminalSessionClient {
    override fun onTerminalReady(readySession: TerminalSession) {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        onSessionReady(readySession, generation)
      } else {
        mainHandler.post { onSessionReady(readySession, generation) }
      }
    }

    override fun onFrameAvailable(changedSession: TerminalSession) = Unit

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit

    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalProtocolNotification(session: TerminalSession, title: String?, body: String?) = Unit

    override fun onTerminalProgressChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) = Unit

    override fun logWarn(tag: String?, message: String?) = Unit

    override fun logInfo(tag: String?, message: String?) = Unit

    override fun logDebug(tag: String?, message: String?) = Unit

    override fun logVerbose(tag: String?, message: String?) = Unit

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit

    override fun logStackTrace(tag: String?, e: Exception?) = Unit
  }
}
