export type KeyModifierState = "off" | "armed" | "locked";

/** Android `KeyEvent` key codes the key bar sends. */
export const ANDROID_KEYCODE = {
  ESCAPE: 111,
  TAB: 61,
  DPAD_UP: 19,
  DPAD_DOWN: 20,
  DPAD_LEFT: 21,
  DPAD_RIGHT: 22,
} as const;

export function tapCtrlModifier(state: KeyModifierState): KeyModifierState {
  return state === "off" ? "armed" : "off";
}

export function tapAltModifier(state: KeyModifierState): KeyModifierState {
  return state === "off" ? "armed" : state === "armed" ? "locked" : "off";
}

export function lockModifier(): KeyModifierState {
  return "locked";
}

/** An armed modifier applies to one key; a locked one stays until tapped off. */
export function consumeArmedModifier(state: KeyModifierState): KeyModifierState {
  return state === "armed" ? "off" : state;
}

export type ArrowDirection = "up" | "down" | "left" | "right";

export function arrowDirectionKeyCode(direction: ArrowDirection): number {
  switch (direction) {
    case "up":
      return ANDROID_KEYCODE.DPAD_UP;
    case "down":
      return ANDROID_KEYCODE.DPAD_DOWN;
    case "left":
      return ANDROID_KEYCODE.DPAD_LEFT;
    case "right":
      return ANDROID_KEYCODE.DPAD_RIGHT;
  }
}

/** Delay before the next repeat of a held vertical arrow: a pause, then accelerating to a floor. */
export function repeatDelayMs(repeatIndex: number): number {
  if (repeatIndex === 0) return 350;
  return Math.max(40, Math.floor(120 * Math.pow(0.85, repeatIndex - 1)));
}

export function clampKnobOffset(
  dx: number,
  dy: number,
  maxRadius: number,
): { readonly x: number; readonly y: number } {
  "worklet";
  const length = Math.hypot(dx, dy);
  if (length <= maxRadius) return { x: dx, y: dy };
  const scale = maxRadius / length;
  return { x: dx * scale, y: dy * scale };
}

/** Once a drag picks an axis, the knob only travels along it. */
export function joystickKnobOffset(
  lockedHorizontal: boolean | null,
  dx: number,
  dy: number,
  maxRadius: number,
): { readonly x: number; readonly y: number } {
  "worklet";
  if (lockedHorizontal === true) return clampKnobOffset(dx, 0, maxRadius);
  if (lockedHorizontal === false) return clampKnobOffset(0, dy, maxRadius);
  return clampKnobOffset(dx, dy, maxRadius);
}

export interface ArrowSteps {
  readonly direction: ArrowDirection;
  readonly count: number;
  readonly lockedHorizontal: boolean;
}

/**
 * Turns a swipe into arrow steps. The first step past `stepPx` picks the axis. Horizontally,
 * every further `stepPx` is another step; vertically, the drag reports one step and then nothing,
 * so the caller repeats that arrow until the finger lifts.
 */
export function createArrowDragTracker(stepPx: number) {
  let originX = 0;
  let originY = 0;
  let lockedHorizontal: boolean | null = null;

  return {
    get lockedHorizontal() {
      return lockedHorizontal;
    },
    start(x: number, y: number) {
      originX = x;
      originY = y;
      lockedHorizontal = null;
    },
    move(x: number, y: number): ArrowSteps | null {
      if (lockedHorizontal === false) return null;
      const dx = x - originX;
      const dy = y - originY;
      const onX = lockedHorizontal ?? Math.abs(dx) >= Math.abs(dy);
      const steps = Math.trunc((onX ? dx : dy) / stepPx);
      if (steps === 0) return null;
      lockedHorizontal = onX;
      if (onX) originX += steps * stepPx;
      else originY += steps * stepPx;
      const direction: ArrowDirection = onX
        ? steps < 0
          ? "left"
          : "right"
        : steps < 0
          ? "up"
          : "down";
      return { direction, count: onX ? Math.abs(steps) : 1, lockedHorizontal: onX };
    },
  };
}
