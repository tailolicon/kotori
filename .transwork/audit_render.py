"""Scores a rendered chapter against its sources, one page at a time.

Three defects, all of which the eye catches instantly and a green regression suite does not:

  spill   the letterer drew outside the region it was handed (read from the render trace)
  patch   the repainted background is a different shade from the paper around it, so the
          erase leaves a visible rectangle -- the "nham nho" complaint
  faint   the new lettering has almost no contrast against what it was drawn onto
"""
import argparse, glob, os, re, sys
from collections import Counter
from PIL import Image, ImageChops, ImageFilter

Image.MAX_IMAGE_PIXELS = None
TRACE = re.compile(r"Simple: Rect\((\d+), (\d+) - (\d+), (\d+)\).*bounds=Rect\((\d+), (\d+) - (\d+), (\d+)\)")


def mode_colour(img, box, exclude_dark=True):
    crop = img.crop(box).convert("RGB")
    px = list(crop.getdata())
    if exclude_dark:
        lum = [0.299 * r + 0.587 * g + 0.114 * b for r, g, b in px]
        hi = sorted(lum)[int(len(lum) * 0.6)] if lum else 0
        px = [p for p, l in zip(px, lum) if l >= hi]
    if not px:
        return None
    return Counter(px).most_common(1)[0][0]


def dist(a, b):
    return max(abs(x - y) for x, y in zip(a, b)) if a and b else 0


def components(mask, min_area):
    """Connected boxes of a binary mask, 4-connected, iterative flood."""
    w, h = mask.size
    seen = bytearray(w * h)
    data = mask.load()
    out = []
    for y in range(h):
        for x in range(w):
            i = y * w + x
            if seen[i] or not data[x, y]:
                continue
            stack = [(x, y)]
            seen[i] = 1
            x0 = x1 = x
            y0 = y1 = y
            n = 0
            while stack:
                cx, cy = stack.pop()
                n += 1
                x0, x1 = min(x0, cx), max(x1, cx)
                y0, y1 = min(y0, cy), max(y1, cy)
                for nx, ny in ((cx - 1, cy), (cx + 1, cy), (cx, cy - 1), (cx, cy + 1)):
                    if 0 <= nx < w and 0 <= ny < h:
                        j = ny * w + nx
                        if not seen[j] and data[nx, ny]:
                            seen[j] = 1
                            stack.append((nx, ny))
            if n >= min_area:
                out.append((x0, y0, x1 + 1, y1 + 1))
    return out


def audit(src_path, out_path, trace_path, scale=6, dump=None, name=""):
    src = Image.open(src_path).convert("RGB")
    out = Image.open(out_path).convert("RGB")
    if src.size != out.size:
        return [("size", f"{src.size} vs {out.size}")]
    findings = []

    if trace_path and os.path.exists(trace_path):
        for m in TRACE.finditer(open(trace_path, encoding="utf-8", errors="replace").read()):
            rl, rt, rr, rb, bl, bt, br, bb = map(int, m.groups())
            # Only gross overruns. A block centred inside a balloon legitimately reports bounds
            # that sit above or beside the box it was measured against; what is never legitimate is
            # type set wider or taller than the region it belongs to, or hanging a third of the
            # region's own size out into the artwork.
            # Only "type set larger than the region it belongs to". A block is legitimately
            # re-anchored to the balloon interior the renderer finds on the paper, so its bounds
            # need not nest inside the box it was measured against -- verified by eye on six pages
            # that render perfectly and were flagged by a containment test. What is never
            # legitimate is a translation wider or taller than its own region.
            rw, rh = max(1, rr - rl), max(1, rb - rt)
            if (br - bl) > rw * 1.3 or (bb - bt) > rh * 1.3:
                findings.append(("spill", f"region ({rl},{rt},{rr},{rb}) drew ({bl},{bt},{br},{bb})",
                                 (bl, bt, br, bb)))

    # Where did the render change the page?
    diff = ImageChops.difference(src, out).convert("L").point(lambda v: 255 if v > 28 else 0)
    small = diff.resize((src.width // scale, src.height // scale), Image.BOX).point(lambda v: 255 if v > 40 else 0)
    small = small.filter(ImageFilter.MaxFilter(5))
    for x0, y0, x1, y1 in components(small, min_area=12):
        box = (x0 * scale, y0 * scale, x1 * scale, y1 * scale)
        w, h = box[2] - box[0], box[3] - box[1]
        if w < 24 or h < 16:
            continue
        pad = max(10, min(w, h) // 3)
        ring = (max(0, box[0] - pad), max(0, box[1] - pad),
                min(src.width, box[2] + pad), min(src.height, box[3] + pad))
        inside = mode_colour(out, box)
        around_out = mode_colour(out, ring)
        around_src = mode_colour(src, ring)
        if inside and around_out and dist(inside, around_out) > 14:
            findings.append(("patch", f"{box} fill {inside} vs paper {around_out}", box))
        elif inside and around_src and dist(inside, around_src) > 14:
            findings.append(("patch", f"{box} fill {inside} vs original {around_src}", box))
        # contrast of the new lettering against its own background
        crop = out.crop(box).convert("L")
        vals = sorted(crop.getdata())
        if vals:
            dark = vals[int(len(vals) * 0.03)]
            light = vals[int(len(vals) * 0.9)]
            if light - dark < 55:
                findings.append(("faint", f"{box} ink {dark} on {light}", box))

    if dump and findings:
        os.makedirs(dump, exist_ok=True)
        for n, (kind, detail, box) in enumerate(findings):
            pad = 40
            crop = (max(0, box[0] - pad), max(0, box[1] - pad),
                    min(src.width, box[2] + pad), min(src.height, box[3] + pad))
            a, b = src.crop(crop), out.crop(crop)
            sheet = Image.new("RGB", (a.width * 2 + 12, a.height), (255, 0, 0))
            sheet.paste(a, (0, 0))
            sheet.paste(b, (a.width + 12, 0))
            sheet.save(os.path.join(dump, f"{name}-{n:02d}-{kind}.png"))
    return findings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dump")
    args = ap.parse_args()
    bad = 0
    for s in sorted(glob.glob(os.path.join(args.src, "*"))):
        name = os.path.splitext(os.path.basename(s))[0]
        o = os.path.join(args.out, name + ".png")
        if not os.path.exists(o):
            print(f"{name}: MISSING RENDER")
            bad += 1
            continue
        f = audit(s, o, os.path.join(args.out, name + ".trace.txt"),
                  dump=args.dump, name=name)
        if f:
            bad += 1
            print(f"{name}: {len(f)} finding(s)")
            for kind, detail, _ in f[:8]:
                print(f"    {kind:6} {detail}")
        else:
            print(f"{name}: clean")
    print(f"\n{bad} page(s) with findings")


if __name__ == "__main__":
    main()
