#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DSH Phone Bridge —— 把电脑上的 DeepSeek Harness Web GUI 安全地借给手机用。

它做四件事：
  1. 上游通道：自己拉起一个独立的 `dsh web`（默认 profile=phone，端口 15099），
     从 stdout 抓到带 token 的启动 URL，立刻用它换出进程级 cookie，之后一直替
     手机保管。也可以改成 attach 到你已有的实例（config.upstream）。
  2. 设备配对：手机首次访问要输入 6 位 PIN，通过后发一个长期设备 cookie。
     从公网隧道或局域网进来的请求，没有设备 cookie 一律不给过。
  3. 反向代理：HTTP + WebSocket 全量转发，并把 Host / Origin / Referer 改写成
     上游的 loopback authority —— DSH 的信任栅栏只认 loopback，这一步必须做。
     上游的 Set-Cookie 一律吃掉，手机只看到桥接器自己的设备 cookie。
  4. 移动端注入：给页面塞 viewport、安全区、点击高亮等少量 CSS/JS，不改变
     DSH 自身行为。

只依赖 Python 3 标准库。用法：

    python dsh_phone_bridge.py                    # 默认 0.0.0.0:8799
    python dsh_phone_bridge.py --port 8799 --upstream http://127.0.0.1:15099/?token=xxx
    python dsh_phone_bridge.py --no-spawn         # 不自己拉 dsh，只 attach
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import http.client
import http.server
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

import dsh_files  # 电脑文件浏览/下载（/files），见 dsh_files.py
import dsh_inbox  # 手机收件箱：agent 推送文件 → 手机通知（/_bridge/push、/files/api/inbox）
import dsh_upload  # 手机 → 电脑 文件上传（/files/upload）

BRIDGE_VERSION = "1.0.0"
STATE_FILE = "bridge-state.json"
CONFIG_FILE = "config.json"

COOKIE_NAME = "dsh_phone"
HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length",
}

PAIR_PAGE = """<!doctype html><html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>DSH 遥控 · 待配对</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; min-height:100vh; display:grid; place-items:center;
         background:radial-gradient(120% 80% at 50% 0%,#132033 0%,#0b0f14 60%);
         color:#e6edf6; font:16px/1.6 system-ui,-apple-system,"Segoe UI",sans-serif;
         padding:calc(24px + env(safe-area-inset-top)) 24px calc(24px + env(safe-area-inset-bottom)); }
  .card { width:100%; max-width:420px; background:#111823cc; border:1px solid #243247;
          border-radius:20px; padding:22px; box-shadow:0 20px 60px #0008; }
  h1 { font-size:19px; margin:0 0 6px; }
  p  { margin:0 0 14px; color:#9fb0c6; font-size:14px; }
  code { background:#0b1220; border:1px solid #22314a; border-radius:8px; padding:2px 6px; color:#79d0ff; }
  .row { display:flex; gap:10px; margin-top:14px; }
  input { flex:1; min-width:0; background:#0b1220; border:1px solid #22314a; color:#e6edf6;
          border-radius:12px; padding:14px; font-size:18px; letter-spacing:6px; text-align:center; }
  button { border:0; border-radius:12px; padding:14px 18px; font-size:15px; font-weight:600;
           background:#2f6fed; color:#fff; }
  #msg { margin-top:12px; font-size:14px; min-height:20px; }
  .ok { color:#6ee7a8 } .bad { color:#ff8a8a }
  .meta { margin-top:18px; padding-top:14px; border-top:1px solid #1d2a3d; font-size:12px; color:#6f8299; }
</style></head><body>
<div class="card">
  <h1>DSH 遥控 · 待配对</h1>
  <p>这台电脑上的 DeepSeek Harness 已通过桥接器对外开放。第一次连接请输入电脑端显示的 6 位 PIN，成功后本机长期免输。</p>
  <div class="row"><input id="pin" inputmode="numeric" maxlength="6" placeholder="● ● ● ● ● ●" autocomplete="one-time-code"><button id="go">连接</button></div>
  <div id="msg"></div>
  <div class="meta">桥接器 __VERSION__ · 配对成功后请把本页加入主屏幕</div>
</div>
<script>
(function(){
  var pin = document.getElementById('pin'), msg = document.getElementById('msg');
  function set(t, cls){ msg.textContent = t; msg.className = cls || ''; }
  function go(){
    var v = (pin.value || '').trim();
    if (!/^\\d{4,8}$/.test(v)) { set('请输入 6 位数字 PIN', 'bad'); return; }
    set('配对中…');
    fetch('/_bridge/pair', { method:'POST', headers:{'content-type':'application/json'},
      credentials:'same-origin', body: JSON.stringify({ pin: v }) })
      .then(function(r){ return r.json().then(function(j){ return { ok:r.ok, j:j }; }); })
      .then(function(x){
        if (x.ok && x.j.ok) { set('配对成功，正在进入…', 'ok'); location.replace(window.location.pathname + window.location.search); }
        else { set(x.j.error || 'PIN 不正确', 'bad'); }
      })
      .catch(function(){ set('网络错误，检查电脑端桥接器是否在运行', 'bad'); });
  }
  document.getElementById('go').addEventListener('click', go);
  pin.addEventListener('keydown', function(e){ if (e.key === 'Enter') go(); });
  pin.focus();
})();
</script></body></html>"""

MOBILE_HEAD = """<meta name="theme-color" content="#0b0f14">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<style id="dsh-phone-css">
  :root { --dsh-safe-bottom: env(safe-area-inset-bottom, 0px); }
  html, body { overscroll-behavior-y: contain; -webkit-text-size-adjust: 100%; overflow-x: hidden; }
  body { padding-bottom: calc(var(--dsh-safe-bottom) + 4px) !important; }
  * { -webkit-tap-highlight-color: transparent; }
  ::-webkit-scrollbar { width: 0 !important; height: 0 !important; }
  /* 说明：不要给 [role=button]/button 统一放大命中区 —— 会把会话行里的
     归档/删除这类小图标撑大，反而更容易误点。 */
  @media (max-width: 860px) {
    body { font-size: 15px; }
    textarea, input { font-size: max(16px, 1em) !important; }
  }
</style>
<script id="dsh-phone-js">
(function () {
  if (window.__dshPhoneBridge) return;
  window.__dshPhoneBridge = { version: __VERSION_JSON__, pairedAt: Date.now() };
  if (window.DshPhone && window.DshPhone.toast) { /* host shell present */ }
})();
</script>
"""

