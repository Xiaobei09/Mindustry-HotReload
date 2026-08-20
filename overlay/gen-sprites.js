#!/usr/bin/env node
// Generates placeholder PNG sprites for the overlay mod (pure Node, no deps).
const fs = require("fs");
const zlib = require("zlib");
const path = require("path");

const OUT = path.join(__dirname, "sprites");

function png(file, w, h, px) {
  const chunk = (tag, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(zlib.crc32(Buffer.concat([tag, data])) >>> 0);
    return Buffer.concat([len, tag, data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2; // 8-bit RGB
  const raw = Buffer.alloc(h * (1 + w * 3));
  for (let y = 0; y < h; y++) {
    raw[y * (1 + w * 3)] = 0;
    px.copy(raw, y * (1 + w * 3) + 1, y * w * 3, (y + 1) * w * 3);
  }
  const buf = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk(Buffer.from("IHDR"), ihdr),
    chunk(Buffer.from("IDAT"), zlib.deflateSync(raw)),
    chunk(Buffer.from("IEND"), Buffer.alloc(0)),
  ]);
  fs.writeFileSync(file, buf);
}

function blockSprite(rgb, edge = [40, 40, 40], w = 64, h = 64) {
  const px = Buffer.alloc(w * h * 3);
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 3;
      const c = x < 3 || y < 3 || x >= w - 3 || y >= h - 3 ? edge : rgb;
      px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2];
    }
  return px;
}

function itemSprite(rgb, w = 32, h = 32) {
  return Buffer.from(Array.from({ length: w * h * 3 }, (_, i) => rgb[i % 3]));
}

fs.mkdirSync(OUT, { recursive: true });
png(path.join(OUT, "demo-generator.png"), 64, 64, blockSprite([240, 200, 60]));
png(path.join(OUT, "demo-wall.png"), 64, 64, blockSprite([120, 140, 220]));
png(path.join(OUT, "silver.png"), 32, 32, itemSprite([200, 216, 232]));
png(path.join(OUT, "demo-ore.png"), 32, 32, itemSprite([184, 163, 107]));
console.log("sprites generated in", OUT);