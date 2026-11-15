# -*- coding: utf-8 -*-
"""DSH 遥控桥接器 · 电脑文件浏览与下载（/files）

挂载点（鉴权与桥接器一致：设备 cookie；下载链接额外带短期 HMAC token）：

    GET /files?path=<dir>               浏览目录（手机友好的 HTML 页面）
    GET /files/download?path=<file>&t=  下载单个文件（支持 Range / HEAD）
    GET /files/zip?path=<dir>&t=        把整个目录打包成 zip 下载
    GET /files/api/list?path=<dir>      JSON 列表（给以后原生界面用）

未配对时本模块直接返回 False，交给桥接器原有代理层去显示配对页 / 401。
允许浏览的范围由 config.json 的 file_roots 决定：
  * 不写 / 写 ["*"]  → 本机所有盘符 + 用户目录 + 工作区
  * 写具体路径列表    → 只允许这些目录（及其子目录）
"""

from __future__ import annotations

import hashlib
import hmac
import html as _html
import mimetypes
import os
import re
import tempfile
import time
import urllib.parse
import zipfile

COOKIE_NAME = "dsh_phone"
_PREFIX = "/files"
_SKIP_NAMES = {"System Volume Information", "$RECYCLE.BIN", "Config.Msi", "Recovery"}
_TOKEN_TTL = 24 * 3600
_CHUNK = 256 * 1024


# ---------------------------------------------------------------- 入口

def handle(handler, method: str) -> bool:
    """处理 /files* 请求；返回 True 表示已经响应，False 表示交给桥接器代理。"""
    raw_path = handler.path.split("?", 1)[0]
    if raw_path != _PREFIX and not raw_path.startswith(_PREFIX + "/"):
        return False

    sub = raw_path[len(_PREFIX):] or "/"
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query, keep_blank_values=True)
    raw_target = (query.get("path") or [""])[0]
    token = (query.get("t") or [""])[0]

    paired = handler.bridge.verify(handler._cookie(COOKIE_NAME)) is not None
    if not paired:
        # 未配对：下载/打包允许走短期 token（方便外部下载器），其余交给代理层出配对页
        if sub in ("/download", "/zip") and raw_target and _token_ok(handler, raw_target, token):
            pass
        else:
            return False

    if method not in ("GET", "HEAD"):
        handler._send_json(405, {"ok": False, "error": "只支持 GET"})
        return True

    try:
        if sub == "/":
            _handle_page(handler, method, raw_target)
        elif sub == "/download":
            _handle_download(handler, method, raw_target)
        elif sub == "/zip":
            _handle_zip(handler, method, raw_target)
        elif sub == "/api/list":
            _handle_api_list(handler, method, raw_target)
        elif sub == "/api/recent":
            _handle_api_recent(handler, method, raw_target)
        elif sub == "/api/roots":
            _handle_api_roots(handler, method, raw_target)
        else:
            handler._send_json(404, {"ok": False, "error": "not found"})
    except FileNotFoundError as exc:
        _fail(handler, method, 404, "找不到路径", str(exc))
    except PermissionError as exc:
        _fail(handler, method, 403, "没有权限", str(exc))
    except ValueError as exc:
        _fail(handler, method, 400, "路径/参数有问题", str(exc))
    except Exception as exc:  # noqa: BLE001
        _fail(handler, method, 500, "服务器内部错误", "%s: %s" % (type(exc).__name__, exc))
    return True


# ---------------------------------------------------------------- 鉴权 token

def _secret(handler) -> bytes:
    return str(handler.bridge.state.get("secret") or "").encode("utf-8")


def _sign(handler, path: str) -> str:
    ts = str(int(time.time()))
    sig = hmac.new(_secret(handler), (ts + "\n" + path).encode("utf-8"),
                   hashlib.sha256).hexdigest()[:32]
    return ts + "." + sig


def _token_ok(handler, path: str, token: str) -> bool:
    try:
        ts_s, sig = token.split(".", 1)
        if int(ts_s) + _TOKEN_TTL < time.time():
            return False
        want = hmac.new(_secret(handler), (ts_s + "\n" + path).encode("utf-8"),
                        hashlib.sha256).hexdigest()[:32]
        return hmac.compare_digest(sig, want)
    except Exception:  # noqa: BLE001
        return False


