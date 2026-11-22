#!/usr/bin/env bash
# Install a built fork DMG into /Applications/T3 Code (Nightly).app with APFS backups + rollback instructions.
# Quit T3 Code (Nightly) completely (Cmd+Q) before running this.
# Usage:
#   ./scripts/fork/install-desktop.sh [path/to/app.dmg]
#   ./scripts/fork/install-desktop.sh --no-backup [path/to/app.dmg]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEFAULT_BUILD_DIR="$(cd "$REPO_ROOT/.." && pwd)/t3code-desktop-build"
APP="/Applications/T3 Code (Nightly).app"
BACKUP=1
DMG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-backup) BACKUP=0; shift ;;
    -h|--help)
      sed -n '2,6p' "$0"
      exit 0
      ;;
    *)
      DMG="$1"
      shift
      ;;
  esac
done

if [[ -z "$DMG" ]]; then
  DMG="$(ls -t "$DEFAULT_BUILD_DIR"/*.dmg 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "$DMG" || ! -f "$DMG" ]]; then
  echo "No DMG found. Pass a path or run ./scripts/fork/build-desktop.sh first." >&2
  exit 1
fi

if pgrep -f "$APP/Contents/MacOS/" >/dev/null; then
  echo "T3 Code (Nightly) is still running. Quit it with Cmd+Q, then run this again." >&2
  exit 1
fi

if [[ "$BACKUP" -eq 1 ]]; then
  STAMP="$(date +%Y%m%d-%H%M%S)"
  BACKUP_DIR="$HOME/.t3/fork-backup-$STAMP"
  mkdir -p "$BACKUP_DIR"

  if [[ -d "$HOME/.t3/userdata" ]]; then
    echo "Backing up userdata to $BACKUP_DIR/userdata ..."
    cp -cR "$HOME/.t3/userdata" "$BACKUP_DIR/userdata"
  fi

  if [[ -d "$APP" ]]; then
    echo "Backing up current app to $BACKUP_DIR/ ..."
    ditto "$APP" "$BACKUP_DIR/T3 Code (Nightly).app"
  fi

  cat > "$BACKUP_DIR/ROLLBACK.txt" <<EOF
To roll back (quit T3 Code first):
  rm -rf "$APP"
  ditto "$BACKUP_DIR/T3 Code (Nightly).app" "$APP"
  rm -rf "$HOME/.t3/userdata" && cp -cR "$BACKUP_DIR/userdata" "$HOME/.t3/userdata"
EOF
  echo "Backup saved: $BACKUP_DIR"
fi

echo "Installing $(basename "$DMG") ..."
MOUNT="$(hdiutil attach -readonly -nobrowse "$DMG" | grep -oE '/Volumes/.*$' | tail -1)"
trap 'hdiutil detach "$MOUNT" -quiet || true' EXIT

SOURCE_APP="$(find "$MOUNT" -maxdepth 1 -name '*.app' -print -quit)"
if [[ -z "$SOURCE_APP" ]]; then
  echo "No .app bundle found inside $DMG" >&2
  exit 1
fi

rm -rf "$APP"
ditto "$SOURCE_APP" "$APP"
# Ad-hoc signed local build: strip quarantine so Gatekeeper allows opening it.
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true

echo "Installed $APP"
open "$APP"
