#!/usr/bin/env bash
# Generates the demo "overlay" mod (content-only, no compiled code) so it runs on any build.
# Usage: make-overlay.sh <output-dir-or-zip-path>
#   dir ending in .zip -> packaged zip; otherwise a directory mod is written.
set -euo pipefail

OUT="${1:-devdata/mods/overlay}"
mkdir -p "$(dirname "$OUT")"
OUT="$(readlink -f "$OUT")"
TMP=""
if [[ "$OUT" == *.zip ]]; then
  TMP="$(mktemp -d)"
  DIR="$TMP/overlay"
else
  DIR="$OUT"
fi

mkdir -p "$DIR/content/items" "$DIR/content/blocks" "$DIR/sprites"

cat > "$DIR/mod.hjson" <<'EOF'
name: "overlay"
displayName: "[orange]Overlay[] (hot reload)"
description: "Demo mod for the hot-reload workflow. Drop it in mods/, edit its content files while the game runs, and watch changes apply live."
author: "Mindustry-HotReload"
version: "1.0.0"
minGameVersion: "146"
hidden: false
EOF

cat > "$DIR/content/items/demo-ore.json" <<'EOF'
{
  name: demo-ore
  color: "b8a36b"
  hardness: 3
  cost: 1.5
  explosiveness: 0.2
}
EOF

cat > "$DIR/content/items/demo-plasma.json" <<'EOF'
{
  name: demo-plasma
  color: "ff55ff"
  cost: 2
}
EOF

cat > "$DIR/content/blocks/demo-panel.json" <<'EOF'
{
  name: demo-panel
  type: Wall
  category: defense
  size: 5
  health: 2500
  requirements: [
    copper/10
  ]
}
EOF

cat > "$DIR/content/blocks/demo-cannon.json" <<'EOF'
{
  name: demo-cannon
  type: Turret
  category: turret
  size: 3
  health: 2000
  range: 210
  inaccuracy: 2
  reload: 10
  shots: 2
  shootCone: 8
  rotateSpeed: 8
  targetAir: true
  targetGround: true
  requirements: [
    copper/20
    lead/10
  ]
  shoot: {
    bullet: {
      type: basic
      damage: 30
      speed: 4
      lifetime: 60
      width: 8
      height: 14
      frontColor: "ffffaa"
      backColor: "ff8a00"
    }
  }
}
EOF

b64() { base64 -d > "$DIR/sprites/$1" <<< "$2"; }

b64 demo-ore.png iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKklEQVR4nO3NQQkAAAgEsOtrLEsYzxQ+hMH+y3SdikAgEAgEAoFAIPgSLEVrGGqA16DvAAAAAElFTkSuQmCC

b64 demo-panel.png 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

b64 demo-cannon.png iVBORw0KGgoAAAANSUhEUgAAAGAAAABgAgMAAACf9p+rAAAAIGNIUk0AAHomAACAhAAA+gAAAIDoAAB1MAAA6mAAADqYAAAXcJy6UTwAAAAMUExURSp6/9DQ0BoaSv/////23ZkAAAABYktHRAMRDEzyAAAAB3RJTUUH6ggUERMs2tP9ewAAAClJREFUSMdjYICAUDhwYEABoxKjEqMSw19iFAw/sAoHGJUYlRiVoIsEACBn0EEKYeCeAAAAJXRFWHRkYXRlOmNyZWF0ZQAyMDI2LTA4LTIwVDE3OjE5OjQ0KzAwOjAwDhKI/QAAACV0RVh0ZGF0ZTptb2RpZnkAMjAyNi0wOC0yMFQxNzoxOTo0NCswMDowMH9PMEEAAAAodEVYdGRhdGU6dGltZXN0YW1wADIwMjYtMDgtMjBUMTc6MTk6NDQrMDA6MDAoWhGeAAAAAElFTkSuQmCC

if [[ -n "$TMP" ]]; then
  (cd "$TMP" && zip -qr "$OUT" overlay)
  rm -rf "$TMP"
  echo "Wrote $OUT"
else
  echo "Wrote directory mod at $DIR"
fi
