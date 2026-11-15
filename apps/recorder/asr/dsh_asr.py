"""DSH 录音实时识别服务（faster-whisper，CPU int8 / 可选 GPU）。

协议（JSON over HTTP，由 dsh_phone_bridge.py 的 /asr/* 路由转发到本服务）：
  POST /asr/session/start  {sampleRate, script}        -> {session}
  POST /asr/chunk          {session, seq, pcm(base64)} -> {matched,total,chars,transcript,done}
  POST /asr/session/end    {session}                  -> {matched,total,chars,transcript}
  GET  /asr/health                                     -> {ok, model, device, queue}
  GET  /asr/model                                      -> 服务与模型状态

chars: [{i: 脚本字下标, ch, startMs, endMs, status: 0未读/1读对/2读错, chIndex: 第几个发音字}]
时间均为相对本句音频起点的毫秒。
"""
from __future__ import annotations

import base64
import json
import os
import re
import sys
import threading
import time
import traceback
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("DSH_ASR_PORT", "8801"))
MODEL_SIZE = os.environ.get("DSH_ASR_MODEL", "small")
MODEL_DIR = os.environ.get("DSH_ASR_MODEL_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "models"))
_DEVICE_ENV = os.environ.get("DSH_ASR_DEVICE", "cpu")  # 默认 CPU；显存被其它程序占用时 GPU 不稳，需要时设 auto/cuda
DEVICE = "cpu"
COMPUTE_TYPE = "int8"


def _resolve_device():
    """自动选 GPU（有 CUDA 就用 4070），否则 CPU int8。"""
    global DEVICE, COMPUTE_TYPE
    if _DEVICE_ENV and _DEVICE_ENV != "auto":
        DEVICE = _DEVICE_ENV
    else:
        try:
            import ctranslate2
            DEVICE = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
        except Exception:
            DEVICE = "cpu"
    COMPUTE_TYPE = os.environ.get(
        "DSH_ASR_COMPUTE", "int8_float16" if DEVICE == "cuda" else "int8"
    )


_resolve_device()
MAX_WINDOW_MS = 6_000
DECODE_MIN_NEW_MS = 1_200
PUNCT = set(" \t\r\n，。！？；：、,.!?;:\"'“”‘’（）()《》<>【】[]—-…·~～")

model = None
MODELS = {}
model_lock = threading.Lock()
SESSIONS: dict[str, "Session"] = {}
SESSIONS_LOCK = threading.Lock()


def speakable_positions(text: str):
    """返回 (speakable_chars, text_positions)：去掉标点/空白后的脚本字及其在原文下标。"""
    chars, positions = [], []
    for i, ch in enumerate(text):
        if ch not in PUNCT:
            chars.append(ch)
            positions.append(i)
    return chars, positions


class Session:
    def __init__(self, script: str, sample_rate: int, model_size: str = None, mode: str = "loose"):
        self.id = uuid.uuid4().hex[:12]
        self.script = script
        self.model_size = model_size or MODEL_SIZE
        self.mode = mode or "loose"
        self.sample_rate = sample_rate
        self.audio = bytearray()
        self.created = time.time()
        self.lock = threading.Lock()
        self.decode_lock = threading.Lock()
        self.decoding = False
        self.closed = False
        self.last_decode_ms = -1
        self.chars = []          # 逐字结果 [{i,ch,startMs,endMs,status}]
        self.matched = 0         # 已确认前缀字数（发音字序号）
        self.speak_chars, self.positions = speakable_positions(script)
        self.transcript = ""
        threading.Thread(target=self._decoder_loop, daemon=True).start()

    @property
    def duration_ms(self) -> int:
        return int(len(self.audio) / (self.sample_rate * 2) * 1000)

    def apply_alignment(self, words, win_start_ms: int):
        """把识别出的字按顺序对齐到脚本，累积更新状态（保守：只追加新匹配）。"""
        import difflib

        recognized = []
        for w in words:
            text = w.get("text", "")
            for ch in text:
                if ch in PUNCT or ch == " ":
                    continue
                recognized.append((ch, w, len(recognized)))
        if not recognized:
            return
        rec_chars = [r[0] for r in recognized]
        start_idx = max(0, self.matched - 2)
        script_rest = self.speak_chars[start_idx:]
        sm = difflib.SequenceMatcher(a=script_rest, b=rec_chars, autojunk=False)
        new_rows = {r["i"]: r for r in self.chars}
        advanced = start_idx
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == "equal":
                for off in range(i2 - i1):
                    si = start_idx + i1 + off
                    if si < advanced:
                        continue
                    ch = self.speak_chars[si]
                    rec = recognized[j1 + off]
                    word = rec[1]
                    # 单字在词内的相对位置：按词起止均分
                    wtext = [c for c in word.get("text", "") if c not in PUNCT and c != " "]
                    try:
                        pos = wtext.index(ch)
                    except ValueError:
                        pos = 0
                    w_start = int(word.get("start", 0) * 1000) + win_start_ms
                    w_end = int(word.get("end", 0) * 1000) + win_start_ms
                    span = max(60, int((w_end - w_start) / max(1, len(wtext))))
                    cs = w_start + pos * span
                    ce = min(w_end, cs + span) if w_end > cs else cs + span
                    new_rows[self.positions[si]] = {
                        "i": self.positions[si], "ch": ch, "startMs": cs, "endMs": ce, "status": 1,
                    }
                    advanced = si + 1
            elif tag == "replace":
                for off in range(i2 - i1):
                    si = start_idx + i1 + off
                    if si < advanced:
                        continue
                    if j1 + off >= j2:
                        break
                    rec = recognized[j1 + off]
                    word = rec[1]
                    w_start = int(word.get("start", 0) * 1000) + win_start_ms
                    w_end = int(word.get("end", 0) * 1000) + win_start_ms
                    new_rows[self.positions[si]] = {
                        "i": self.positions[si], "ch": rec[0],
                        "startMs": w_start, "endMs": max(w_end, w_start + 120), "status": 2,
                    }
                    advanced = si + 1
            # delete/insert：不推进（漏读由后续判断处理）
        self.matched = max(self.matched, advanced)
        self.chars = sorted(new_rows.values(), key=lambda r: r["i"])

    def _decoder_loop(self):
        while not self.closed:
            time.sleep(0.12)
            if self.closed:
                break
            if self.duration_ms - self.last_decode_ms < DECODE_MIN_NEW_MS:
                continue
            try:
                self.decode_once()
            except Exception:
                traceback.print_exc()

    def decode_once(self, final: bool = False):
        acquired = self.decode_lock.acquire(blocking=final, timeout=40 if final else -1)
        if not acquired:
            return
        self.decoding = True
        try:
            words, win_start = transcribe_window(self, final)
            if words:
                self.transcript = "".join(w["text"] for w in words)
                self.apply_alignment(words, win_start)
                self.last_decode_ms = self.duration_ms
        finally:
            self.decoding = False
            self.decode_lock.release()

    def to_payload(self, final: bool = False) -> dict:
        total = len(self.speak_chars)
        return {
            "matched": self.matched,
            "total": total,
            "chars": self.chars,
            "transcript": self.transcript,
            "done": final or (total > 0 and self.matched >= total),
            "durationMs": self.duration_ms,
        }


def get_model(size: str = None):
    global model
    global MODELS
    size = (size or MODEL_SIZE).strip() or MODEL_SIZE
    local_dir = os.path.join(MODEL_DIR, size)
    if not os.path.isfile(os.path.join(local_dir, "model.bin")):
        # 请求的模型没下载 → 回退默认模型
        size = MODEL_SIZE
        local_dir = os.path.join(MODEL_DIR, size)
    with model_lock:
        if MODELS.get(size) is None:
            from faster_whisper import WhisperModel

            os.makedirs(MODEL_DIR, exist_ok=True)
            if os.path.isfile(os.path.join(local_dir, "model.bin")):
                MODELS[size] = WhisperModel(
                    local_dir, device=DEVICE, compute_type=COMPUTE_TYPE, local_files_only=True
                )
            else:
                MODELS[size] = WhisperModel(
                    size, device=DEVICE, compute_type=COMPUTE_TYPE,
                    download_root=MODEL_DIR, local_files_only=False,
                )
            model = MODELS[size]
        return MODELS[size]


def transcribe_window(session: Session, final: bool = False):
    """解码窗口末尾音频，返回 (words, window_start_ms)。"""
    with session.lock:
        data = bytes(session.audio)
    if len(data) < session.sample_rate * 2 * 0.3:
        return [], 0
    if final:
        window_bytes = data
        win_start = 0
    else:
        max_bytes = int(session.sample_rate * 2 * MAX_WINDOW_MS / 1000)
        window_bytes = data[-max_bytes:]
        win_start = session.duration_ms - int(len(window_bytes) / (session.sample_rate * 2) * 1000)
    import numpy as np

    audio = np.frombuffer(window_bytes, dtype=np.int16).astype(np.float32) / 32768.0
    # 实时窗口用 small（低延迟）；句末最终对齐用用户选的模型（更准，可接受几秒）
    # GPU 时实时也直接用用户选的模型（medium 也够快）；CPU 时实时用小模型保低延迟
    if DEVICE == "cuda":
        live_size = session.model_size
    else:
        live_size = "small" if os.path.isfile(os.path.join(MODEL_DIR, "small", "model.bin")) else MODEL_SIZE
    m = get_model(session.model_size if final else live_size)
    # 只用中性普通话提示（不用整句脚本，避免脑补未读内容）；口音靠更大模型补偿
    segments, info = m.transcribe(
        audio,
        language="zh",
        beam_size=5 if final else 1,
        best_of=5 if final else 1,
        vad_filter=False,
        word_timestamps=True,
        condition_on_previous_text=False,
        initial_prompt="以下是普通话的句子。",
    )
    words = []
    for seg in segments:
        for w in (seg.words or []):
            words.append({"text": w.word, "start": w.start, "end": w.end})
    return words, win_start


class Handler(BaseHTTPRequestHandler):
    server_version = "DshAsr/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("[asr] " + (fmt % args) + "\n")

    def _read_json(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8") or "{}")

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/asr/health"):
            try:
                get_model()
                available = [d for d in os.listdir(MODEL_DIR) if os.path.isfile(os.path.join(MODEL_DIR, d, "model.bin"))] if os.path.isdir(MODEL_DIR) else []
                self._send(200, {
                    "ok": True,
                    "model": MODEL_SIZE,
                    "models": available,
                    "device": DEVICE,
                    "compute": COMPUTE_TYPE,
                    "sessions": len(SESSIONS),
                })
            except Exception as e:
                self._send(500, {"ok": False, "error": str(e)})
        elif self.path.startswith("/asr/model"):
            self._send(200, {"model": MODEL_SIZE, "device": DEVICE, "compute": COMPUTE_TYPE,
                             "modelDir": MODEL_DIR, "port": PORT})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            req = self._read_json()
        except Exception as e:
            self._send(400, {"error": "bad json: %s" % e})
            return
        try:
            if self.path.startswith("/asr/session/start"):
                script = str(req.get("script", ""))
                sr = int(req.get("sampleRate", 48000))
                model_size = req.get("model") or None
                mode = str(req.get("mode", "loose"))
                sess = Session(script, sr, model_size=model_size, mode=mode)
                with SESSIONS_LOCK:
                    SESSIONS[sess.id] = sess
                get_model()  # 预热
                self._send(200, {"session": sess.id, "total": len(sess.speak_chars)})
            elif self.path.startswith("/asr/chunk"):
                sess = SESSIONS.get(str(req.get("session", "")))
                if sess is None:
                    self._send(404, {"error": "session not found"})
                    return
                pcm = base64.b64decode(req.get("pcm", "") or "")
                with sess.lock:
                    sess.audio.extend(pcm)
                self._send(200, sess.to_payload())
            elif self.path.startswith("/asr/session/end"):
                sess = SESSIONS.get(str(req.get("session", "")))
                if sess is None:
                    self._send(404, {"error": "session not found"})
                    return
                sess.closed = True
                sess.decode_once(final=True)
                payload = sess.to_payload(final=True)
                with SESSIONS_LOCK:
                    SESSIONS.pop(sess.id, None)
                self._send(200, payload)
            else:
                self._send(404, {"error": "not found"})
        except Exception as e:
            traceback.print_exc()
            self._send(500, {"error": str(e)})


def main():
    print("DSH ASR service   model=%s device=%s compute=%s modelDir=%s" % (MODEL_SIZE, DEVICE, COMPUTE_TYPE, MODEL_DIR), flush=True)
    print("loading model...", flush=True)
    get_model()
    print("model ready; listening 0.0.0.0:%d" % PORT, flush=True)
    srv = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    srv.serve_forever()


if __name__ == "__main__":
    main()
