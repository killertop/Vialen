"""Export original SVG geometry; never crops or edits the reference image.
Requires cairosvg. Run from any directory.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
import cairosvg
ROOT = Path(__file__).resolve().parents[2]
MASTER = Path(__file__).with_name("master.svg")
NS = "{http://www.w3.org/2000/svg}"
def render(destination, size, foreground=False, legacy=False):
    svg = ET.fromstring(MASTER.read_text())
    background = svg.find(NS + "rect")
    if foreground:
        svg.remove(background)
    elif legacy:
        svg.set("viewBox", "14 14 80 80")
        background.attrib.update(x="14", y="14", width="80", height="80", rx="18")
    else:
        svg.set("viewBox", "14 14 80 80")
    cairosvg.svg2png(bytestring=ET.tostring(svg), write_to=str(destination), output_width=size, output_height=size)
for density, size in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
    directory = ROOT / "app/src/main/res" / ("mipmap-" + density)
    render(directory / "ic_launcher.png", size, legacy=True)
    render(directory / "ic_launcher_foreground.png", round(size * 2.25), foreground=True)
render(ROOT / "app/src/main/ic_launcher-playstore.png", 512)
