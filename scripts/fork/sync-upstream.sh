#!/usr/bin/env bash
# Fetch upstream/main and merge (or rebase) it into the current branch.
# Usage:
#   ./scripts/fork/sync-upstream.sh [--rebase] [--dry-run]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODE="merge"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebase) MODE="rebase"; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help)
      sed -n '2,4p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

cd "$REPO_ROOT"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit or stash your changes first." >&2
  exit 1
fi

echo "Fetching upstream..."
git fetch upstream

INCOMING="$(git rev-list --count HEAD..upstream/main)"
echo "Incoming commits from upstream/main: $INCOMING"
if [[ "$INCOMING" -eq 0 ]]; then
  echo "Already up to date."
  exit 0
fi

git log --oneline -n 20 HEAD..upstream/main

if [[ "$DRY_RUN" -eq 1 ]]; then
  exit 0
fi

LOCK_BEFORE="$(git hash-object pnpm-lock.yaml 2>/dev/null || true)"

if [[ "$MODE" == "rebase" ]]; then
  git rebase upstream/main
else
  git merge upstream/main
fi

LOCK_AFTER="$(git hash-object pnpm-lock.yaml 2>/dev/null || true)"
if [[ "$LOCK_BEFORE" != "$LOCK_AFTER" ]]; then
  echo "pnpm-lock.yaml changed; running vp i..."
  vp i
fi

echo "Synced with upstream/main."
