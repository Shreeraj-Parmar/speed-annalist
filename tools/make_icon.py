"""Draws the launcher icon (a speedometer) into app/res/mipmap-*/ic_launcher.png."""
import math
import os
import sys

from PIL import Image, ImageDraw

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def draw(size):
    s = size * 4  # supersample
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 0, s - 1, s - 1], fill=(15, 23, 42, 255))
    cx, cy, r = s / 2, s * 0.56, s * 0.34
    w = max(4, int(s * 0.07))
    box = [cx - r, cy - r, cx + r, cy + r]
    d.arc(box, 150, 210, fill=(34, 197, 94), width=w)
    d.arc(box, 210, 300, fill=(56, 189, 248), width=w)
    d.arc(box, 300, 390, fill=(245, 158, 11), width=w)
    ang = math.radians(-40)
    nx, ny = cx + r * 0.85 * math.cos(ang), cy + r * 0.85 * math.sin(ang)
    d.line([cx, cy, nx, ny], fill=(241, 245, 249), width=max(3, int(s * 0.045)))
    k = s * 0.06
    d.ellipse([cx - k, cy - k, cx + k, cy + k], fill=(241, 245, 249))
    return img.resize((size, size), Image.LANCZOS)


def main(res_dir):
    for name, px in SIZES.items():
        out = os.path.join(res_dir, "mipmap-" + name)
        os.makedirs(out, exist_ok=True)
        draw(px).save(os.path.join(out, "ic_launcher.png"))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/res")
