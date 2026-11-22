package expo.modules.t3terminal

import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TextStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxGhosttyGeometryTest {
  @Test
  fun parsesThemeConfigOverDefaultScheme() {
    val colors = parseGhosttyColorScheme(
      foreground = 0xFF111111.toInt(),
      background = 0xFF222222.toInt(),
      cursor = 0xFF333333.toInt(),
      themeConfig = """
        background = #000000
        cursor-color = #009fff
        palette = 1=#ff2e3f
        palette = 255=fff
        palette = 256=#123456
        palette = 2=not-a-color
        palette = 3=#12345
      """.trimIndent(),
    )
    val defaults = TerminalColorScheme().mDefaultColors

    assertEquals(TextStyle.NUM_INDEXED_COLORS, colors.size)
    assertEquals(0xFF111111.toInt(), colors[TextStyle.COLOR_INDEX_FOREGROUND])
    assertEquals(0xFF222222.toInt(), colors[TextStyle.COLOR_INDEX_BACKGROUND])
    assertEquals(0xFF009FFF.toInt(), colors[TextStyle.COLOR_INDEX_CURSOR])
    assertEquals(0xFFFF2E3F.toInt(), colors[1])
    assertEquals(0xFFFFFFFF.toInt(), colors[255])
    assertEquals(defaults[2], colors[2])
    assertEquals(defaults[3], colors[3])
    assertArrayEquals(defaults.copyOfRange(4, 255), colors.copyOfRange(4, 255))
  }

  @Test
  fun keepsTheCursorPropWithoutAValidCursorColor() {
    val colors = parseGhosttyColorScheme(0, 0, 0xFF333333.toInt(), "cursor-color = nope")
    assertEquals(0xFF333333.toInt(), colors[TextStyle.COLOR_INDEX_CURSOR])
  }

  @Test
  fun convertsFontSizesBetweenSpAndPixels() {
    assertEquals(29, spToFontSizePx(10.5f, 2.75f, minPx = 17, maxPx = 39))
    assertEquals(17, spToFontSizePx(2f, 2.75f, minPx = 17, maxPx = 39))
    assertEquals(39, spToFontSizePx(20f, 2.75f, minPx = 17, maxPx = 39))

    assertEquals(10.5f, fontSizePxToSnappedSp(29, 2.75f))
    assertEquals(11f, fontSizePxToSnappedSp(30, 2.75f))
    assertEquals(6f, fontSizePxToSnappedSp(1, 2.75f))
    assertEquals(14f, fontSizePxToSnappedSp(100, 2.75f))
    assertEquals("10.5 pt", formatFontSizeBadge(10.5f))
    assertEquals("6.0 pt", formatFontSizeBadge(6f))
  }

  @Test
  fun usesTheFullViewportWithoutASmallerSharedGrid() {
    assertEquals(1080 to 1800, viewport(sharedGrid = emptyList()))
    assertEquals(1080 to 1800, viewport(sharedGrid = listOf(0, 24)))
    assertEquals(1080 to 1800, viewport(sharedGrid = listOf(200, 200)))
  }

  @Test
  fun sizesTheCanvasToDeriveTheSharedGrid() {
    val (width, height) = viewport(sharedGrid = listOf(80, 24))
    assertEquals(600, width)
    assertEquals(412, height)
    assertEquals(1080 to 412, viewport(sharedGrid = listOf(144, 24)))
    assertEquals(30 to 1800, viewport(sharedGrid = listOf(1, 105)))
  }

  @Test
  fun nudgesRoundingSoTheCanvasDerivesTheTargetGrid() {
    // 15 * 5.8f rounds to exactly 87, which divides back to 14.99.
    val (width, _) = computeSharedGridViewportPx(1000, 1000, 172, 80, listOf(15, 80), 5.8f, 12f, 0f)
    assertEquals(88, width)
    assertEquals(15, (width / 5.8f).toInt())

    // ceil(7 * 12.27f + 4.11f) = 90 divides back to 6.99.
    val (_, height) = computeSharedGridViewportPx(1000, 1000, 100, 81, listOf(100, 7), 10f, 12.27f, 4.11f)
    assertEquals(91, height)
    assertEquals(7, ((height - 4.11f) / 12.27f).toInt())
  }

  private fun viewport(sharedGrid: List<Int>): Pair<Int, Int> =
    computeSharedGridViewportPx(
      fullWidthPx = 1080,
      fullHeightPx = 1800,
      measuredCols = 144,
      measuredRows = 105,
      sharedGrid = sharedGrid,
      measuredCellWidthPx = 7.5f,
      cellHeightPx = 17f,
      lineSpacingAndAscentPx = 4f,
    )
}
