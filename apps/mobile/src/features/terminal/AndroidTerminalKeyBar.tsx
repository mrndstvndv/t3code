import * as Haptics from "expo-haptics";
import { useGT } from "gt-react-native";
import { useEffect, useEffectEvent, useState } from "react";
import { Pressable, View, type AccessibilityActionEvent } from "react-native";
import { Gesture, GestureDetector } from "react-native-gesture-handler";
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
} from "react-native-reanimated";

import { ComposerToolbarButton } from "../../components/ComposerToolbar";
import { AppText as Text } from "../../components/ui/AppText";
import {
  ANDROID_KEYCODE,
  arrowDirectionKeyCode,
  createArrowDragTracker,
  joystickKnobOffset,
  repeatDelayMs,
  type ArrowDirection,
  type KeyModifierState,
} from "./AndroidTerminalKeyBar.logic";
import type { TerminalTheme } from "./terminalTheme";

const BAR_HEIGHT = 48;
const JOYSTICK_TRACK_SIZE = 40;
const JOYSTICK_KNOB_SIZE = 18;
const JOYSTICK_KNOB_TRAVEL = (JOYSTICK_TRACK_SIZE - JOYSTICK_KNOB_SIZE) / 2;
const JOYSTICK_SWIPE_STEP = 10;
// Compose's medium-bouncy spring: damping ratio 0.5 at medium stiffness.
const KNOB_RETURN_SPRING = { mass: 1, stiffness: 1500, damping: 39 };

function keyHaptic() {
  void Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Virtual_Key);
}

function lockHaptic() {
  void Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Long_Press);
}

function arrowHaptic() {
  void Haptics.performAndroidHapticsAsync(Haptics.AndroidHaptics.Text_Handle_Move);
}

function KeyButton(props: {
  readonly label: string;
  readonly theme: TerminalTheme;
  readonly accentColor: string;
  readonly accentForegroundColor: string;
  readonly state?: KeyModifierState;
  readonly onPress: () => void;
  readonly onLongPress?: () => void;
}) {
  const state = props.state ?? "off";
  return (
    <Pressable
      accessibilityRole={props.state === undefined ? "button" : "togglebutton"}
      accessibilityLabel={props.label}
      accessibilityState={
        props.state === undefined
          ? undefined
          : { checked: state === "locked" ? true : state === "armed" ? "mixed" : false }
      }
      onPressIn={keyHaptic}
      onPress={props.onPress}
      onLongPress={
        props.onLongPress
          ? () => {
              lockHaptic();
              props.onLongPress?.();
            }
          : undefined
      }
      style={({ pressed }) => ({
        minWidth: 44,
        height: 36,
        borderRadius: 18,
        paddingHorizontal: 10,
        alignItems: "center",
        justifyContent: "center",
        overflow: "hidden",
        backgroundColor: state === "locked" ? props.accentColor : "transparent",
        transform: [{ scale: pressed ? 0.94 : 1 }],
      })}
    >
      {state === "armed" ? (
        <View
          pointerEvents="none"
          style={{
            position: "absolute",
            inset: 0,
            backgroundColor: props.accentColor,
            opacity: 0.22,
          }}
        />
      ) : null}
      <Text
        className="text-sm font-t3-medium"
        style={{ color: state === "locked" ? props.accentForegroundColor : props.theme.foreground }}
      >
        {props.label}
      </Text>
    </Pressable>
  );
}

