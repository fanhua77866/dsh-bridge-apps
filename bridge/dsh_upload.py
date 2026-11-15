# -*- coding: utf-8 -*-
"""手机 → 电脑 文件上传。

接口（都需要设备 cookie，即手机 App 已配对）：

    GET  /files/api/upload-info   返回默认上传目录、可写根目录、单文件大小上限
    POST /files/upload?dir=<abs>&name=<file>
         raw body = 文件内容（必须带 Content-Length）
    GET  /files/api/uploads?limit=20   默认上传目录里最近收到的文件

默认上传目录：config.json 的 upload_dir；未配置则 <workspace>/手机上传。
"""

from __future__ import annotations

import os
import re
import time
import urllib.parse

import dsh_files

COOKIE_NAME = "dsh_phone"
DEFAULT_MAX_MB = 4096


def _cfg(handler) -> dict:
    return handler.bridge.cfg or {}


def _upload_dir(handler) -> str:
    cfg = _cfg(handler)
    raw = str(cfg.get("upload_dir") or "").strip()
    if raw:
        return os.path.abspath(os.path.expanduser(raw))
    ws = str(cfg.get("workspace") or "").strip()
    base = os.path.abspath(ws) if ws and os.path.isdir(ws) else os.path.expanduser("~")
    return os.path.join(base, "手机上传")


def _max_bytes(handler) -> int:
    try:
        mb = int(_cfg(handler).get("upload_max_mb", DEFAULT_MAX_MB))
    except (TypeError, ValueError):
        mb = DEFAULT_MAX_MB
    return max(1, mb) * 1024 * 1024


def handle(handler, method: str) -> bool:
    path = handler.path.split("?", 1)[0]
    if not path.startswith("/files/api/upload") and path != "/files/upload":
        return False
    if handler.bridge.verify(handler._cookie(COOKIE_NAME)) is None:
        handler._send_json(401, {"ok": False, "error": "未配对，请先在 App 里连接"})
        return True

    if path == "/files/api/upload-info":
        default = _upload_dir(handler)
        os.makedirs(default, exist_ok=True)
        roots = [{"label": label, "path": root}
                 for label, root in dsh_files._roots(handler)]
        handler._send_json(200, {
            "ok": True,
            "defaultDir": default,
            "roots": roots,
            "maxMb": _max_bytes(handler) // (1024 * 1024),
        })
        return True

    if path == "/files/api/uploads":
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query)
        try:
            limit = max(1, min(100, int((query.get("limit") or ["20"])[0])))
        except (TypeError, ValueError):
            limit = 20
        folder = _upload_dir(handler)
        items = []
        if os.path.isdir(folder):
            for name in os.listdir(folder):
                full = os.path.join(folder, name)
                try:
                    if not os.path.isfile(full):
                        continue
                    stat = os.stat(full)
                except OSError:
                    continue
                items.append({"name": name, "size": stat.st_size, "mtime": int(stat.st_mtime)})
        items.sort(key=lambda item: item["mtime"], reverse=True)
        handler._send_json(200, {"ok": True, "dir": folder, "items": items[:limit]})
        return True

    if path == "/files/upload" and method == "POST":
        _handle_upload(handler)
        return True

    handler._send_json(404, {"ok": False, "error": "not found"})
    return True


def _sanitize(name: str) -> str:
    cleaned = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip()
    return cleaned[:180] or ("upload-" + time.strftime("%Y%m%d-%H%M%S"))


def _resolve_dir(handler, raw_dir: str) -> str:
    default = _upload_dir(handler)
    if not raw_dir.strip():
        os.makedirs(default, exist_ok=True)
        return default
    target = os.path.abspath(os.path.expanduser(raw_dir.strip().strip('"')))
    if target.startswith("\\\\") or target.startswith("//"):
        raise ValueError("不支持网络共享路径")
    roots = [root for _label, root in dsh_files._roots(handler)]
    real = os.path.realpath(target)
    if roots and not any(dsh_files._within(real, os.path.realpath(root)) for root in roots):
        raise PermissionError("目标目录不在允许范围内")
    if os.path.exists(real) and not os.path.isdir(real):
        raise ValueError("目标不是文件夹")
    os.makedirs(real, exist_ok=True)
    return real


def _unique_path(folder: str, name: str) -> str:
    target = os.path.join(folder, name)
    if not os.path.exists(target):
        return target
    stem, ext = os.path.splitext(name)
    for index in range(1, 1000):
        candidate = os.path.join(folder, "%s-%d%s" % (stem, index, ext))
        if not os.path.exists(candidate):
            return candidate
    raise ValueError("同名文件太多")


def _handle_upload(handler) -> None:
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query)
    raw_dir = (query.get("dir") or [""])[0]
    name = _sanitize((query.get("name") or [""])[0] or "upload.bin")

    try:
        length = int(handler.headers.get("Content-Length") or -1)
    except (TypeError, ValueError):
        length = -1
    if length < 0:
        handler._send_json(411, {"ok": False, "error": "缺少 Content-Length"})
        return
    limit = _max_bytes(handler)
    if length > limit:
        handler._send_json(413, {"ok": False,
                                  "error": "文件超过 %d MB 上限" % (limit // (1024 * 1024))})
        return

    try:
        folder = _resolve_dir(handler, raw_dir)
    except PermissionError as exc:
        handler._send_json(403, {"ok": False, "error": str(exc)})
        return
    except (ValueError, OSError) as exc:
        handler._send_json(400, {"ok": False, "error": str(exc)})
        return

    final_path = _unique_path(folder, name)
    remaining = length
    try:
        with open(final_path, "wb") as fh:
            while remaining > 0:
                chunk = handler.rfile.read(min(262144, remaining))
                if not chunk:
                    break
                fh.write(chunk)
                remaining -= len(chunk)
        if remaining != 0:
            raise IOError("数据不完整（还差 %d 字节）" % remaining)
    except Exception as exc:  # noqa: BLE001
        try:
            os.unlink(final_path)
        except OSError:
            pass
        handler._send_json(400, {"ok": False, "error": "接收失败：%s" % exc})
        return

    handler.log_message("手机上传：%s（%d 字节）" % (final_path, length))
    handler._send_json(200, {
        "ok": True,
        "path": final_path,
        "name": os.path.basename(final_path),
        "size": length,
        "dir": folder,
    })
