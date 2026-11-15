#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把电脑上的文件/文件夹推送到手机（DSH 文件 App）。

agent 用法（任意对话里，用户说「发到我手机 / 推送到手机 / 传到手机」时）：

    python bridge/dsh_push.py <文件或目录> [更多...] --note "给用户的说明"

- 目录会自动打包成 zip 再推送
- 手机 App 会弹通知，点开后即可下载
- 只走本机回环地址，不需要外网 / 不上传任何云服务

退出码：0 = 至少成功推送一个；1 = 全部失败或桥接器没运行。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_URL = "http://127.0.0.1:8799/_bridge/push"


def _human_size(size: int) -> str:
    value = float(size)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return ("%d %s" % (value, unit)) if unit == "B" else ("%.1f %s" % (value, unit))
        value /= 1024.0
    return "%d B" % size


def main() -> int:
    parser = argparse.ArgumentParser(description="推送文件到手机（DSH 文件 App）")
    parser.add_argument("paths", nargs="+", help="要推送的文件或目录（可多个）")
    parser.add_argument("--note", default="", help="显示在手机通知里的说明")
    parser.add_argument("--url", default=DEFAULT_URL, help="桥接器推送地址")
    args = parser.parse_args()

    payload = {
        "paths": [os.path.abspath(os.path.expanduser(p)) for p in args.paths],
        "note": args.note,
    }
    request = urllib.request.Request(
        args.url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = json.loads(exc.read().decode("utf-8")).get("error", "")
        except Exception:  # noqa: BLE001
            pass
        print("推送失败：HTTP %s %s" % (exc.code, detail), file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001
        print("推送失败：%s（桥接器在运行吗？）" % exc, file=sys.stderr)
        return 1

    ok = 0
    for item in data.get("items", []):
        if "error" in item:
            print("未推送：%s" % item["error"], file=sys.stderr)
            continue
        ok += 1
        note = ("（%s）" % item["note"]) if item.get("note") else ""
        print("已推送：%s · %s%s" % (item["name"], _human_size(int(item.get("size", 0))), note))
    if ok:
        print("手机 App 会收到通知，点开即可下载。")
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