MOBILE_TAIL = """<script id="dsh-phone-tail">
(function () {
  if (window.__dshPhoneTail) return; window.__dshPhoneTail = true;

  // 页面被系统回收/切回时给宿主一个信号，宿主可据此刷新。
  document.addEventListener('visibilitychange', function () {
    try { window.DshPhone && window.DshPhone.onVisibility && window.DshPhone.onVisibility(!document.hidden); } catch (e) {}
  });
  window.addEventListener('online',  function(){ try{ window.DshPhone && window.DshPhone.onNetwork && window.DshPhone.onNetwork(true); }catch(e){} });
  window.addEventListener('offline', function(){ try{ window.DshPhone && window.DshPhone.onNetwork && window.DshPhone.onNetwork(false); }catch(e){} });

  // ---- 抽屉/浮层的“左滑关闭” ----
  // 找覆盖全屏的半透明遮罩（抽屉打开时它一定在最上层）
  function scrim() {
    var all = document.querySelectorAll('div');
    for (var i = all.length - 1; i >= 0; i--) {
      var el = all[i], s = getComputedStyle(el), r = el.getBoundingClientRect();
      if (s.position !== 'fixed') continue;
      if (r.width < innerWidth * 0.9 || r.height < innerHeight * 0.9) continue;
      var m = (s.backgroundColor || '').match(/rgba?\\(([^)]+)\\)/);
      if (!m) continue;
      var parts = m[1].split(',');
      if (parts.length === 4 && parseFloat(parts[3]) > 0.05) return el;
    }
    return null;
  }

  // ---- 侧栏开合（纯几何判断，不依赖任何哈希 class） ----
  // 唯一锚点：class 后缀含 toggle 的那个按钮。
  // 收起时它在左上角（x≈10），展开后跑到 280 宽侧栏的右侧（x≈240）。
  function toggleBtn() {
    return document.querySelector('button[class*="toggle"]');
  }
  function railExpanded() {
    var b = toggleBtn();
    if (!b) return false;
    return b.getBoundingClientRect().left > 100;
  }
  function railRight() {
    var b = toggleBtn();
    if (!b) return 0;
    return b.getBoundingClientRect().right + 24;
  }
  function toggleRail() {
    var b = toggleBtn();
    if (!b) return false;
    try { b.click(); return true; } catch (e) { return false; }
  }

  // 返回 true 表示“确实关掉了侧栏/浮层”
  window.__dshClose = function () {
    if (railExpanded() && toggleRail()) return true;
    var s = scrim();
    if (s) { try { s.click(); } catch (e) {} return true; }
    var ev = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true };
    try { document.dispatchEvent(new KeyboardEvent('keydown', ev)); } catch (e) {}
    try { window.dispatchEvent(new KeyboardEvent('keydown', ev)); } catch (e) {}
    return false;
  };

  var sx = 0, sy = 0, st = 0;
  document.addEventListener('touchstart', function (e) {
    if (e.touches.length !== 1) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now();
  }, { passive: true });

  document.addEventListener('touchend', function (e) {
    if (!st) return;
    var t = e.changedTouches[0];
    var dx = t.clientX - sx, dy = t.clientY - sy, dt = Date.now() - st;
    st = 0;
    // 快速左滑（>70px 且基本水平）→ 关掉抽屉/浮层，不再落到下层列表上
    if (dt < 500 && dx < -70 && Math.abs(dy) < 50) {
      window.__dshClose();
    }
  }, { passive: true });

  // 在侧栏里点选一项后自动收起
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t || !t.closest) return;
    if (!railExpanded()) return;            // 侧栏没展开就什么都不做
    if (e.clientX > railRight()) return;    // 点在主内容区，不是侧栏
    var item = t.closest('[role="button"],[role="listitem"],a,button,li,div');
    if (!item) return;
    var lbl = (item.getAttribute('aria-label') || item.getAttribute('title') || '').toLowerCase();
    if (lbl.indexOf('归档') >= 0 || lbl.indexOf('archive') >= 0 ||
        lbl.indexOf('删除') >= 0 || lbl.indexOf('delete') >= 0) return;
    setTimeout(function () { window.__dshClose(); }, 260);
  }, true);
})();
</script>
"""


LOG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bridge.log")


def log(msg: str) -> None:
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg)
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except Exception:  # noqa: BLE001
        pass
    try:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()
    except Exception:  # noqa: BLE001
        pass


