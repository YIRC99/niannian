"""Build the bundled garden-sign and sowing-note font from the official source TTF."""

import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont


CHARACTERS = (
    "慢一点也没关系"
    "花园里，已种下 株走进我的花园花园暂未更新，点此查看0123456789-"
    "小小的一件事也会长出美好的花让生活的每一件小事都被温柔记住念念"
    "种下小小的种子收获更好的自己把小事种成美好的自己"
)
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "android/app/src/main/res/font/garden_handwriting.ttf"
LICENSE = Path(__file__).with_name("garden-handwriting-OFL.txt")


def main():
    source = Path(sys.argv[1])
    font = TTFont(source, recalcTimestamp=False)
    requested = set(map(ord, CHARACTERS))
    missing = requested - font.getBestCmap().keys()
    if missing:
        raise ValueError(f"Source font misses: {sorted(missing)}")

    options = subset.Options()
    options.name_IDs = ["*"]
    options.name_legacy = True
    options.name_languages = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=requested)
    subsetter.subset(font)

    names = {
        1: "Niannian Garden Hand",
        2: "Regular",
        3: "NiannianGardenHand-Regular;1.0;subset-406197b9",
        4: "Niannian Garden Hand Regular",
        5: "Version 2.003; Niannian subset 1.0",
        6: "NiannianGardenHand-Regular",
        13: LICENSE.read_text(encoding="utf-8"),
        16: "Niannian Garden Hand",
        17: "Regular",
        18: "Niannian Garden Hand Regular",
        21: "Niannian Garden Hand",
        22: "Regular",
    }
    for record in list(font["name"].names):
        if record.nameID in names:
            font["name"].setName(
                names[record.nameID], record.nameID,
                record.platformID, record.platEncID, record.langID,
            )
    for name_id in (1, 2, 3, 4, 5, 6, 13, 16, 17):
        font["name"].setName(names[name_id], name_id, 3, 1, 0x409)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    font.save(OUTPUT)
    with TTFont(OUTPUT) as bundled:
        missing = requested - bundled.getBestCmap().keys()
        if missing:
            raise ValueError(f"Subset misses: {sorted(missing)}")
    print(f"{OUTPUT}: {OUTPUT.stat().st_size} bytes; all {len(requested)} characters covered")


if __name__ == "__main__":
    main()
