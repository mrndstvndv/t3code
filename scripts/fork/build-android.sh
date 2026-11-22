#!/usr/bin/env bash
# Build the Android release APK locally and copy it to ../t3code-android-build.
# Usage:
#   ./scripts/fork/build-android.sh [--clean] [--install] [--skip-terminal-lib] [--out <dir>]
#   Publishes ../termux-ghostty's terminal AARs to mavenLocal first unless --skip-terminal-lib.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MOBILE_DIR="$REPO_ROOT/apps/mobile"
OUT_DIR="$(cd "$REPO_ROOT/.." && pwd)/t3code-android-build"
TERMUX_GHOSTTY_DIR="$(cd "$REPO_ROOT/.." && pwd)/termux-ghostty"
CLEAN_PREBUILD=0
INSTALL_ADB=0
SKIP_TERMINAL_LIB=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --clean) CLEAN_PREBUILD=1; shift ;;
    --install) INSTALL_ADB=1; shift ;;
    --skip-terminal-lib) SKIP_TERMINAL_LIB=1; shift ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,5p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if [[ "$SKIP_TERMINAL_LIB" -eq 0 && -d "$TERMUX_GHOSTTY_DIR" ]]; then
  echo "Publishing termux-ghostty terminal libraries to mavenLocal..."
  (cd "$TERMUX_GHOSTTY_DIR" && ./gradlew \
    :terminal-emulator:publishReleasePublicationToMavenLocal \
    :terminal-compose-view:publishReleasePublicationToMavenLocal \
    :terminal-compose-session:publishReleasePublicationToMavenLocal \
    -PtermuxTargetAbis=arm64-v8a,armeabi-v7a,x86_64)
fi

export APP_VARIANT=production
export EXPO_NO_GIT_STATUS=1

if [[ "$CLEAN_PREBUILD" -eq 1 || ! -d "$MOBILE_DIR/android" ]]; then
  echo "Running expo prebuild (android)..."
  PREBUILD_ARGS=(prebuild --platform android)
  if [[ "$CLEAN_PREBUILD" -eq 1 ]]; then
    PREBUILD_ARGS+=(--clean)
  fi
  (cd "$MOBILE_DIR" && pnpm exec expo "${PREBUILD_ARGS[@]}")
fi

GRADLE_ARGS=(assembleRelease)
if [[ "$SKIP_TERMINAL_LIB" -eq 0 && -d "$TERMUX_GHOSTTY_DIR" ]]; then
  GRADLE_ARGS+=(--refresh-dependencies)
fi

echo "Building Android release APK..."
(cd "$MOBILE_DIR/android" && ./gradlew "${GRADLE_ARGS[@]}")

BUILT_APK="$MOBILE_DIR/android/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$BUILT_APK" ]]; then
  echo "Expected APK not found at $BUILT_APK" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
DEST_APK="$OUT_DIR/T3-Code-release-${STAMP}-${SHA}.apk"
cp "$BUILT_APK" "$DEST_APK"
echo "Built: $DEST_APK"

if [[ "$INSTALL_ADB" -eq 1 ]]; then
  echo "Installing via adb..."
  adb install -r "$DEST_APK"
fi
