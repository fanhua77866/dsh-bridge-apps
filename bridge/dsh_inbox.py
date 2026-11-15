# -*- coding: utf-8 -*-
"""DSH 手机收件箱（push to phone）

agent / 脚本在本机把结果文件推过来，手机 App 轮询收件箱并弹通知：

    POST /_bridge/push                 （只允许 127.0.0.1，agent 本机调用）
        {"paths": ["F:\\...\\结果.zip"], "note": "给用户的说明"}
        目录会自动打包成 zip 再入箱。
    GET  /files/api/inbox?since=<ts>   （设备 cookie；?since 只返回更新的条目）
    GET  /files/inbox/<id>             （设备 cookie；下载/支持 Range）

元数据存 <bridge>/inbox/inbox.json，保留最近 100 条；文件只引用原始路径，
不复制（目录推送时生成的 zip 存 inbox 目录）。
"""

from __future__ import annotations

import json
import os
import secrets
import threading
import time
import urllib.parse
import zipfile

from dsh_files import _serve_file

COOKIE_NAME = "dsh_phone"
INBOX_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "inbox")
META_FILE = os.path.join(INBOX_DIR, "inbox.json")
KEEP = 100
ZIP_LIMIT_MB = 2048
ZIP_MAX_FILES = 20000
_SKIP_DIRS = {
    "node_modules", ".git", ".gradle", "__pycache__", ".venv", "venv", ".idea",
    "System Volume Information", "$RECYCLE.BIN", "Config.Msi",
}

_lock = threading.Lock()


# ---------------------------------------------------------------- 存储

def _ensure_dir() -> None:
    os.makedirs(INBOX_DIR, exist_ok=True)


def _load() -> list[dict]:
    _ensure_dir()
    try:
        with open(META_FILE, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
    except (OSError, ValueError):
        pass
    return []


def _save(items: list[dict]) -> None:
    _ensure_dir()
    tmp = META_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(items[:KEEP], fh, ensure_ascii=False, indent=1)
    os.replace(tmp, META_FILE)


def _public(item: dict) -> dict:
    return {
        "id": item.get("id", ""),
        "name": item.get("name", ""),
        "size": int(item.get("size", 0) or 0),
        "mtime": int(item.get("mtime", 0) or 0),
        "pushedAt": int(item.get("pushedAt", 0) or 0),
        "note": item.get("note", ""),
        "kind": item.get("kind", "file"),
    }


def _zip_dir(src: str) -> dict:
    base = os.path.basename(src.rstrip("\\/")) or "folder"
    _ensure_dir()
    target = os.path.join(INBOX_DIR, "%s-%s.zip" % (base, secrets.token_hex(3)))
    limit = ZIP_LIMIT_MB * 1024 * 1024
    total = 0
    count = 0
    parent = os.path.dirname(src.rstrip("\\/")) or src
    try:
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            for root, dirs, names in os.walk(src):
                dirs[:] = sorted(d for d in dirs
                                 if d not in _SKIP_DIRS and not d.startswith("."))
                for name in names:
                    if name.startswith("."):
                        continue
                    full = os.path.join(root, name)
                    try:
                        size = os.stat(full).st_size
                    except OSError:
                        continue
                    total += size
                    count += 1
                    if total > limit:
                        raise ValueError("目录超过 %d MB 打包上限" % ZIP_LIMIT_MB)
                    if count > ZIP_MAX_FILES:
                        raise ValueError("目录文件数超过 %d" % ZIP_MAX_FILES)
                    try:
                        zf.write(full, os.path.relpath(full, parent))
                    except (OSError, ValueError):
                        continue
    except Exception:
        try:
            os.unlink(target)
        except OSError:
            pass
        raise
    return {"path": target, "name": base + ".zip", "kind": "zip"}


def _ingest(raw_path: str, note: str) -> dict:
    path = os.path.abspath(os.path.expanduser(raw_path.strip().strip('"')))
    if not os.path.exists(path):
        raise FileNotFoundError("路径不存在：%s" % raw_path)
    if os.path.isdir(path):
        info = _zip_dir(path)
    else:
        info = {"path": path, "name": os.path.basename(path), "kind": "file"}
    stat = os.stat(info["path"])
    return {
        "id": secrets.token_hex(6),
        "path": info["path"],
        "name": info["name"],
        "size": stat.st_size,
        "mtime": int(stat.st_mtime),
        "pushedAt": int(time.time()),
        "note": note,
        "kind": info["kind"],
    }


# ---------------------------------------------------------------- 路由

def handle_push(handler) -> bool:
    """POST /_bridge/push —— 只允许回环地址（agent 在电脑本机调用）。"""
    path = handler.path.split("?", 1)[0]
    if path != "/_bridge/push":
        return False
    if handler.command != "POST":
        handler._send_json(405, {"ok": False, "error": "只支持 POST"})
        return True
    if not handler._is_loopback():
        handler._send_json(403, {"ok": False, "error": "推送只允许从电脑本机发起"})
        return True

    length = int(handler.headers.get("Content-Length") or 0)
    raw = handler.rfile.read(length) if length else b"{}"
    try:
        payload = json.loads(raw.decode("utf-8") or "{}")
    except (ValueError, UnicodeDecodeError):
        payload = {}

    paths = payload.get("paths")
    if not paths and payload.get("path"):
        paths = [payload["path"]]
    if isinstance(paths, str):
        paths = [paths]
    if not isinstance(paths, list):
        paths = []
    note = str(payload.get("note") or "").strip()[:300]

    results: list[dict] = []
    accepted = 0
    with _lock:
        items = _load()
        for item_path in paths:
            try:
                item = _ingest(str(item_path), note)
            except Exception as exc:  # noqa: BLE001
                results.append({"error": "%s: %s" % (item_path, exc)})
                continue
            items.insert(0, item)
            accepted += 1
            results.append(_public(item))
        _save(items)

    handler.log_message("推送 %d 个文件到手机：%s" % (accepted, note or "-"))
    handler._send_json(200, {"ok": True, "count": accepted, "items": results})
    return True


def handle_files(handler, method: str) -> bool:
    """GET /files/api/inbox 与 GET /files/inbox/<id>。"""
    path = handler.path.split("?", 1)[0]
    if path != "/files/api/inbox" and not path.startswith("/files/inbox/"):
        return False
    if handler.bridge.verify(handler._cookie(COOKIE_NAME)) is None:
        handler._send_json(401, {"ok": False, "error": "未配对，请先在 App 里连接"})
        return True

    if path == "/files/api/inbox":
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query)
        try:
            since = int((query.get("since") or ["0"])[0])
        except (TypeError, ValueError):
            since = 0
        with _lock:
            items = _load()
        if since > 0:
            items = [item for item in items if int(item.get("pushedAt", 0) or 0) > since]
        handler._send_json(200, {
            "ok": True,
            "serverTime": int(time.time()),
            "items": [_public(item) for item in items],
        })
        return True

    item_id = path[len("/files/inbox/"):].strip("/")
    with _lock:
        item = next((it for it in _load() if it.get("id") == item_id), None)
    if item is None:
        handler._send_json(404, {"ok": False, "error": "收件箱里没有这条"})
        return True
    target = item.get("path") or ""
    if not os.path.isfile(target):
        handler._send_json(404, {"ok": False, "error": "电脑上的这个文件已被移动或删除"})
        return True
    _serve_file(handler, method, target, item.get("name") or os.path.basename(target))
    return True