# ---------------------------------------------------------------- 配置 / 路径

def _cfg(handler) -> dict:
    return handler.bridge.cfg or {}


def _workspace(handler) -> str | None:
    ws = str(_cfg(handler).get("workspace") or "").strip()
    if not ws:
        return None
    try:
        ws = os.path.abspath(ws)
    except Exception:  # noqa: BLE001
        return None
    return ws if os.path.isdir(ws) else None


def _roots(handler) -> list[tuple[str, str]]:
    """允许浏览的根目录列表 [(标签, 绝对路径)]。"""
    raw = _cfg(handler).get("file_roots") or []
    if isinstance(raw, str):
        raw = [raw]
    raw = [str(x).strip() for x in raw if str(x).strip()]

    out: list[tuple[str, str]] = []
    if raw and raw != ["*"]:
        for item in raw:
            p = os.path.abspath(os.path.expanduser(item))
            if os.path.isdir(p):
                out.append((os.path.basename(p.rstrip("\\/")) or p, p))
    else:
        ws = _workspace(handler)
        if ws:
            out.append(("工作区", ws))
        home = os.path.expanduser("~")
        if os.path.isdir(home):
            out.append(("用户目录", home))
        for letter in "CDEFGHIJKLMNOPQRSTUVWXYZ":
            p = letter + ":\\"
            try:
                if os.path.isdir(p):
                    out.append((letter + ": 盘", p))
            except OSError:
                continue

    seen = set()
    dedup: list[tuple[str, str]] = []
    for label, p in out:
        key = os.path.normcase(os.path.abspath(p))
        if key in seen:
            continue
        seen.add(key)
        dedup.append((label, p))
    return dedup


def _within(child: str, root: str) -> bool:
    try:
        return os.path.normcase(os.path.commonpath([child, root])) == os.path.normcase(root)
    except ValueError:
        return False


def _resolve(handler, raw: str) -> str:
    raw = (raw or "").strip().strip('"')
    if not raw:
        raise ValueError("缺少 path 参数")
    if "\x00" in raw:
        raise ValueError("路径包含非法字符")
    p = os.path.expanduser(raw)
    if not re.match(r"^[a-zA-Z]:[\\/]", p):
        ws = _workspace(handler)
        if not ws:
            raise ValueError("相对路径需要先在 config.json 配置 workspace")
        p = os.path.join(ws, p)
    p = os.path.abspath(p)
    if p.startswith("\\\\") or p.startswith("//"):
        raise ValueError("不支持网络共享路径")
    real = os.path.realpath(p)
    if not os.path.exists(real):
        raise FileNotFoundError("路径不存在：%s" % raw)
    roots = [r for _label, r in _roots(handler)]
    if roots and not any(_within(real, r) for r in roots):
        raise PermissionError("路径不在允许范围内（看 config.json 的 file_roots）")
    return real


def _parent(handler, target: str) -> str:
    norm = os.path.normpath(target)
    parent = os.path.dirname(norm.rstrip("\\"))
    if not parent or parent == norm:
        return ""
    roots = [r for _label, r in _roots(handler)]
    if any(_within(os.path.realpath(parent), r) for r in roots):
        return parent
    return ""


# ---------------------------------------------------------------- 展示工具

def _esc(value) -> str:
    return _html.escape(str(value), quote=True)


def _fmt_size(n: int) -> str:
    try:
        n = int(n)
    except (TypeError, ValueError):
        return "?"
    value = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return ("%d B" % value) if unit == "B" else ("%.1f %s" % (value, unit))
        value /= 1024.0
    return "%d B" % n


def _fmt_time(ts: float) -> str:
    try:
        return time.strftime("%Y-%m-%d %H:%M", time.localtime(ts))
    except Exception:  # noqa: BLE001
        return ""


