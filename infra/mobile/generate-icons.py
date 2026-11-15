"""Render Routy's existing road mark into native and store icons (requires Pillow)."""
from pathlib import Path
import argparse
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
GREEN = "#175F55"
GOLD = "#E8B85A"


def curve(a, b, c, d, steps=64):
    return [tuple((1-t)**3*a[j]+3*(1-t)**2*t*b[j]+3*(1-t)*t*t*c[j]+t**3*d[j] for j in range(2))
            for t in (i/steps for i in range(steps+1))]


def icon(size, transparent=False, circular=False):
    # Same geometry as the SVG brand in RoutyApp; the adaptive foreground fits the 66 dp safe zone.
    scale = 2048 / 42
    image = Image.new("RGBA", (2048, 2048), (0, 0, 0, 0) if transparent or circular else GREEN)
    draw = ImageDraw.Draw(image)
    if circular:
        draw.ellipse((0, 0, 2047, 2047), fill=GREEN)
    points = [(14, 31), (14, 16)]
    points += curve((14, 16), (14, 6.667), (28, 6.667), (28, 16))[1:]
    points += curve((28, 16), (28, 22), (19, 22), (19, 26))[1:]
    points += curve((19, 26), (19, 30), (25, 28), (28, 31))[1:]
    draw.line([(round(x*scale), round(y*scale)) for x, y in points], fill="white", width=round(3.5*scale), joint="curve")
    r=1.75
    draw.ellipse(tuple(round(v*scale) for v in (14-r,31-r,14+r,31+r)),fill="white")
    draw.ellipse(tuple(round(v*scale) for v in (25,28,31,34)),fill=GOLD)
    image=image.resize((size,size),Image.Resampling.LANCZOS)
    return image if transparent or circular else image.convert("RGB")


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--store-output",type=Path)
    args=parser.parse_args()
    resources=ROOT/"apps/web/android/app/src/main/res"
    for density,size,foreground in [("mdpi",48,108),("hdpi",72,162),("xhdpi",96,216),("xxhdpi",144,324),("xxxhdpi",192,432)]:
        folder=resources/f"mipmap-{density}"
        icon(size).save(folder/"ic_launcher.png")
        icon(size,circular=True).save(folder/"ic_launcher_round.png")
        icon(foreground,transparent=True).save(folder/"ic_launcher_foreground.png")
    icon(1024).save(ROOT/"apps/web/ios/App/App/Assets.xcassets/AppIcon.appiconset/AppIcon-512@2x.png")
    if args.store_output:
        args.store_output.mkdir(parents=True,exist_ok=True)
        icon(512).save(args.store_output/"google-play-icon-512.png")
        icon(1024).save(args.store_output/"app-icon-master-1024.png")
    print("Generated Android density/adaptive icons and iOS 1024px icon.")


if __name__=="__main__":
    main()
