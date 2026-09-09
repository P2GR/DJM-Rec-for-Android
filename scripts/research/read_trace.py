"""Inspect exported traces locally. Does not label arbitrary strings as track metadata."""
import argparse
import json
from collections import Counter
from pathlib import Path


def read(path, show=False):
    counts = Counter()
    with path.open(encoding="utf-8") as source:
        for number, line in enumerate(source, 1):
            event = json.loads(line)
            if "schema" in event:
                print(json.dumps(event))
                continue
            raw = bytes.fromhex(event["hex"])
            if len(raw) != event["capturedLength"] or len(raw) > event["length"]:
                raise ValueError(f"Invalid payload lengths at line {number}")
            counts[event["kind"]] += 1
            if show:
                print(event["wallMs"], event["monotonicNs"], event["kind"], event["detail"])
                if event["kind"] == "usb-native-batch":
                    print(raw.decode("utf-8"))
                else:
                    print(raw.hex(" "))
    print(json.dumps(dict(counts), indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--show", action="store_true", help="Print all raw payloads (may contain private metadata)")
    args = parser.parse_args()
    read(args.trace, args.show)
