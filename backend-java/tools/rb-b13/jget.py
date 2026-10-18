#!/usr/bin/env python
"""Tiny JSON field extractor for the B13 probe script.

Usage:  echo '<json>' | python jget.py detail.errors.0.msg
Prints an empty line when the path is missing (so bash comparisons stay simple).
"""
from __future__ import annotations

import json
import sys


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        data = json.load(sys.stdin)
    except json.JSONDecodeError:
        print("")
        return 0
    for key in path.split(".") if path else []:
        if isinstance(data, dict):
            data = data.get(key)
        elif isinstance(data, list):
            try:
                data = data[int(key)]
            except (ValueError, IndexError):
                data = None
        else:
            data = None
        if data is None:
            print("")
            return 0
    if isinstance(data, (dict, list)):
        print(json.dumps(data, ensure_ascii=False))
    elif isinstance(data, bool):
        print("true" if data else "false")
    else:
        print(data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
