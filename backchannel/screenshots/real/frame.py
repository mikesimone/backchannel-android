#!/usr/bin/env python3
"""Frame real emulator screenshots for the Play listing: 1080x1920, caption on top."""
import sys
from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 1920
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def frame(src, out, title, sub):
    bg = Image.new("RGB", (W, H))
    top, bottom = (0x3B, 0x2A, 0x5C), (0x8A, 0x4F, 0x86)  # amethyst to mauve-pink
    d = ImageDraw.Draw(bg)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    d.text((70, 70), title, font=ImageFont.truetype(BOLD, 64), fill=(0xF7, 0xEE, 0xFF))
    d.text((70, 160), sub, font=ImageFont.truetype(REG, 36), fill=(0xE9, 0xD6, 0xF5))
    shot = Image.open(src).convert("RGB")
    h = H - 290 - 60
    w = int(shot.width * h / shot.height)
    shot = shot.resize((w, h), Image.LANCZOS)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=48, fill=255)
    x = (W - w) // 2
    ImageDraw.Draw(bg).rounded_rectangle([x - 10, 280, x + w + 9, 290 + h + 9], radius=56, fill=(0x1A, 0x14, 0x20))
    bg.paste(shot, (x, 290), mask)
    bg.save(out, optimize=True)


if __name__ == "__main__":
    frame(*sys.argv[1:5])
