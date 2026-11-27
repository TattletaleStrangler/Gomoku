"""Previews the launcher icon and checks its geometry.

The icon itself is a vector (`app/src/main/res/drawable/ic_launcher_foreground.xml`);
this script repeats the same numbers in Pillow, so the drawing can be looked at — and
checked — without installing the app on a device:

    python tools/icon_preview.py            # print the geometry check only
    python tools/icon_preview.py out/       # ... and write the previews into out/

The check samples the rendered pixels: the cut has to pass through the centre of every
stone, with the stone's own colour on both sides of it. That is what makes the row read
as sliced through rather than merely crossed out, and it is easy to lose by moving the
cut a couple of units.

The previews are drawn from the safe zone of the adaptive icon (the inner 72 of the 108
units), which is what a launcher shows: 512×512 to look at, and a small one to judge
legibility at the size the home screen actually uses.
"""
import os
import sys

from PIL import Image, ImageDraw

BACKGROUND = (0x4A, 0x90, 0xD9)
BLADE = (0xEA, 0xF4, 0xFF)
BLACK = (0x1A, 0x1A, 0x1A)
WHITE = (0xFF, 0xFF, 0xFF)

# Five stones in a row: radius 7, 12 units apart, centred on y = 54 of the 108 viewport.
RADIUS = 7.0
STONES = [(30, 54, BLACK), (42, 54, BLACK), (54, 54, BLACK), (66, 54, WHITE), (78, 54, BLACK)]

# The cut, from ic_launcher_foreground.xml: the slice and the blade's edge.
SLICE = [(12.66, 61.6), (95.84, 49.96), (95.34, 46.4), (12.16, 58.04)]
BLADE_EDGE = [(20.33, 58.71), (37.27, 57.15), (54.12, 54.89), (71.95, 52.43),
              (87.67, 49.29), (71.73, 50.85), (53.88, 53.11), (37.05, 55.57)]

# Through (54, 54) with a slope of -0.14, i.e. about 8 degrees.
SLOPE = -0.14
SAFE_ZONE = (18, 18, 90, 90)

SUPERSAMPLE = 4


def cut_y(x):
    """Height of the cut above a given column."""
    return 54.0 + SLOPE * (x - 54.0)


def render(path, crop, size, monochrome=False, background=BACKGROUND):
    side = size * SUPERSAMPLE
    image = Image.new("RGB", (side, side), background)
    draw = ImageDraw.Draw(image)
    scale_x = side / (crop[2] - crop[0])
    scale_y = side / (crop[3] - crop[1])

    def at(x, y):
        return ((x - crop[0]) * scale_x, (y - crop[1]) * scale_y)

    for cx, cy, colour in STONES:
        draw.ellipse([at(cx - RADIUS, cy - RADIUS), at(cx + RADIUS, cy + RADIUS)],
                     fill=WHITE if monochrome else colour)

    # The slice is the colour of the background layer, which is exactly what the
    # monochrome layer achieves with two clipped halves of the same stones.
    draw.polygon([at(*point) for point in SLICE], fill=background)
    if not monochrome:
        draw.polygon([at(*point) for point in BLADE_EDGE], fill=BLADE)

    resized = image.resize((size, size), Image.LANCZOS)
    if path:
        resized.save(path)
    return resized


def check():
    """Prints the colour of the cut and of both its sides for every stone."""
    preview = render(None, SAFE_ZONE, 512)
    pixels = preview.load()
    scale = 512 / (SAFE_ZONE[2] - SAFE_ZONE[0])

    def sample(x, y):
        return pixels[int(round((x - SAFE_ZONE[0]) * scale)),
                      int(round((y - SAFE_ZONE[1]) * scale))]

    def close(colour, target, tolerance=12):
        return all(abs(a - b) <= tolerance for a, b in zip(colour, target))

    ok = True
    print("stone  cut centre   above        below")
    for cx, cy, colour in STONES:
        y = cut_y(cx)
        centre, above, below = sample(cx, y), sample(cx, y - 2.6), sample(cx, y + 2.6)
        good = close(centre, BLADE) and close(above, colour) and close(below, colour)
        ok = ok and good
        print(f"{cx:>5}  {centre} {'blade' if close(centre, BLADE) else 'NOT BLADE'}  "
              f"{above} {'stone' if close(above, colour) else 'NOT STONE'}  "
              f"{below} {'stone' if close(below, colour) else 'NOT STONE'}")
    print("geometry:", "ok" if ok else "BROKEN")
    return ok


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else None
    ok = check()
    if out:
        os.makedirs(out, exist_ok=True)
        render(os.path.join(out, "icon-512.png"), SAFE_ZONE, 512)
        render(os.path.join(out, "icon-96.png"), SAFE_ZONE, 96)
        render(os.path.join(out, "icon-mono-512.png"), SAFE_ZONE, 512,
               monochrome=True, background=(0x55, 0x55, 0x55))
        print("previews written to", out)
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
