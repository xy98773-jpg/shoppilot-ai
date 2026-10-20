# -*- coding: utf-8 -*-
"""R148/V3 取证辅助：避免在 shell 里嵌套引号写 Python 单行（首轮即因此产出空值）。

子命令：
  field  <file> <dotted.path>          打印字段值（None -> 空串）
  isnull <file> <dotted.path>          字段为 null/缺失 -> 1，否则 0
  num_ge <file> <dotted.path> <n>      数值 >= n -> 1，否则 0
  keys   <listfile> <index>            打印 data.list[i] 的键（逗号分隔，排序）
  cmp    <listfile> <detailfile>       列表元素与详情 data 深度相等 -> IDENTICAL/DIFF/NOT_FOUND
"""
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def dig(node, dotted):
    for key in dotted.split("."):
        if not key:
            continue
        if isinstance(node, dict):
            if key not in node:
                return None
            node = node[key]
        elif isinstance(node, list) and key.lstrip("-").isdigit():
            idx = int(key)
            if idx >= len(node):
                return None
            node = node[idx]
        else:
            return None
    return node


def main():
    cmd = sys.argv[1]
    if cmd == "field":
        value = dig(load(sys.argv[2]), sys.argv[3])
        print("" if value is None else value)
    elif cmd == "isnull":
        node = dig(load(sys.argv[2]), sys.argv[3])
        print(1 if node is None else 0)
    elif cmd == "islist":
        node = dig(load(sys.argv[2]), sys.argv[3])
        print(1 if isinstance(node, list) else 0)
    elif cmd == "num_ge":
        node = dig(load(sys.argv[2]), sys.argv[3])
        print(1 if isinstance(node, (int, float)) and node >= float(sys.argv[4]) else 0)
    elif cmd == "keys":
        rows = dig(load(sys.argv[2]), "data.list") or []
        idx = int(sys.argv[3])
        print(",".join(sorted(rows[idx].keys())) if idx < len(rows) else "")
    elif cmd == "cmp":
        rows = dig(load(sys.argv[2]), "data.list") or []
        detail = dig(load(sys.argv[3]), "data") or {}
        matched = [row for row in rows if row.get("run_id") == detail.get("run_id")]
        if not matched:
            print("NOT_FOUND")
        else:
            print("IDENTICAL" if matched[0] == detail else "DIFF")
    else:
        raise SystemExit("unknown command: " + cmd)


if __name__ == "__main__":
    main()
