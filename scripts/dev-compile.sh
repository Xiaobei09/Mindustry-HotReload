#!/usr/bin/env bash
# HOTRELOAD: continuously compile core + overlay.
# Core changes are hot-swapped into the running game by the JVM agent;
# overlay changes trigger the in-game overlay reload automatically.
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew :core:compileJava :overlay:syncDev --continuous "$@"