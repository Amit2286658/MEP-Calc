#!/usr/bin/env python3
"""Print the tap target (center "x y") of a tab node from a uiautomator dump.

Usage: tap_tab.py window_dump.xml "Currency Converter"

Matches the first node whose content-desc (or text) equals the label and prints
its bounds center. Exits non-zero when the node is not present, so a CI leg can
fall back to its normal capture instead of tapping the wrong place.
"""
import re
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) != 3:
        return 2
    root = ET.parse(sys.argv[1]).getroot()
    for node in root.iter("node"):
        if node.get("content-desc") == sys.argv[2] or node.get("text") == sys.argv[2]:
            values = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
            if len(values) == 4:
                print((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
                return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
