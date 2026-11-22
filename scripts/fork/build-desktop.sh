#!/usr/bin/env bash
# Build the macOS desktop DMG for this personal fork into ../t3code-desktop-build.
# Usage:
#   ./scripts/fork/build-desktop.sh [--version <ver>] [--arch arm64|x64|universal] [--out <dir>] [--skip-build] [--install]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT_DIR="$(cd "$REPO_ROOT/.." && pwd)/t3code-desktop-build"
ARCH="arm64"
SKIP_BUILD=0
INSTALL_AFTER=0
BUILD_VERSION=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) BUILD_VERSION="$2"; shift 2 ;;
    --arch) ARCH="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --install) INSTALL_AFTER=1; shift ;;
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

if [[ -z "$BUILD_VERSION" ]]; then
  BASE_VERSION="$(node -p "JSON.parse(require('fs').readFileSync('$REPO_ROOT/apps/desktop/package.json','utf8')).version")"
  BUILD_VERSION="${BASE_VERSION}-nightly.$(date +%Y%m%d.%H%M)"
fi

mkdir -p "$OUT_DIR"

ARGS=(
  "$REPO_ROOT/scripts/build-desktop-artifact.ts"
  --platform mac
  --target dmg
  --arch "$ARCH"
  --build-version "$BUILD_VERSION"
  --output-dir "$OUT_DIR"
)
if [[ "$SKIP_BUILD" -eq 1 ]]; then
  ARGS+=(--skip-build)
fi

echo "Building T3 Code desktop ($BUILD_VERSION, $ARCH) -> $OUT_DIR"
(cd "$REPO_ROOT" && node "${ARGS[@]}")

DMG_PATH="$(ls -t "$OUT_DIR"/*"${BUILD_VERSION}"*.dmg 2>/dev/null | head -n 1 || true)"
if [[ -n "$DMG_PATH" ]]; then
  echo "Built: $DMG_PATH"
fi

if [[ "$INSTALL_AFTER" -eq 1 ]]; then
  if [[ -n "$DMG_PATH" ]]; then
    "$REPO_ROOT/scripts/fork/install-desktop.sh" "$DMG_PATH"
  else
    "$REPO_ROOT/scripts/fork/install-desktop.sh"
  fi
fi
