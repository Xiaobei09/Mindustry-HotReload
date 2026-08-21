#!/usr/bin/env bash
# Injects the hot-reload patch set into a pristine Mindustry source tree.
# Usage: inject-hotreload.sh <path-to-mindustry-checkout>
# Semantic anchors (make-patches.py) fail loudly if the target version drifted.
# Verified injectable for upstream tags >= v142.
set -euo pipefail

TARGET="$(cd "$1" && pwd)"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 1. new files: verbatim copies, zero conflict potential
mkdir -p "$TARGET/core/src/mindustry/mod"
cp "$ROOT/inject/core/src/mindustry/mod/OverlayMods.java" "$TARGET/core/src/mindustry/mod/"
rm -rf "$TARGET/hotreload-agent"
cp -r "$ROOT/inject/hotreload-agent" "$TARGET/hotreload-agent"

# 2. minimal semantic edits to upstream files (strict anchors)
python3 "$ROOT/scripts/make-patches.py" "$TARGET"

echo "Hot-reload injection complete: $TARGET"