def _b64e(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _b64d(text: str) -> bytes:
    text = text.replace("-", "+").replace("_", "/")
    return base64.b64decode(text + "=" * (-len(text) % 4))


def _cookie_secrets() -> list[str]:
    """从 ~/.dsh/.credentials.yaml 里捞出所有候选签名密钥（不依赖 PyYAML）。"""
    home = os.environ.get("DSH_HOME") or os.path.join(os.path.expanduser("~"), ".dsh")
    path = os.path.join(home, ".credentials.yaml")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except Exception:  # noqa: BLE001
        return []
    found = re.findall(r"secret:\s*([A-Za-z0-9_\-+/=]{20,})", text)
    seen, out = set(), []
    for item in found:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def mint_browser_cookie(authority: str) -> str | None:
    """给指定 authority 签一张 DSH 认的会话 cookie。

    DSH 的浏览器会话 cookie 是
        name  = "dsh-auth-" + base64url(sha256(authority))
        value = "v1." + base64url(payload) + "." + base64url(HMAC-SHA256(secret, payload))
    签名密钥持久化在 $DSH_HOME/.credentials.yaml，所以同一个 DSH_HOME 下的实例都认。
    """
    name = "dsh-auth-" + _b64e(hashlib.sha256(authority.encode()).digest())
    now = int(time.time() * 1000)
    payload = json.dumps(
        {"version": 1, "authority": authority, "issuedAt": now, "expiresAt": now + 30 * 86400 * 1000},
        separators=(",", ":"),
    )
    body = _b64e(payload.encode())
    for secret in _cookie_secrets():
        try:
            key = _b64d(secret)
        except Exception:  # noqa: BLE001
            continue
        sig = _b64e(hmac.new(key, body.encode(), hashlib.sha256).digest())
        return "%s=v1.%s.%s" % (name, body, sig)
    return None


class Upstream:
    """维护一条可用的 DSH Web 上游：拉起或附着，保管 token 换来的 cookie。"""

    def __init__(self, url: str | None, spawn: bool, profile: str, port: int,
                 workspace: str, attach: str | None = None, cookie: str | None = None) -> None:
        self.configured_url = url
        self.spawn_enabled = spawn
        self.profile = profile
        self.port = port
        self.workspace = workspace
        self.attach = attach
        self.configured_cookie = cookie
        self.proc: subprocess.Popen | None = None
        self.cookie: str | None = None
        self.authority = "127.0.0.1:%d" % port
        self.lock = threading.Lock()
        self.last_error: str | None = None
        self.ok = False
        self._monitor: threading.Thread | None = None

    # ---------- public ----------

    def status(self) -> bool:
        """最近一次探测的结果；不会阻塞请求线程。"""
        self.ensure_async()
        return self.ok

    def ensure_async(self) -> None:
        """后台跑一次 ensure()，同一时刻只跑一个。"""
        if self.ok:
            return
        if self._monitor is not None and self._monitor.is_alive():
            return
        self._monitor = threading.Thread(target=self.ensure, daemon=True)
        self._monitor.start()

    def ensure(self) -> bool:
        """确保上游可用；返回是否就绪。"""
        with self.lock:
            if self.cookie and self._probe():
                self.ok = True
                return True
            self.ok = False
            if self.attach and self._attach():
                self.ok = True
                return True
            # 桌面端每次启动端口都会变，attach 指空时先自动找本机实例
            if self._discover():
                self.ok = True
                return True
            if self.configured_url:
                if self._adopt(self.configured_url):
                    self.ok = True
                    return True
            if self.spawn_enabled and self._spawn():
                self.ok = self._probe()
                return self.ok
            return False

    def headers_for(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        h = {"Host": self.authority,
             "Origin": "http://" + self.authority,
             "Referer": "http://" + self.authority + "/"}
        if self.cookie:
            h["Cookie"] = self.cookie
        if extra:
            h.update(extra)
        return h

    # ---------- internals ----------

    def _attach(self) -> bool:
        """附着到已经在跑的 DSH 实例：自己签一张 cookie。

        attach 可以写成具体地址（127.0.0.1:4450），也可以写 auto —— 自动从
        DeepSeek Harness 进程的监听端口里找一个能认证通过的。
        """
        if not self.attach:
            return False

        raw = self.attach.strip()
        if raw.lower() == "auto":
            candidates = _candidate_authorities()
            if not candidates:
                self.last_error = "没找到正在运行的 DSH 实例（auto）"
                log(self.last_error)
                return False
            log("auto 探测到候选端口：%s" % ", ".join(candidates))
        else:
            a = raw.split("://", 1)[1] if "://" in raw else raw
            a = a.rstrip("/")
            if ":" not in a:
                a += ":3080"
            candidates = [a]

        for authority in candidates:
            if self._try_authority(authority):
                return True
        self.last_error = self.last_error or "attach 失败"
        return False

    def _try_authority(self, authority: str) -> bool:
        self.authority = authority
        host, _, port = authority.rpartition(":")
        try:
            self.port = int(port)
        except ValueError:
            return False

        self.cookie = (self.configured_cookie.strip() if self.configured_cookie
                       else mint_browser_cookie(authority))
        if not self.cookie:
            self.last_error = "拿不到会话签名密钥（~/.dsh/.credentials.yaml）"
            log(self.last_error)
            return False

        if self._probe():
            log("已附着现有实例 %s" % authority)
            return True

        self.cookie = None
        self.last_error = "attach %s 探测失败" % authority
        return False

    def _discover(self) -> bool:
        """自动在本机已运行的 DSH 实例里找一个能附着的（优先桌面端主进程）。"""
        desktop = _desktop_authorities()
        candidates = desktop + [a for a in _candidate_authorities() if a not in desktop]
        if not candidates:
            return False
        log("发现本机 DSH 候选端口：%s" % ", ".join(candidates))
        for authority in candidates:
            if self._try_authority(authority):
                log("自动附着上游 %s" % authority)
                return True
        return False

    def _probe(self) -> bool:
        try:
            conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=6)
            conn.request("GET", "/", headers=self.headers_for())
            resp = conn.getresponse()
            status = resp.status
            body = resp.read() if status != 304 else b""
            conn.close()
            if status not in (200, 304):
                return False
            # dsh-pocket 手机访问代理（默认 3081）在未认证时也回 200 —— 但回的是
            # 「DSH Pocket 访问验证」登录页。若把它当成上游附着，手机在桥接器配对
            # 之后又会撞上 pocket 的 8 位密码页（2026-11-14 实际踩坑）。
            if b"pocket-login" in body or b"DSH Pocket" in body:
                self.last_error = "上游是 dsh-pocket 登录页（非 DSH 实例），跳过"
                return False
            return True
        except Exception as exc:  # noqa: BLE001
            self.last_error = str(exc)
            return False

    def _exchange_token(self, url: str) -> bool:
        """用启动 URL 换 cookie（上游回 303 + Set-Cookie）。"""
        try:
            path = url.split("://", 1)[1]
            path = "/" + path.split("/", 1)[1] if "/" in path else "/"
        except Exception:
            path = "/"
        try:
            conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
            conn.request("GET", path, headers={"Host": self.authority})
            resp = conn.getresponse()
            cookies = resp.headers.get_all("Set-Cookie") or []
            resp.read()
            conn.close()
            for raw in cookies:
                name_value = raw.split(";", 1)[0].strip()
                if name_value.startswith("dsh-auth"):
                    self.cookie = name_value
                    return True
            self.last_error = "token exchange returned no dsh-auth cookie"
            return False
        except Exception as exc:  # noqa: BLE001
            self.last_error = str(exc)
            return False

    def _adopt(self, url: str) -> bool:
        m = re.search(r"://([^/]+)", url)
        if m:
            self.authority = m.group(1)
        if not self._exchange_token(url):
            log("attach 失败：%s" % self.last_error)
            return False
        if self._probe():
            log("已附着上游 %s" % url.split("?")[0])
            return True
        return False

    def _spawn(self) -> bool:
        exe = os.environ.get("DSH_EXE") or _which("dsh")
        if not exe:
            self.last_error = "找不到 dsh 可执行文件（设置 DSH_EXE 或把 dsh 加进 PATH）"
            log(self.last_error)
            return False
        if self.profile:
            # `dsh web` 是 `--profile web` 的别名，两者不能同时给；
            # 指定 profile 时直接 `dsh --profile <名字>` 再跟 web 自己的 flag。
            args = [exe, "--profile", self.profile, "--no-open", "--port", str(self.port)]
        else:
            args = [exe, "web", "--no-open", "--port", str(self.port)]
        log("启动上游：%s" % " ".join(args))
        try:
            env = dict(os.environ)
            if not env.get("DEEPSEEK_API_KEY"):
                key_file = os.path.join(os.path.expanduser("~"), ".dsh", ".local", "deepseek.key")
                try:
                    with open(key_file, "r", encoding="utf-8") as fh:
                        key = fh.read().strip()
                    if key:
                        env["DEEPSEEK_API_KEY"] = key
                except Exception:  # noqa: BLE001
                    pass
            self.proc = subprocess.Popen(
                args, cwd=self.workspace or None,
                env=env,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace",
                shell=True, creationflags=0x08000000,  # CREATE_NO_WINDOW
            )
        except Exception as exc:  # noqa: BLE001
            self.last_error = str(exc)
            log("启动失败：%s" % exc)
            return False

        deadline = time.time() + 120
        while time.time() < deadline:
            line = self.proc.stdout.readline() if self.proc.stdout else ""
            if not line:
                if self.proc.poll() is not None:
                    self.last_error = "dsh 进程提前退出，退出码 %s" % self.proc.returncode
                    log(self.last_error)
                    return False
                continue
            line = line.strip()
            if line:
                log("upstream> " + line[:220])
            m = re.search(r"(http://127\.0\.0\.1:%d/\?token=[A-Za-z0-9_\-]+)" % self.port, line)
            if m:
                if self._exchange_token(m.group(1)):
                    threading.Thread(target=self._drain, daemon=True).start()
                    log("上游就绪，cookie 已接管")
                    return True
        self.last_error = "等待上游 token 超时"
        log(self.last_error)
        return False

    def _drain(self) -> None:
        """继续消费上游 stdout，避免管道写满；错误行写进日志便于排障。"""
        if not self.proc or not self.proc.stdout:
            return
        for line in self.proc.stdout:
            line = line.strip()
            if not line:
                continue
            self.last_error = line[:300]
            low = line.lower()
            if "error" in low or "fail" in low or "unauthor" in low or "401" in low or "api key" in low:
                log("upstream! " + line[:400])
        log("上游进程退出")


def _which(name: str) -> str | None:
    for ext in ("", ".cmd", ".exe", ".bat"):
        p = os.path.join(os.environ.get("APPDATA", ""), "npm", name + ext)
        if ext and os.path.exists(p):
            return p
    from shutil import which
    return which(name)


class Bridge:
    def __init__(self, cfg: dict) -> None:
        self.cfg = cfg
        self.state_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), STATE_FILE)
        self.state = self._load_state()
        self.upstream = Upstream(
            url=cfg.get("upstream"),
            spawn=bool(cfg.get("spawn", True)),
            profile=cfg.get("dsh_profile", "phone"),
            port=int(cfg.get("upstream_port", 15099)),
            workspace=cfg.get("workspace", ""),
            attach=cfg.get("attach"),
            cookie=cfg.get("upstream_cookie"),
        )
        self.pin = str(self.state.get("pin") or cfg.get("pin") or "").strip() or self._new_pin()
        self.state["pin"] = self.pin

    # ---------- state ----------

    def _load_state(self) -> dict:
        try:
            with open(self.state_path, "r", encoding="utf-8") as fh:
                return json.load(fh)
        except Exception:  # noqa: BLE001
            return {"devices": {}, "secret": secrets.token_hex(32)}

    def _save_state(self) -> None:
        self.state.setdefault("secret", secrets.token_hex(32))
        tmp = self.state_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(self.state, fh, ensure_ascii=False, indent=2)
        os.replace(tmp, self.state_path)

    def _new_pin(self) -> str:
        pin = "%06d" % secrets.randbelow(1000000)
        self.state["pin"] = pin
        self._save_state()
        return pin

    # ---------- device auth ----------

    def issue(self, device_id: str) -> str:
        secret = self.state["secret"].encode()
        sig = hmac.new(secret, device_id.encode(), hashlib.sha256).hexdigest()[:40]
        return "%s.%s" % (device_id, sig)

    def verify(self, cookie: str | None) -> str | None:
        if not cookie or "." not in cookie:
            return None
        device_id, sig = cookie.rsplit(".", 1)
        secret = self.state["secret"].encode()
        want = hmac.new(secret, device_id.encode(), hashlib.sha256).hexdigest()[:40]
        if not hmac.compare_digest(sig, want):
            return None
        device = self.state.get("devices", {}).get(device_id)
        if not device:
            return None
        device["lastSeen"] = int(time.time())
        return device_id

    def add_device(self, name: str) -> tuple[str, str]:
        device_id = secrets.token_hex(8)
        self.state.setdefault("devices", {})[device_id] = {
            "name": name[:40] or "phone",
            "addedAt": int(time.time()),
            "lastSeen": int(time.time()),
        }
        self._save_state()
        return device_id, self.issue(device_id)

    def revoke_all(self) -> int:
        n = len(self.state.get("devices", {}))
        self.state["devices"] = {}
        self._save_state()
        return n


