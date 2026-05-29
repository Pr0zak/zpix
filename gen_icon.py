#!/usr/bin/env python3
from PIL import Image, ImageDraw
import os

OUT = "/home/spider/zandframe/res"
S = 1024

c0 = (0x5b, 0x6b, 0xff)
c1 = (0x8a, 0x4f, 0xf0)
c2 = (0xd2, 0x4b, 0xe0)

def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))

def grad_color(t):
    if t <= 0.55:
        return lerp(c0, c1, t / 0.55)
    return lerp(c1, c2, (t - 0.55) / 0.45)

# diagonal-ish gradient (top-left -> bottom-right)
grad = Image.new("RGB", (S, S))
gd = ImageDraw.Draw(grad)
for y in range(S):
    gd.line([(0, y), (S, y)], fill=grad_color(y / (S - 1)))

# rounded tile mask
margin = int(S * 0.045)
rad = int(S * 0.21)
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([margin, margin, S - margin, S - margin], radius=rad, fill=255)

base = Image.new("RGBA", (S, S), (0, 0, 0, 0))
base.paste(grad, (0, 0), mask)

def card(w, h, rad, fill, alpha=255, content=None):
    layer = Image.new("RGBA", (w, h), (fill[0], fill[1], fill[2], 255))
    if content:
        content(layer)
    rmask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(rmask).rounded_rectangle([0, 0, w - 1, h - 1], radius=rad, fill=alpha)
    layer.putalpha(rmask)
    return layer

# back card (tilted)
bw, bh = int(S * 0.42), int(S * 0.34)
back = card(bw, bh, int(S * 0.045), (255, 255, 255), 128)
back = back.rotate(13, expand=True, resample=Image.BICUBIC)
base.alpha_composite(back, (int(S * 0.5 - back.width / 2), int(S * 0.48 - back.height / 2)))

# front card with sun + mountains
def front_content(layer):
    w, h = layer.size
    d = ImageDraw.Draw(layer)
    r = int(w * 0.10)
    d.ellipse([int(w * 0.17), int(h * 0.17), int(w * 0.17) + 2 * r, int(h * 0.17) + 2 * r], fill=(255, 178, 77, 255))
    d.polygon([(int(w * 0.00), int(h * 1.0)), (int(w * 0.32), int(h * 0.46)),
               (int(w * 0.52), int(h * 0.70)), (int(w * 0.72), int(h * 0.40)),
               (int(w * 1.0), int(h * 1.0))], fill=(74, 79, 134, 255))
    d.polygon([(int(w * 0.52), int(h * 1.0)), (int(w * 0.72), int(h * 0.62)),
               (int(w * 1.0), int(h * 1.0))], fill=(106, 111, 176, 255))

fw, fh = int(S * 0.42), int(S * 0.34)
front = card(fw, fh, int(S * 0.045), (238, 241, 255), 255, front_content)
front = front.rotate(-7, expand=True, resample=Image.BICUBIC)
base.alpha_composite(front, (int(S * 0.5 - front.width / 2), int(S * 0.53 - front.height / 2)))

sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
         "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
for d, sz in sizes.items():
    os.makedirs(os.path.join(OUT, d), exist_ok=True)
    base.resize((sz, sz), Image.LANCZOS).save(os.path.join(OUT, d, "ic_launcher.png"))

# committable logo for the README
os.makedirs("/home/spider/zandframe/docs", exist_ok=True)
base.resize((512, 512), Image.LANCZOS).save("/home/spider/zandframe/docs/logo.png")

# a preview for review (gitignored)
base.resize((256, 256), Image.LANCZOS).save("/home/spider/zandframe/icon_preview.png")
print("icons written to", OUT)
