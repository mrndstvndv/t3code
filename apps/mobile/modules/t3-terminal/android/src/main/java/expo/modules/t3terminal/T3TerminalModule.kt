package expo.modules.t3terminal

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class T3TerminalModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("T3TerminalSurface")

    // Bumped when native hardware-keyboard handling changes; surfaced in the JS debug
    // logs so a stale native binary is distinguishable from a broken key pipeline.
    Constants(
      "hardwareKeyRevision" to 2,
      "outputRevision" to 1,
    )

    View(TermuxGhosttyTerminalView::class) {
      AsyncFunction("writeOutput") { view: TermuxGhosttyTerminalView, data: String, reset: Boolean ->
        view.writeOutput(data, reset)
      }

      AsyncFunction("resetInput") { view: TermuxGhosttyTerminalView -> view.resetInput() }

      AsyncFunction("sendKey") { view: TermuxGhosttyTerminalView, keyCode: Int, ctrl: Boolean, alt: Boolean ->
        view.sendKey(keyCode, ctrl, alt)
      }

      Prop("terminalKey") { view: TermuxGhosttyTerminalView, terminalKey: String ->
        view.terminalKey = terminalKey
      }

      Prop("initialBuffer") { view: TermuxGhosttyTerminalView, initialBuffer: String ->
        view.initialBuffer = initialBuffer
      }

      Prop("fontSize") { view: TermuxGhosttyTerminalView, fontSize: Double ->
        view.fontSize = fontSize.toFloat()
      }

      Prop("focusRequest") { view: TermuxGhosttyTerminalView, focusRequest: Double ->
        view.focusRequest = focusRequest
      }

      Prop("readOnly") { view: TermuxGhosttyTerminalView, readOnly: Boolean ->
        view.readOnly = readOnly
      }

      Prop("autoFocus") { view: TermuxGhosttyTerminalView, autoFocus: Boolean ->
        view.autoFocus = autoFocus
      }

      Prop("appearanceScheme") { view: TermuxGhosttyTerminalView, appearanceScheme: String ->
        view.appearanceScheme = appearanceScheme
      }

      Prop("themeConfig") { view: TermuxGhosttyTerminalView, themeConfig: String ->
        view.themeConfig = themeConfig
      }

      Prop("backgroundColor") { view: TermuxGhosttyTerminalView, backgroundColor: String ->
        view.backgroundColorHex = backgroundColor
      }

      Prop("foregroundColor") { view: TermuxGhosttyTerminalView, foregroundColor: String ->
        view.foregroundColorHex = foregroundColor
      }

      Prop("mutedForegroundColor") { view: TermuxGhosttyTerminalView, mutedForegroundColor: String ->
        view.mutedForegroundColorHex = mutedForegroundColor
      }

      Prop("captureRequest") { view: TermuxGhosttyTerminalView, request: Double ->
        view.captureRequest = request
      }

      Prop("sharedGrid") { view: TermuxGhosttyTerminalView, sharedGrid: List<Int> ->
        view.sharedGrid = sharedGrid
      }

      Prop("ctrlState") { view: TermuxGhosttyTerminalView, state: String ->
        view.ctrlState = state
      }

      Prop("altState") { view: TermuxGhosttyTerminalView, state: String ->
        view.altState = state
      }
      Events("onInput", "onResize", "onCapture", "onPaste", "onFontSizeChange", "onModifiersConsumed")

      OnViewDidUpdateProps { view: TermuxGhosttyTerminalView ->
        view.applyPendingBuffer()
      }

      OnViewDestroys { view: TermuxGhosttyTerminalView ->
        view.cleanup()
      }
    }
  }
}