def _dsh_pids() -> set[str]:
    """列出 DeepSeek Harness 进程的 PID（桌面端起的是同名进程）。"""
    try:
        out = subprocess.run(
            ["tasklist", "/fi", "imagename eq DeepSeek Harness.exe", "/fo", "csv", "/nh"],
            capture_output=True, text=True, timeout=8, creationflags=0x08000000,
        ).stdout
    except Exception:  # noqa: BLE001
        return set()
    pids = set()
    for line in out.splitlines():
        parts = [p.strip('"') for p in line.split('","')]
        if len(parts) >= 2 and parts[1].isdigit():
            pids.add(parts[1])
    return pids


def _desktop_authorities() -> list[str]:
    """优先挑出「桌面端 profile」主进程监听的 loopback 端口。"""
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "$pids = (Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*profiles\\desktop*' }).ProcessId; $pids -join ','"],
            capture_output=True, text=True, timeout=25, creationflags=0x08000000).stdout
    except Exception:  # noqa: BLE001
        return []
    pids = {p.strip() for p in out.replace('\r', '').split(',') if p.strip().isdigit()}
    if not pids:
        return []
    try:
        net = subprocess.run(["netstat", "-ano"], capture_output=True, text=True,
                             timeout=10, creationflags=0x08000000).stdout
    except Exception:  # noqa: BLE001
        return []
    found: list[str] = []
    for line in net.splitlines():
        if "LISTENING" not in line:
            continue
        parts = line.split()
        if len(parts) < 5 or parts[-1] not in pids:
            continue
        host, _, port = parts[1].rpartition(":")
        if host in ("127.0.0.1", "[::1]", "0.0.0.0") and port.isdigit():
            auth = "127.0.0.1:" + port
            if auth not in found:
                found.append(auth)
    return found


