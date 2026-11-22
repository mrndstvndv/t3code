import { describe, expect, it } from "vite-plus/test";

import {
  clampKnobOffset,
  consumeArmedModifier,
  createArrowDragTracker,
  joystickKnobOffset,
  repeatDelayMs,
  tapAltModifier,
  tapCtrlModifier,
  type KeyModifierState,
} from "./AndroidTerminalKeyBar.logic";

describe("modifier states", () => {
  it.each<[KeyModifierState, KeyModifierState, KeyModifierState]>([
    // state, after a Ctrl tap, after an Alt tap
    ["off", "armed", "armed"],
    ["armed", "off", "locked"],
    ["locked", "off", "off"],
  ])("taps from %s", (state, ctrl, alt) => {
    expect(tapCtrlModifier(state)).toBe(ctrl);
    expect(tapAltModifier(state)).toBe(alt);
  });

  it("releases an armed modifier after one key and keeps a locked one", () => {
    expect(consumeArmedModifier("armed")).toBe("off");
    expect(consumeArmedModifier("locked")).toBe("locked");
    expect(consumeArmedModifier("off")).toBe("off");
  });
});

describe("repeatDelayMs", () => {
  it("pauses first, then accelerates down to a 40ms floor", () => {
    expect([0, 1, 2, 3, 7, 8, 50].map(repeatDelayMs)).toEqual([350, 120, 102, 86, 45, 40, 40]);
  });
});

describe("createArrowDragTracker", () => {
  it("steps horizontally once per step distance, in both directions", () => {
    const tracker = createArrowDragTracker(10);
    tracker.start(0, 0);
    expect(tracker.move(9, 3)).toBeNull();
    expect(tracker.move(25, 3)).toEqual({ direction: "right", count: 2, lockedHorizontal: true });
    expect(tracker.move(29, 40)).toBeNull();
    expect(tracker.move(-1, 40)).toEqual({ direction: "left", count: 2, lockedHorizontal: true });
    expect(tracker.lockedHorizontal).toBe(true);
  });

  it("locks a vertical drag after its first step", () => {
    const tracker = createArrowDragTracker(10);
    tracker.start(0, 0);
    expect(tracker.move(2, -12)).toEqual({ direction: "up", count: 1, lockedHorizontal: false });
    expect(tracker.move(40, -60)).toBeNull();
    expect(tracker.lockedHorizontal).toBe(false);

    tracker.start(0, 0);
    expect(tracker.lockedHorizontal).toBeNull();
    expect(tracker.move(0, 11)).toEqual({ direction: "down", count: 1, lockedHorizontal: false });
  });
});

describe("knob offset", () => {
  it("clamps to the travel radius and follows the locked axis", () => {
    expect(clampKnobOffset(3, 4, 11)).toEqual({ x: 3, y: 4 });
    expect(clampKnobOffset(30, 40, 10)).toEqual({ x: 6, y: 8 });
    expect(joystickKnobOffset(true, 5, 9, 11)).toEqual({ x: 5, y: 0 });
    expect(joystickKnobOffset(false, 5, -20, 11)).toEqual({ x: 0, y: -11 });
  });
});
