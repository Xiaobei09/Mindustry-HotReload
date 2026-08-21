#!/usr/bin/env bash
# HOTRELOAD dev loop:
#   1. compiles core classes continuously (the JVM agent hot-swaps them into the running game)
#   2. starts the game with the agent attached and the overlay mod deployed to devdata/mods
#
# Usage:
#   scripts/dev-run.sh            # start game (blocking)
#   scripts/dev-run.sh --headless # start headless server instead
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_OPTS="${JAVA_OPTS:-}"

# keep core compiling in the background so saves apply live via the agent
echo "[hotreload] starting continuous core compiler..."
./gradlew :core:compileJava --continuous &
COMPILE_PID=$!
trap 'kill $COMPILE_PID 2>/dev/null || true' EXIT

echo "[hotreload] starting game (hot-swap agent + overlay watcher)..."
if [ "${1:-}" = "--headless" ]; then
    exec ./gradlew :server:run -Pargs="[host]" "$@"
else
    exec ./gradlew :desktop:hotreloadRun "$@"
fi