function ArrowJoystick(props: {
  readonly theme: TerminalTheme;
  readonly accentColor: string;
  readonly onArrow: (direction: ArrowDirection) => void;
}) {
  const gt = useGT();
  const knobX = useSharedValue(0);
  const knobY = useSharedValue(0);
  const lockedHorizontal = useSharedValue<boolean | null>(null);
  const [tracker] = useState(() => createArrowDragTracker(JOYSTICK_SWIPE_STEP));
  // A vertical swipe holds its arrow, which repeats until the finger lifts.
  const [held, setHeld] = useState<ArrowDirection | null>(null);

  const fireArrow = useEffectEvent((direction: ArrowDirection) => {
    props.onArrow(direction);
    arrowHaptic();
  });
  // oxlint-disable-next-line t3code/no-unreasoned-state-sync -- drives the held arrow's accelerating repeat timer
  useEffect(() => {
    if (held === null) return;
    let repeatIndex = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = () => {
      fireArrow(held);
      timer = setTimeout(tick, repeatDelayMs(repeatIndex));
      repeatIndex += 1;
    };
    tick();
    return () => clearTimeout(timer);
  }, [held]);

  const handleStart = useEffectEvent(() => {
    tracker.start(0, 0);
    setHeld(null);
  });
  const handleMove = useEffectEvent((dx: number, dy: number) => {
    const steps = tracker.move(dx, dy);
    if (steps === null) return;
    lockedHorizontal.set(steps.lockedHorizontal);
    if (!steps.lockedHorizontal) {
      setHeld(steps.direction);
      return;
    }
    for (let index = 0; index < steps.count; index += 1) props.onArrow(steps.direction);
    arrowHaptic();
  });
  const handleEnd = useEffectEvent(() => {
    setHeld(null);
  });

  const [gesture] = useState(() =>
    Gesture.Pan()
      .minDistance(0)
      .shouldCancelWhenOutside(false)
      .onBegin(() => {
        lockedHorizontal.set(null);
        runOnJS(handleStart)();
      })
      .onUpdate((event) => {
        const knob = joystickKnobOffset(
          lockedHorizontal.get(),
          event.translationX,
          event.translationY,
          JOYSTICK_KNOB_TRAVEL,
        );
        knobX.set(knob.x);
        knobY.set(knob.y);
        runOnJS(handleMove)(event.translationX, event.translationY);
      })
      .onFinalize(() => {
        lockedHorizontal.set(null);
        knobX.set(withSpring(0, KNOB_RETURN_SPRING));
        knobY.set(withSpring(0, KNOB_RETURN_SPRING));
        runOnJS(handleEnd)();
      }),
  );
  const knobStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: knobX.get() }, { translateY: knobY.get() }],
  }));

  const handleAccessibilityAction = (event: AccessibilityActionEvent) => {
    const direction = event.nativeEvent.actionName;
    if (
      direction === "up" ||
      direction === "down" ||
      direction === "left" ||
      direction === "right"
    ) {
      props.onArrow(direction);
    }
  };

  return (
    <GestureDetector gesture={gesture}>
      <View
        accessible
        accessibilityLabel={gt("Arrow keys")}
        accessibilityActions={[
          { name: "up", label: gt("Arrow up") },
          { name: "down", label: gt("Arrow down") },
          { name: "left", label: gt("Arrow left") },
          { name: "right", label: gt("Arrow right") },
        ]}
        onAccessibilityAction={handleAccessibilityAction}
        style={{
          width: JOYSTICK_TRACK_SIZE,
          height: JOYSTICK_TRACK_SIZE,
          borderRadius: JOYSTICK_TRACK_SIZE / 2,
          alignItems: "center",
          justifyContent: "center",
          backgroundColor: props.theme.border,
        }}
      >
        <Animated.View
          style={[
            {
              width: JOYSTICK_KNOB_SIZE,
              height: JOYSTICK_KNOB_SIZE,
              borderRadius: JOYSTICK_KNOB_SIZE / 2,
              backgroundColor: props.accentColor,
            },
            knobStyle,
          ]}
        />
      </View>
    </GestureDetector>
  );
}

/** Android's terminal key bar: sticky Ctrl/Alt, Esc, Tab, and a swipe arrow joystick. */
export function AndroidTerminalKeyBar(props: {
  readonly theme: TerminalTheme;
  readonly accentColor: string;
  readonly accentForegroundColor: string;
  readonly surfaceColor: string;
  readonly ctrlState: KeyModifierState;
  readonly altState: KeyModifierState;
  readonly onTapCtrl: () => void;
  readonly onLockCtrl: () => void;
  readonly onTapAlt: () => void;
  readonly onLockAlt: () => void;
  readonly onSendKey: (keyCode: number) => void;
  readonly onPaste: () => void;
  readonly onDismissKeyboard: () => void;
}) {
  const gt = useGT();
  return (
    <View
      style={{
        flexDirection: "row",
        alignItems: "center",
        gap: 6,
        paddingHorizontal: 8,
        paddingVertical: 4,
      }}
    >
      <View
        style={{
          flex: 1,
          height: BAR_HEIGHT,
          borderRadius: BAR_HEIGHT / 2,
          borderWidth: 1,
          borderColor: props.theme.border,
          backgroundColor: props.surfaceColor,
          flexDirection: "row",
          alignItems: "center",
          justifyContent: "space-evenly",
          paddingHorizontal: 4,
        }}
      >
        <KeyButton
          label="Esc"
          theme={props.theme}
          accentColor={props.accentColor}
          accentForegroundColor={props.accentForegroundColor}
          onPress={() => props.onSendKey(ANDROID_KEYCODE.ESCAPE)}
        />
        <KeyButton
          label="Ctrl"
          theme={props.theme}
          accentColor={props.accentColor}
          accentForegroundColor={props.accentForegroundColor}
          state={props.ctrlState}
          onPress={props.onTapCtrl}
          onLongPress={props.onLockCtrl}
        />
        <KeyButton
          label="Alt"
          theme={props.theme}
          accentColor={props.accentColor}
          accentForegroundColor={props.accentForegroundColor}
          state={props.altState}
          onPress={props.onTapAlt}
          onLongPress={props.onLockAlt}
        />
        <KeyButton
          label="Tab"
          theme={props.theme}
          accentColor={props.accentColor}
          accentForegroundColor={props.accentForegroundColor}
          onPress={() => props.onSendKey(ANDROID_KEYCODE.TAB)}
        />
        <ArrowJoystick
          theme={props.theme}
          accentColor={props.accentColor}
          onArrow={(direction) => props.onSendKey(arrowDirectionKeyCode(direction))}
        />
      </View>
      <ComposerToolbarButton
        accessibilityLabel={gt("Paste", { $context: "verb" })}
        icon={{ ios: "doc.on.clipboard", android: "content_paste" }}
        onPress={props.onPaste}
        showChevron={false}
      />
      <ComposerToolbarButton
        accessibilityLabel={gt("Dismiss keyboard")}
        icon={{ ios: "keyboard.chevron.compact.down", android: "keyboard_hide" }}
        onPress={props.onDismissKeyboard}
        showChevron={false}
      />
    </View>
  );
}