def _candidate_authorities() -> list[str]:
    """在 DeepSeek Harness 进程的监听端口里找可附着的 loopback 地址。

    桌面端每次重启端口都可能变（14986 → 4450 …），所以不写死。
    """
    pids = _dsh_pids()
    if not pids:
        return []
    try:
        out = subprocess.run(
            ["netstat", "-ano"], capture_output=True, text=True, timeout=10,
            creationflags=0x08000000,
        ).stdout
    except Exception:  # noqa: BLE001
        return []
    found: list[str] = []
    for line in out.splitlines():
        if "LISTENING" not in line:
            continue
        parts = line.split()
        if len(parts) < 5:
            continue
        local, pid = parts[1], parts[-1]
        if pid not in pids:
            continue
        host, _, port = local.rpartition(":")
        if host in ("127.0.0.1", "[::1]", "0.0.0.0") and port.isdigit():
            auth = "127.0.0.1:" + port
            if auth not in found:
                found.append(auth)
    return found


# ---------- ASR 服务（DSH 录音实时识别） ----------
ASR_DIR = os.environ.get("DSH_ASR_DIR", os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "dsh-recorder", "asr"))
ASR_PORT = int(os.environ.get("DSH_ASR_PORT", "8801"))
_asr_proc = None
_asr_lock = threading.Lock()


def _asr_health(timeout: float = 1.0):
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d/asr/health" % ASR_PORT, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception:
        return None