def _fmt_ago(ts: float) -> str:
    try:
        delta = max(0, int(time.time() - ts))
    except Exception:  # noqa: BLE001
        return ""
    if delta < 60:
        return "刚刚"
    if delta < 3600:
        return "%d 分钟前" % (delta // 60)
    if delta < 86400:
        return "%d 小时前" % (delta // 3600)
    return "%d 天前" % (delta // 86400)


_PAGE_HEAD = """<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0b0f14">
<title>__TITLE__</title>
<style>
  :root { color-scheme: dark; --bg:#0b0f14; --panel:#111823; --edge:#1d2a3d; --accent:#2f6fed; --muted:#7d8ca0; --text:#e6edf6; }
  * { -webkit-tap-highlight-color: transparent; box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;
         padding-bottom: calc(16px + env(safe-area-inset-bottom)); }
  header { position:sticky; top:0; z-index:6; display:flex; align-items:center; gap:8px;
           padding: calc(10px + env(safe-area-inset-top)) 12px 10px; background:#0b0f14f2;
           backdrop-filter: blur(10px); border-bottom:1px solid var(--edge); }
  header .title { flex:1; min-width:0; font-weight:600; font-size:16px; overflow:hidden;
                  text-overflow:ellipsis; white-space:nowrap; }
  header a.btn { flex:0 0 auto; color:#79d0ff; text-decoration:none; font-size:13px;
                 padding:6px 10px; border:1px solid var(--edge); border-radius:10px; background:var(--panel); }
  .cwrap { padding:10px 12px 4px; }
  .crumbs { display:flex; flex-wrap:wrap; gap:4px; font-size:12px; color:var(--muted); word-break:break-all; }
  .crumbs a { color:#cfe3ff; text-decoration:none; padding:3px 7px; border-radius:8px; background:var(--panel); }
  .chips { display:flex; gap:8px; overflow-x:auto; padding:8px 12px 10px; scrollbar-width:none; }
  .chips::-webkit-scrollbar { display:none; }
  .chips a { flex:0 0 auto; color:#cfe3ff; text-decoration:none; font-size:12px; padding:6px 11px;
             border:1px solid var(--edge); border-radius:999px; background:var(--panel); }
  .list { display:flex; flex-direction:column; }
  .row { display:flex; align-items:stretch; border-bottom:1px solid #16202e; background:var(--bg); }
  .row:active { background:#101924; }
  .row .main { flex:1; min-width:0; display:flex; align-items:center; gap:10px; padding:12px 8px 12px 14px;
               color:var(--text); text-decoration:none; }
  .row .ico { flex:0 0 auto; font-size:16px; width:20px; text-align:center; }
  .row .nm { flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .row .nm .sub { display:block; color:var(--muted); font-size:11px; margin-top:2px;
                  overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .row .act { flex:0 0 auto; display:flex; align-items:center; padding:0 14px; color:#79d0ff;
              font-size:12px; text-decoration:none; border-left:1px solid #16202e; }
  .hint, .empty { padding:18px 14px; color:var(--muted); font-size:13px; }
  .empty { text-align:center; padding:48px 16px; }
  .err { margin:16px 12px; padding:12px 14px; border:1px solid #7f1d1d; border-radius:12px;
         color:#ffb4b4; background:#1a0f12; font-size:13px; word-break:break-all; }
  .err b { display:block; margin-bottom:4px; color:#ffd7d7; }
  .note { padding:16px 14px 22px; color:#5d6f85; font-size:11px; line-height:1.7; }
</style>
</head><body>"""


def _shell(title: str, body: str) -> str:
    return (_PAGE_HEAD.replace("__TITLE__", _esc(title)) + body +
            '<div class="note">DSH 遥控桥接器 __VERSION__ · 文件只在本机内网 / 已配对设备可见'
            ' · 原文件请勿在手机上乱改</div></body></html>')


def _header(title: str, back_href: str = "/", refresh_href: str | None = None) -> str:
    html = ['<header>', '<a class="btn" href="%s">← 对话</a>' % _esc(back_href),
            '<div class="title">%s</div>' % _esc(title)]
    if refresh_href:
        html.append('<a class="btn" href="%s">↻</a>' % _esc(refresh_href))
    html.append('</header>')
    return "".join(html)


def _chips(handler, target: str, parent: str) -> str:
    parts = ['<div class="chips">']
    if parent:
        parts.append('<a href="/files?path=%s">⬆ 上级</a>'
                     % _esc(urllib.parse.quote(parent, safe="")))
    for label, root in _roots(handler):
        parts.append('<a href="/files?path=%s">%s</a>'
                     % (_esc(urllib.parse.quote(root, safe="")), _esc(label)))
    parts.append('</div>')
    return "".join(parts)


def _crumbs(handler, target: str) -> str:
    drive, rest = os.path.splitdrive(os.path.normpath(target))
    parts = [p for p in rest.replace("/", "\\").split("\\") if p]
    html = ['<div class="cwrap"><div class="crumbs">']
    cur = drive + "\\" if drive else "\\"
    html.append('<a href="/files?path=%s">%s</a>' % (_esc(urllib.parse.quote(cur, safe="")),
                                                     _esc(drive or "根")))
    for part in parts:
        cur = os.path.join(cur, part)
        html.append("› <a href=\"/files?path=%s\">%s</a>"
                    % (_esc(urllib.parse.quote(cur, safe="")), _esc(part)))
    html.append('</div>')
    return "".join(html)


def _row_dir(handler, target: str, name: str) -> str:
    full = os.path.join(target, name)
    page = "/files?path=" + urllib.parse.quote(full, safe="")
    zipped = ("/files/zip?path=" + urllib.parse.quote(full, safe="") +
              "&t=" + _sign(handler, full))
    return ('<div class="row"><a class="main" href="%s"><span class="ico">📁</span>'
            '<span class="nm">%s</span></a><a class="act" href="%s">打包</a></div>'
            % (_esc(page), _esc(name), _esc(zipped)))


def _row_file(handler, target: str, name: str, size: int, mtime: float) -> str:
    full = os.path.join(target, name)
    link = ("/files/download?path=" + urllib.parse.quote(full, safe="") +
            "&t=" + _sign(handler, full))
    sub = "%s · %s" % (_fmt_size(size), _fmt_ago(mtime))
    return ('<div class="row"><a class="main" href="%s" download><span class="ico">📄</span>'
            '<span class="nm">%s<span class="sub">%s</span></span></a>'
            '<a class="act" href="%s" download>⬇</a></div>'
            % (_esc(link), _esc(name), _esc(sub), _esc(link)))


def _fail(handler, method: str, code: int, title: str, detail: str) -> None:
    if method == "GET":
        body = (_header("出错了") +
                '<div class="err"><b>%s</b>%s</div>'
                '<div class="list"><div class="row"><a class="main" href="/files">'
                '<span class="ico">↩︎</span><span class="nm">返回文件首页</span></a></div></div>'
                % (_esc(title), _esc(detail)))
        handler._send_html(code, _shell(title, body))
    else:
        handler._send_json(code, {"ok": False, "error": title, "detail": detail})


# ---------------------------------------------------------------- 页面

def _handle_page(handler, method: str, raw_target: str) -> None:
    if not raw_target.strip():
        _roots_page(handler, method)
        return
    target = _resolve(handler, raw_target)
    if not os.path.isdir(target):
        _serve_file(handler, method, target, os.path.basename(target))
        return
    _listing_page(handler, method, target)


def _roots_page(handler, method: str) -> None:
    roots = _roots(handler)
    rows = []
    for label, path in roots:
        icon = "🏠" if label == "用户目录" else ("📂" if label == "工作区" else "💻")
        href = "/files?path=" + urllib.parse.quote(path, safe="")
        rows.append('<div class="row"><a class="main" href="%s"><span class="ico">%s</span>'
                    '<span class="nm">%s<span class="sub">%s</span></span></a></div>'
                    % (_esc(href), icon, _esc(label), _esc(os.path.normpath(path))))
    body = (_header("电脑文件", refresh_href="/files") +
            '<div class="hint">选择电脑上的一个位置开始浏览，点文件即可下载到手机：</div>'
            '<div class="list">%s</div>' % "".join(rows))
    handler._send_html(200, _shell("电脑文件", body))


def _listing_page(handler, method: str, target: str) -> None:
    cfg = _cfg(handler)
    show_hidden = bool(cfg.get("show_hidden", False))
    entries = []
    try:
        with os.scandir(target) as it:
            for entry in it:
                name = entry.name
                if name in _SKIP_NAMES:
                    continue
                if name.startswith(".") and not show_hidden:
                    continue
                try:
                    st = entry.stat(follow_symlinks=False)
                except OSError:
                    continue
                is_dir = entry.is_dir(follow_symlinks=False)
                entries.append((is_dir, name, 0 if is_dir else st.st_size, st.st_mtime))
    except PermissionError:
        raise PermissionError("没有权限读取目录：%s" % target)
    entries.sort(key=lambda item: (not item[0], item[1].lower()))

    rows = []
    for is_dir, name, size, mtime in entries:
        rows.append(_row_dir(handler, target, name) if is_dir
                    else _row_file(handler, target, name, size, mtime))
    if not rows:
        rows.append('<div class="empty">这里什么都没有</div>')

    parent = _parent(handler, target)
    title = os.path.basename(target.rstrip("\\/")) or target
    current = "/files?path=" + urllib.parse.quote(target, safe="")
    body = (_header(title, refresh_href=current) + _crumbs(handler, target) +
            _chips(handler, target, parent) + '<div class="list">%s</div>' % "".join(rows))
    handler._send_html(200, _shell(title, body))


# ---------------------------------------------------------------- JSON

def _handle_api_list(handler, method: str, raw_target: str) -> None:
    target = _resolve(handler, raw_target)
    parent = _parent(handler, target)
    payload: dict = {"ok": True, "path": target, "parent": parent or None, "entries": []}
    if os.path.isdir(target):
        with os.scandir(target) as it:
            items = list(it)
        for entry in items:
            try:
                st = entry.stat(follow_symlinks=False)
            except OSError:
                continue
            is_dir = entry.is_dir(follow_symlinks=False)
            payload["entries"].append({
                "name": entry.name,
                "dir": is_dir,
                "size": 0 if is_dir else st.st_size,
                "mtime": int(st.st_mtime),
            })
        payload["entries"].sort(key=lambda x: (not x["dir"], x["name"].lower()))
    handler._send_json(200, payload)


def _handle_api_roots(handler, method: str, raw_target: str) -> None:
    handler._send_json(200, {
        "ok": True,
        "roots": [{"label": label, "path": path} for label, path in _roots(handler)],
    })


_RECENT_SKIP_DIRS = {"node_modules", ".git", ".gradle", "__pycache__", ".venv", "venv",
                     ".idea", ".vscode", ".next", ".cache", "dist", "target", "build"}
_RECENT_CACHE: dict = {}
_RECENT_TTL = 20


def _query_int(handler, name: str, default: int, lo: int, hi: int) -> int:
    try:
        raw = urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query).get(name)
        value = int(raw[0]) if raw else default
    except (TypeError, ValueError, IndexError):
        value = default
    return max(lo, min(hi, value))


def _handle_api_recent(handler, method: str, raw_target: str) -> None:
    """返回某个根目录下最近修改的文件（给独立 App 的「最近产出」用）。

    ?path=<root> 可省略（默认 workspace）；?limit=N 默认 30，最大 100。
    30 秒内同一请求走内存缓存，避免反复扫描大目录。
    """
    if raw_target.strip():
        root = _resolve(handler, raw_target)
    else:
        root = _workspace(handler)
        if not root:
            roots = _roots(handler)
            if not roots:
                raise FileNotFoundError("没有可浏览的目录")
            root = roots[0][1]
    root = os.path.realpath(root)
    if not os.path.isdir(root):
        raise ValueError("不是目录：%s" % root)

    limit = _query_int(handler, "limit", 30, 1, 100)
    cache_key = (os.path.normcase(root), limit)
    cached = _RECENT_CACHE.get(cache_key)
    if cached and time.time() - cached[0] < _RECENT_TTL:
        handler._send_json(200, cached[1])
        return

    root_len = len(root.rstrip("\\/"))
    items: list[dict] = []
    scanned = 0
    max_scan = 60000
    max_depth = 8
    for current, dirs, names in os.walk(root):
        depth = current[root_len:].count(os.sep) if len(current) > root_len else 0
        if depth >= max_depth:
            dirs[:] = []
        else:
            dirs[:] = [d for d in dirs
                       if not d.startswith(".") and d not in _RECENT_SKIP_DIRS
                       and d not in _SKIP_NAMES]
        for name in names:
            if name.startswith("."):
                continue
            full = os.path.join(current, name)
            try:
                st = os.stat(full)
            except OSError:
                continue
            scanned += 1
            items.append({
                "name": name,
                "path": full,
                "size": st.st_size,
                "mtime": int(st.st_mtime),
                "rel": os.path.relpath(full, root),
            })
            if scanned >= max_scan:
                break
        if scanned >= max_scan:
            break

    items.sort(key=lambda item: item["mtime"], reverse=True)
    payload = {"ok": True, "root": root, "items": items[:limit]}
    _RECENT_CACHE[cache_key] = (time.time(), payload)
    handler._send_json(200, payload)


# ---------------------------------------------------------------- 下载

def _handle_download(handler, method: str, raw_target: str) -> None:
    target = _resolve(handler, raw_target)
    if os.path.isdir(target):
        raise ValueError("这是目录，请用 /files/zip 打包下载")
    _serve_file(handler, method, target, os.path.basename(target))


def _serve_file(handler, method: str, path: str, name: str) -> None:
    stat = os.stat(path)
    size = stat.st_size
    ctype = mimetypes.guess_type(name)[0] or "application/octet-stream"
    ascii_name = re.sub(r"[^A-Za-z0-9._-]", "_", name) or "file"
    disposition = ('attachment; filename="%s"; filename*=UTF-8\'\'%s'
                   % (ascii_name, urllib.parse.quote(name, safe="")))

    start, end, status = 0, size - 1, 200
    range_header = (handler.headers.get("Range") or "").strip()
    if range_header:
        match = re.match(r"bytes=(\d*)-(\d*)$", range_header)
        if match:
            left, right = match.group(1), match.group(2)
            if left == "" and right != "":
                start = max(0, size - int(right))
                end = size - 1
            elif left != "":
                start = int(left)
                end = min(int(right), size - 1) if right else size - 1
            if start > end or start >= size:
                handler.send_response(416)
                handler.send_header("Content-Range", "bytes */%d" % size)
                handler.send_header("Content-Length", "0")
                handler.end_headers()
                return
            status = 206

    length = max(0, end - start + 1)
    handler.send_response(status)
    handler.send_header("Content-Type", ctype)
    handler.send_header("Accept-Ranges", "bytes")
    handler.send_header("Content-Length", str(length))
    handler.send_header("Content-Disposition", disposition)
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("X-Content-Type-Options", "nosniff")
    if status == 206:
        handler.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
    handler.end_headers()
    if method == "HEAD":
        return

    try:
        with open(path, "rb") as fh:
            fh.seek(start)
            remaining = length
            while remaining > 0:
                chunk = fh.read(min(_CHUNK, remaining))
                if not chunk:
                    break
                handler.wfile.write(chunk)
                remaining -= len(chunk)
            handler.wfile.flush()
    except (BrokenPipeError, ConnectionResetError):
        pass


# ---------------------------------------------------------------- 打包下载

def _handle_zip(handler, method: str, raw_target: str) -> None:
    target = _resolve(handler, raw_target)
    if not os.path.isdir(target):
        _serve_file(handler, method, target, os.path.basename(target))
        return

    cfg = _cfg(handler)
    limit = int(cfg.get("file_zip_limit_mb", 2048)) * 1024 * 1024
    max_files = int(cfg.get("file_zip_max_files", 20000))
    base_parent = os.path.dirname(target.rstrip("\\/")) or target.rstrip("\\/")

    files: list[str] = []
    total = 0
    for root, dirs, names in os.walk(target):
        dirs[:] = sorted(d for d in dirs
                         if d not in _SKIP_NAMES and not d.startswith("."))
        for name in names:
            if name.startswith("."):
                continue
            full = os.path.join(root, name)
            try:
                total += os.stat(full).st_size
            except OSError:
                continue
            if total > limit:
                raise ValueError("目录超过打包上限 %d MB，请进入子目录再打包"
                                 % (limit // (1024 * 1024)))
            files.append(full)
            if len(files) > max_files:
                raise ValueError("文件数量超过上限 %d" % max_files)

    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
    tmp_path = tmp.name
    tmp.close()
    try:
        with zipfile.ZipFile(tmp_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            for full in files:
                try:
                    zf.write(full, os.path.relpath(full, base_parent))
                except (OSError, ValueError):
                    continue
        name = os.path.basename(target.rstrip("\\/")) or "root"
        _serve_file(handler, method, tmp_path, name + ".zip")
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
