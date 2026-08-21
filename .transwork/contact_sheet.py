"""Tiles a rendered chapter into sheets small enough to look at but large enough to judge."""
import argparse, glob, math, os
from PIL import Image
Image.MAX_IMAGE_PIXELS = None

ap = argparse.ArgumentParser()
ap.add_argument("--out", required=True)
ap.add_argument("--dest", required=True)
ap.add_argument("--cols", type=int, default=4)
ap.add_argument("--per-sheet", type=int, default=12)
ap.add_argument("--width", type=int, default=440)
a = ap.parse_args()

files = sorted(glob.glob(os.path.join(a.out, "*.png")))
files = [f for f in files if not f.endswith(".diff.png")]
os.makedirs(a.dest, exist_ok=True)
for s in range(math.ceil(len(files) / a.per_sheet)):
    chunk = files[s * a.per_sheet:(s + 1) * a.per_sheet]
    thumbs = []
    for f in chunk:
        im = Image.open(f).convert("RGB")
        h = int(im.height * a.width / im.width)
        thumbs.append((os.path.basename(f), im.resize((a.width, h), Image.LANCZOS)))
    rows = math.ceil(len(thumbs) / a.cols)
    rh = max(t.height for _, t in thumbs)
    sheet = Image.new("RGB", (a.cols * (a.width + 8), rows * (rh + 8)), "white")
    for i, (_, t) in enumerate(thumbs):
        sheet.paste(t, ((i % a.cols) * (a.width + 8), (i // a.cols) * (rh + 8)))
    p = os.path.join(a.dest, f"sheet{s}.png")
    sheet.save(p)
    print(p, [n for n, _ in thumbs])
