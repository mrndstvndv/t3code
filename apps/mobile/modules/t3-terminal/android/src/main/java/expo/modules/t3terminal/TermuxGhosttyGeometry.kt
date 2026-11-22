package expo.modules.t3terminal

import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

// Mirrors src/features/terminal/terminalPreferences.ts.
internal const val MIN_TERMINAL_FONT_SIZE_SP = 6f
internal const val MAX_TERMINAL_FONT_SIZE_SP = 14f
internal const val TERMINAL_FONT_SIZE_STEP_SP = 0.5f

// The compose canvas never lays out a grid smaller than this.
private const val MIN_GRID_DIMENSION = 4

internal fun parseGhosttyColorScheme(
  foreground: Int,
  background: Int,
  cursor: Int,
  themeConfig: String,
): IntArray {
  val colors = TerminalColorScheme().mDefaultColors.clone()
  colors[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
  colors[TextStyle.COLOR_INDEX_BACKGROUND] = background
  colors[TextStyle.COLOR_INDEX_CURSOR] = cursor
  for (line in themeConfig.lineSequence()) {
    val parts = line.split('=', limit = 2)
    if (parts.size != 2) continue
    val value = parts[1].trim()
    when (parts[0].trim()) {
      "cursor-color" -> parseHexColor(value)?.let { colors[TextStyle.COLOR_INDEX_CURSOR] = it }
      "palette" -> {
        val entry = value.split('=', limit = 2)
        val index = entry[0].trim().toIntOrNull()
        val color = entry.getOrNull(1)?.let { parseHexColor(it.trim()) }
        if (index != null && index in 0..255 && color != null) colors[index] = color
      }
    }
  }
  return colors
}

/** Parses `#RGB`, `#RRGGBB` or `#AARRGGBB` (the `#` is optional), or returns null. */
internal fun parseHexColor(value: String): Int? {
  val hex = value.removePrefix("#")
  if (hex.isEmpty() || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
  return when (hex.length) {
    3 -> (0xFF000000 or hex.map { "$it$it" }.joinToString("").toLong(16)).toInt()
    6 -> (0xFF000000 or hex.toLong(16)).toInt()
    8 -> hex.toLong(16).toInt()
    else -> null
  }
}

internal fun spToFontSizePx(fontSizeSp: Float, scaledDensity: Float, minPx: Int, maxPx: Int): Int =
  (fontSizeSp * scaledDensity).roundToInt().coerceIn(minPx, maxPx)

internal fun fontSizePxToSnappedSp(
  fontSizePx: Int,
  scaledDensity: Float,
  minSp: Float = MIN_TERMINAL_FONT_SIZE_SP,
  maxSp: Float = MAX_TERMINAL_FONT_SIZE_SP,
  stepSp: Float = TERMINAL_FONT_SIZE_STEP_SP,
): Float = ((fontSizePx / scaledDensity / stepSp).roundToInt() * stepSp).coerceIn(minSp, maxSp)

internal fun formatFontSizeBadge(fontSizeSp: Float): String =
  String.format(Locale.US, "%.1f pt", fontSizeSp)

/**
 * The canvas size that makes the compose terminal lay out exactly the shared PTY grid.
 * The canvas drops any frame whose grid differs from the one it derives from its own size,
 * so the size must derive back to the target with the canvas's own truncating formula.
 */
@Suppress("LongParameterList")
internal fun computeSharedGridViewportPx(
  fullWidthPx: Int,
  fullHeightPx: Int,
  measuredCols: Int,
  measuredRows: Int,
  sharedGrid: List<Int>,
  measuredCellWidthPx: Float,
  cellHeightPx: Float,
  lineSpacingAndAscentPx: Float,
): Pair<Int, Int> {
  if (sharedGrid.size != 2 || sharedGrid[0] <= 0 || sharedGrid[1] <= 0) return fullWidthPx to fullHeightPx
  if (measuredCellWidthPx <= 0f || cellHeightPx <= 0f) return fullWidthPx to fullHeightPx
  val targetCols = sharedGrid[0].coerceIn(MIN_GRID_DIMENSION, measuredCols.coerceAtLeast(MIN_GRID_DIMENSION))
  val targetRows = sharedGrid[1].coerceIn(MIN_GRID_DIMENSION, measuredRows.coerceAtLeast(MIN_GRID_DIMENSION))
  val width = if (targetCols < measuredCols) {
    fitSize(ceil(targetCols * measuredCellWidthPx).toInt(), fullWidthPx, targetCols) { px ->
      (px / measuredCellWidthPx).toInt()
    }
  } else {
    fullWidthPx
  }
  val height = if (targetRows < measuredRows) {
    fitSize(ceil(targetRows * cellHeightPx + lineSpacingAndAscentPx).toInt(), fullHeightPx, targetRows) { px ->
      ((px - lineSpacingAndAscentPx) / cellHeightPx).toInt()
    }
  } else {
    fullHeightPx
  }
  return width to height
}

/** Nudges [initialPx] by a pixel at a time until [cellsFor] yields [target], within `1..maxPx`. */
private inline fun fitSize(initialPx: Int, maxPx: Int, target: Int, cellsFor: (Int) -> Int): Int {
  var px = initialPx.coerceIn(1, maxPx)
  repeat(2) {
    val cells = cellsFor(px)
    px = when {
      cells < target && px < maxPx -> px + 1
      cells > target && px > 1 -> px - 1
      else -> return px
    }
  }
  return px
}