def ensure_asr() -> bool:
    """确保本机 ASR 服务在跑；没跑就用 venv 启动（首次加载模型较慢）。"""
    global _asr_proc
    h = _asr_health(0.6)
    if h and h.get("ok"):
        return True
    with _asr_lock:
        h = _asr_health(0.6)
        if h and h.get("ok"):
            return True
        exe = os.path.join(ASR_DIR, ".venv", "Scripts", "python.exe")
        script = os.path.join(ASR_DIR, "dsh_asr.py")
        if not (os.path.isfile(exe) and os.path.isfile(script)):
            log("ASR 未安装：%s" % ASR_DIR)
            return False
        try:
            logfile = open(os.path.join(ASR_DIR, "asr.log"), "ab")
            _asr_proc = subprocess.Popen(
                [exe, script],
                cwd=ASR_DIR,
                stdout=logfile,
                stderr=subprocess.STDOUT,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            log("ASR 启动中 pid=%s" % _asr_proc.pid)
        except Exception as exc:
            log("ASR 启动失败：%s" % exc)
            return False
        for _ in range(150):
            time.sleep(0.4)
            h = _asr_health(1.0)
            if h and h.get("ok"):
                log("ASR 就绪：%s" % h)
                return True
        log("ASR 启动超时")
        return False


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "dsh-phone-bridge/" + BRIDGE_VERSION
    bridge: Bridge = None  # type: ignore[assignment]

    # ---------- logging ----------

    def log_message(self, fmt: str, *args) -> None:  # noqa: A003
        try:
            log("%s %s" % (self.address_string(), fmt % args))
        except Exception:  # noqa: BLE001
            pass

    # ---------- helpers ----------

    def _is_loopback(self) -> bool:
        try:
            return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")
        except Exception:  # noqa: BLE001
            return False

    def _maybe_auto_pair(self) -> bool:
        """本机浏览器打开 http://127.0.0.1:8899 时免 PIN：自动发一枚 desktop 设备 cookie。

        只对回环地址 + 文档请求生效；局域网/外网仍然必须配对。
        返回 True 表示已经回过响应（302 重定向），调用方直接 return。
        """
        if not self.bridge.cfg.get("allow_local_auto_pair", True):
            return False
        if not self._is_loopback():
            return False
        if self.headers.get("Upgrade"):
            return False
        path = self.path.split("?", 1)[0]
        if path.startswith("/_bridge") or path.startswith("/api") or path.startswith("/app"):
            return False
        if self.bridge.verify(self._cookie(COOKIE_NAME)) is not None:
            return False
        device_id, cookie = self.bridge.add_device("desktop")
        log("本机浏览器自动配对：%s" % device_id)
        self.send_response(302)
        self.send_header("Location", self.path or "/")
        self.send_header("Set-Cookie",
                         "%s=%s; Path=/; HttpOnly; SameSite=Lax" % (COOKIE_NAME, cookie))
        self.send_header("Content-Length", "0")
        self.end_headers()
        return True

    def _cookie(self, name: str) -> str | None:
        raw = self.headers.get("Cookie") or ""
        for part in raw.split(";"):
            k, _, v = part.strip().partition("=")
            if k == name:
                return v
        return None

    def _send_json(self, code: int, payload: dict, extra: dict[str, str] | None = None) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _send_html(self, code: int, html: str) -> None:
        body = html.replace("__VERSION__", BRIDGE_VERSION).encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    # ---------- routing ----------

    def do_GET(self) -> None:  # noqa: N802
        if self._maybe_auto_pair():
            return
        if self._app_route():
            return
        if self._bridge_route("GET"):
            return
        if dsh_upload.handle(self, "GET"):
            return
        if dsh_inbox.handle_files(self, "GET"):
            return
        if dsh_files.handle(self, "GET"):
            return
        if self._asr_route():
            return
        self._proxy("GET")

    def _app_route(self) -> bool:
        """App 自更新。发布结构统一为 bridge/app/<code>/{version.json,latest.apk}：

          /app/version        + /app/latest.apk          → code=remote（DSH 遥控，历史路由）
          /app/files/version  + /app/files/latest.apk    → code=files （DSH 文件）
          /app/<code>/version + /app/<code>/latest.apk   → 以后的新 App 通用

        兼容旧目录：remote 还认 app/ 根下的 version.json / dsh-phone.apk；
        files 还认 files-app/。
        """
        path = self.path.split("?", 1)[0]
        if not path.startswith("/app/"):
            return False
        appdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app")

        if path in ("/app/version", "/app/latest.apk"):
            code = "remote"
            tail = "version" if path == "/app/version" else "latest.apk"
            url_path = "/app/" + tail
        else:
            parts = path[len("/app/"):].split("/")
            if len(parts) != 2 or parts[1] not in ("version", "latest.apk"):
                self._send_json(404, {"ok": False, "error": "not found"})
                return True
            code, tail = parts[0], parts[1]
            if not re.fullmatch(r"[a-z0-9_-]{2,32}", code):
                self._send_json(404, {"ok": False, "error": "not found"})
                return True
            url_path = "/app/%s/%s" % (code, tail)

        candidates = [os.path.join(appdir, code)]
        if code == "remote":
            candidates.append(appdir)
        elif code == "files":
            candidates.append(os.path.join(appdir, "files-app"))

        target = None
        for base in candidates:
            candidate = os.path.join(base, "version.json" if tail == "version" else "latest.apk")
            if os.path.isfile(candidate):
                target = candidate
                break
        if target is None and code == "remote" and tail == "latest.apk":
            legacy_apk = os.path.join(appdir, "dsh-phone.apk")
            if os.path.isfile(legacy_apk):
                target = legacy_apk
        if target is None:
            self._send_json(404, {"ok": False, "error": "还没发布过版本"})
            return True

        if tail == "version":
            with open(target, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            data["ok"] = True
            host = (self.headers.get("Host") or "").strip()
            if host:
                data.setdefault("url", "http://%s%s" % (host, url_path))
            self._send_json(200, data)
            return True

        size = os.path.getsize(target)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition",
                         'attachment; filename="%s"' % os.path.basename(target))
        self.end_headers()
        if self.command != "HEAD":
            try:
                with open(target, "rb") as fh:
                    shutil.copyfileobj(fh, self.wfile, 64 * 1024)
            except Exception as exc:  # noqa: BLE001
                log("APK 下载中断：%s" % exc)
        return True

    def do_POST(self) -> None:  # noqa: N802
        if dsh_upload.handle(self, "POST"):
            return
        if dsh_inbox.handle_push(self):
            return
        if self._bridge_route("POST"):
            return
        if self._asr_route():
            return
        self._proxy("POST")

    def do_HEAD(self) -> None:  # noqa: N802
        if self._app_route():
            return
        if dsh_inbox.handle_files(self, "HEAD"):
            return
        if dsh_files.handle(self, "HEAD"):
            return
        self._proxy("HEAD")

    def do_PUT(self) -> None:  # noqa: N802
        self._proxy("PUT")

    def do_PATCH(self) -> None:  # noqa: N802
        self._proxy("PATCH")

    def do_DELETE(self) -> None:  # noqa: N802
        self._proxy("DELETE")

    def do_OPTIONS(self) -> None:  # noqa: N802
        self._proxy("OPTIONS")

    # ---------- bridge endpoints ----------

    def _bridge_route(self, method: str) -> bool:
        path = self.path.split("?", 1)[0]
        if not path.startswith("/_bridge"):
            return False

        if path == "/_bridge/info":
            devices = self.bridge.state.get("devices", {})
            ok = self.bridge.upstream.status()
            self._send_json(200, {
                "ok": True,
                "version": BRIDGE_VERSION,
                "upstream": ok,
                "upstreamError": None if ok else self.bridge.upstream.last_error,
                "paired": self.bridge.verify(self._cookie(COOKIE_NAME)) is not None,
                "devices": len(devices),
                "pinRequired": True,
                "lan": _lan_ip(),
                "port": self.server.server_address[1],
            })
            return True

        if path == "/_bridge/pair" and method == "POST":
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b"{}"
            try:
                payload = json.loads(raw.decode("utf-8") or "{}")
            except Exception:  # noqa: BLE001
                payload = {}
            pin = str(payload.get("pin") or "").strip()
            if not hmac.compare_digest(pin, self.bridge.pin):
                log("配对失败：PIN 不匹配")
                self._send_json(403, {"ok": False, "error": "PIN 不正确"})
                return True
            if not self.bridge.upstream.ensure():
                self._send_json(503, {"ok": False,
                                      "error": "电脑端 DSH 上游还没就绪，稍后重试",
                                      "detail": self.bridge.upstream.last_error})
                return True
            name = str(payload.get("name") or self.headers.get("User-Agent") or "phone")
            device_id, token = self.bridge.add_device(name)
            log("新设备配对：%s" % device_id)
            self._send_json(200, {"ok": True, "device": device_id}, {
                "Set-Cookie": "%s=%s; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax"
                              % (COOKIE_NAME, token),
            })
            return True

        if path == "/_bridge/revoke" and method == "POST":
            if self.bridge.verify(self._cookie(COOKIE_NAME)) is None:
                self._send_json(401, {"ok": False, "error": "未配对"})
                return True
            n = self.bridge.revoke_all()
            self._send_json(200, {"ok": True, "revoked": n})
            return True

        self._send_json(404, {"ok": False, "error": "unknown bridge endpoint"})
        return True

    # ---------- proxy ----------

    def _asr_route(self) -> bool:
        path = self.path.split("?", 1)[0]
        if path != "/asr" and not path.startswith("/asr/"):
            return False
        if not ensure_asr():
            self._send_json(503, {"ok": False, "error": "ASR 服务未就绪（电脑端未安装或启动失败）"})
            return True
        method = self.command
        length = int(self.headers.get("Content-Length", "0") or 0)
        body = self.rfile.read(length) if length else None
        try:
            req = urllib.request.Request(
                "http://127.0.0.1:%d%s" % (ASR_PORT, self.path),
                data=body,
                method=method,
            )
            ctype = self.headers.get("Content-Type")
            if ctype:
                req.add_header("Content-Type", ctype)
            with urllib.request.urlopen(req, timeout=180) as resp:
                raw = resp.read()
                self.send_response(resp.status)
                self.send_header("Content-Type", resp.headers.get("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(raw)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(raw)
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            self.send_response(exc.code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except Exception as exc:
            log("ASR 代理失败：%s" % exc)
            self._send_json(502, {"ok": False, "error": "ASR 代理失败：%s" % exc})
        return True

    def _proxy(self, method: str) -> None:
        if self.headers.get("Upgrade", "").lower() == "websocket":
            self._proxy_ws()
            return

        device = self.bridge.verify(self._cookie(COOKIE_NAME))
        if device is None:
            # 允许浏览器直接来看配对页；其余交给上游前必须先配对。
            if method == "GET" and not self.path.startswith("/api"):
                self._send_html(200, PAIR_PAGE)
            else:
                self._send_json(401, {"ok": False, "error": "未配对：请先在 App 里输入 PIN"})
            return

        if not self.bridge.upstream.ensure():
            self._send_json(503, {"ok": False,
                                  "error": "上游未就绪",
                                  "detail": self.bridge.upstream.last_error})
            return

        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None

        # 诊断：把 prompt 请求体留一份（截断），方便核对前端到底发了什么
        if body and self.path.startswith("/api/session/prompt"):
            try:
                log("prompt 请求体: " + body[:600].decode("utf-8", "replace"))
            except Exception:  # noqa: BLE001
                pass

        headers = {}
        for k, v in self.headers.items():
            if k.lower() in HOP_BY_HOP or k.lower() in ("cookie", "origin", "referer"):
                continue
            headers[k] = v

        # 文档请求要注入 HTML：必须拿到未压缩的原文，否则会把 gzip 字节当 UTF-8 处理。
        is_document = method == "GET" and "text/html" in (self.headers.get("Accept") or "")
        if is_document:
            headers.pop("Accept-Encoding", None)
            headers["Accept-Encoding"] = "identity"

        headers.update(self.bridge.upstream.headers_for())
        if body is not None:
            headers["Content-Length"] = str(len(body))

        try:
            conn = http.client.HTTPConnection("127.0.0.1", self.bridge.upstream.port, timeout=120)
            conn.request(method, self.path, body=body, headers=headers)
            resp = conn.getresponse()
        except Exception as exc:  # noqa: BLE001
            log("代理失败：%s" % exc)
            self._send_json(502, {"ok": False, "error": "上游连接失败", "detail": str(exc)})
            return

        ctype = (resp.getheader("Content-Type") or "").lower()
        inject = "text/html" in ctype

        # 诊断：/api 上的业务错误（HTTP 往往仍是 200），把短响应体记一笔
        if inject is False and self.path.startswith("/api/") and "json" in ctype:
            try:
                # 必须完整读取：以前只读 64KB，带图片的会话响应会被截断，
                # 前端 JSON.parse 直接失败 —— 表现为“图片加载失败”。
                raw = resp.read()
                head = raw[:8192]
                if b'"ok":false' in head or b'"error"' in head:
                    log("API 错误 %s %s -> %s" % (method, self.path,
                                                 head[:400].decode("utf-8", "replace")))
                self.send_response(resp.status)
                for k, v in resp.getheaders():
                    if k.lower() in ("connection", "keep-alive", "set-cookie",
                                     "transfer-encoding", "content-length"):
                        continue
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                if method != "HEAD":
                    self.wfile.write(raw)
                conn.close()
                return
            except Exception as exc:  # noqa: BLE001
                log("JSON 转发失败 %s: %s" % (self.path, exc))

        if inject:
            raw = resp.read()
            if (resp.getheader("Content-Encoding") or "").lower() == "gzip":
                try:
                    import gzip as _gzip
                    raw = _gzip.decompress(raw)
                except Exception:  # noqa: BLE001
                    pass
            try:
                html = raw.decode("utf-8", "replace")
            except Exception:  # noqa: BLE001
                html = ""
            html = _inject(html, bool(self.bridge.cfg.get("inject_mobile", False)))
            data = html.encode("utf-8")
            self.send_response(resp.status)
            for k, v in resp.getheaders():
                lk = k.lower()
                if lk in ("content-length", "transfer-encoding", "connection", "set-cookie",
                          "content-encoding", "content-security-policy"):
                    continue
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            if method != "HEAD":
                self.wfile.write(data)
            conn.close()
            return

        # 诊断：附件上传把上游响应原样记录一份，方便定位「图片传不上去」
        if self.path.startswith("/api/session/attachment"):
            raw = resp.read(2 * 1024 * 1024)
            snippet = raw[:400].decode("utf-8", "replace").replace("\n", " ")
            log("attachment 响应 %s %s" % (resp.status, snippet))
            self.send_response(resp.status)
            for k, v in resp.getheaders():
                if k.lower() in ("connection", "keep-alive", "set-cookie",
                                 "transfer-encoding", "content-length"):
                    continue
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            if method != "HEAD":
                self.wfile.write(raw)
            conn.close()
            return

        self.send_response(resp.status)
        for k, v in resp.getheaders():
            lk = k.lower()
            if lk in ("connection", "keep-alive", "set-cookie", "transfer-encoding"):
                continue
            self.send_header(k, v)
        if resp.getheader("Content-Length") is None:
            self.send_header("Transfer-Encoding", "chunked")
            chunked = True
        else:
            chunked = False
        self.end_headers()

        try:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                if chunked:
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                else:
                    self.wfile.write(chunk)
                self.wfile.flush()
            if chunked:
                self.wfile.write(b"0\r\n\r\n")
        except Exception:  # noqa: BLE001
            pass
        finally:
            conn.close()

    def _proxy_ws(self) -> None:
        if self.bridge.verify(self._cookie(COOKIE_NAME)) is None:
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if not self.bridge.upstream.ensure():
            self.send_response(503)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        try:
            upstream = socket.create_connection(("127.0.0.1", self.bridge.upstream.port), timeout=15)
        except Exception as exc:  # noqa: BLE001
            log("WS 上游连接失败：%s" % exc)
            self.send_response(502)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        lines = ["%s %s HTTP/1.1" % (self.command, self.path)]
        for k, v in self.headers.items():
            lk = k.lower()
            if lk in ("host", "origin", "cookie", "referer", "connection", "upgrade",
                      "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions",
                      "sec-websocket-protocol"):
                continue
            lines.append("%s: %s" % (k, v))
        up_h = self.bridge.upstream.headers_for()
        lines.append("Host: " + up_h["Host"])
        lines.append("Origin: " + up_h["Origin"])
        if up_h.get("Cookie"):
            lines.append("Cookie: " + up_h["Cookie"])
        for k in ("Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions",
                  "Sec-WebSocket-Protocol", "Connection", "Upgrade"):
            if self.headers.get(k):
                lines.append("%s: %s" % (k, self.headers.get(k)))
        upstream.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))

        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = upstream.recv(4096)
            if not chunk:
                break
            buf += chunk
        head, _, rest = buf.partition(b"\r\n\r\n")
        if not head:
            upstream.close()
            self.close_connection = True
            return
        self.connection.sendall(head + b"\r\n\r\n")
        if rest:
            self.connection.sendall(rest)

        head_text = head.decode("latin-1", "replace")
        if " 101 " not in head_text.split("\r\n", 1)[0]:
            upstream.close()
            self.close_connection = True
            return

        self.close_connection = True
        stop = threading.Event()

        def up_to_client() -> None:
            try:
                while not stop.is_set():
                    data = upstream.recv(65536)
                    if not data:
                        break
                    self.connection.sendall(data)
            except Exception:  # noqa: BLE001
                pass
            finally:
                stop.set()

        t = threading.Thread(target=up_to_client, daemon=True)
        t.start()
        try:
            while not stop.is_set():
                data = self.rfile.read1(65536)
                if not data:
                    break
                upstream.sendall(data)
        except Exception:  # noqa: BLE001
            pass
        finally:
            stop.set()
            try:
                upstream.shutdown(socket.SHUT_RDWR)
            except Exception:  # noqa: BLE001
                pass
            upstream.close()
            t.join(timeout=2)


def _inject(html: str, enabled: bool = True) -> str:
    """把手机端外壳补丁塞进 HTML。

    2026-10-25 起默认关闭：同样的 CSS/JS 已经固化成 dsh 宿主插件
    `@local/dsh-mobile-shell`（profiles/phone/cordis.patch.yml），
    桥接器再注入就会出现两份。保留本函数作为回滚手段。
    """
    if not enabled:
        return html
    head = MOBILE_HEAD.replace("__VERSION_JSON__", json.dumps(BRIDGE_VERSION))
    if "</head>" in html:
        html = html.replace("</head>", head + "</head>", 1)
    else:
        html = head + html
    if "</body>" in html:
        html = html.replace("</body>", MOBILE_TAIL + "</body>", 1)
    else:
        html += MOBILE_TAIL
    return html


def _lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:  # noqa: BLE001
        return "127.0.0.1"


class ThreadingServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def load_config(path: str) -> dict:
    defaults = {
        "listen_host": "0.0.0.0",
        "listen_port": 8799,
        "upstream": None,
        "spawn": True,
        "dsh_profile": "phone",
        "upstream_port": 15099,
        "workspace": "",
        "attach": "",
        "upstream_cookie": "",
        "pin": "",
        # 手机端 CSS/JS 已固化为 dsh 插件，默认不再由桥接器注入
        "inject_mobile": False,
        # ---- 电脑文件浏览/下载（/files）----
        # 空列表或 ["*"] = 本机所有盘符 + 用户目录 + workspace；也可写死允许的目录
        "file_roots": [],
        "show_hidden": False,
        "file_zip_limit_mb": 2048,
        "file_zip_max_files": 20000,
        # ---- 手机 → 电脑 文件上传 ----
        # 默认收件目录：留空 = <workspace>/手机上传
        "upload_dir": "",
        "upload_max_mb": 4096,
    }
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as fh:
                defaults.update(json.load(fh))
        except Exception as exc:  # noqa: BLE001
            log("读取配置失败，使用默认值：%s" % exc)
    return defaults


def main() -> int:
    ap = argparse.ArgumentParser(description="DSH Phone Bridge")
    ap.add_argument("--config", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), CONFIG_FILE))
    ap.add_argument("--host", default=None)
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--upstream", default=None)
    ap.add_argument("--attach", default=None,
                    help="附着到已在跑的 DSH 实例，例如 127.0.0.1:14986（桌面端）")
    ap.add_argument("--no-spawn", action="store_true")
    args = ap.parse_args()

    cfg = load_config(args.config)
    if args.host:
        cfg["listen_host"] = args.host
    if args.port:
        cfg["listen_port"] = args.port
    if args.upstream:
        cfg["upstream"] = args.upstream
    if args.attach:
        cfg["attach"] = args.attach
    if args.no_spawn:
        cfg["spawn"] = False

    handler = type("BoundHandler", (Handler,), {"bridge": Bridge(cfg)})

    # 端口可能被系统保留段占用（重启后 Hyper-V/WSL 会变），依次退让
    want = int(cfg["listen_port"])
    candidates = [want] + [p for p in (8899, 18099, 28099, 38099) if p != want]
    srv = None
    bind_error = None
    for port in candidates:
        try:
            srv = ThreadingServer((cfg["listen_host"], port), handler)
            if port != want:
                log("端口 %d 绑不上（可能被系统保留段占用），改用 %d" % (want, port))
            cfg["listen_port"] = port
            break
        except OSError as exc:
            bind_error = exc
            continue
    if srv is None:
        log("所有候选端口都绑不上：%s" % bind_error)
        return 1

    ip = _lan_ip()
    log("DSH Phone Bridge %s" % BRIDGE_VERSION)
    log("本机地址   http://%s:%d/" % (ip, cfg["listen_port"]))
    log("配对 PIN   %s" % handler.bridge.pin)
    upstream_desc = ("附着 %s" % cfg["attach"]) if cfg.get("attach") else (
        "自动拉起 profile=%s port=%d" % (cfg["dsh_profile"], cfg["upstream_port"])
        if cfg["spawn"] else str(cfg["upstream"]))
    log("上游       %s" % upstream_desc)
    threading.Thread(target=lambda: handler.bridge.upstream.ensure(), daemon=True).start()

    def _keepalive() -> None:
        # 定期确认上游还活着，顺便把 /_bridge/info 的状态刷新成真的
        while True:
            time.sleep(20)
            try:
                handler.bridge.upstream.ensure()
            except Exception:  # noqa: BLE001
                pass

    threading.Thread(target=_keepalive, daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("退出")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
