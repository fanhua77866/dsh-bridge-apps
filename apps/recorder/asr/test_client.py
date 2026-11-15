import base64
import os
import json
import sys
import time
import urllib.request
import wave

BASE = "http://127.0.0.1:8801"
WAV = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "test.wav")
SCRIPT = "今天天气真不错，适合出门走一走。"


def post(path, obj, timeout=180):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def get(path, timeout=180):
    with urllib.request.urlopen(BASE + path, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    t0 = time.time()
    print("health:", get("/asr/health"))
    w = wave.open(WAV)
    sr = w.getframerate()
    pcm = w.readframes(w.getnframes())
    w.close()
    st = post("/asr/session/start", {"script": SCRIPT, "sampleRate": sr, "model": os.environ.get("DSH_MODEL"), "mode": os.environ.get("DSH_MODE", "loose")})
    sid = st["session"]
    print("session:", sid, "total:", st.get("total"), "startLatency=%.2fs" % (time.time() - t0))
    chunk = int(sr * 2 * 0.25)
    seq = 0
    final = st
    for i in range(0, len(pcm), chunk):
        seq += 1
        piece = pcm[i:i + chunk]
        t1 = time.time()
        final = post("/asr/chunk", {
            "session": sid, "seq": seq, "pcm": base64.b64encode(piece).decode(),
        })
        print("chunk %02d matched %s/%s transcript=%r  (%.2fs)" % (
            seq, final.get("matched"), final.get("total"), (final.get("transcript") or "")[-24:], time.time() - t1))
    t2 = time.time()
    end = post("/asr/session/end", {"session": sid})
    print("final:", json.dumps({k: end.get(k) for k in ("matched", "total", "transcript")}, ensure_ascii=False))
    for c in end.get("chars", [])[:30]:
        print("  %s  %5d-%5d ms  status=%s" % (c.get("ch"), c.get("startMs"), c.get("endMs"), c.get("status")))
    print("endLatency=%.2fs total=%.2fs" % (time.time() - t2, time.time() - t0))


if __name__ == "__main__":
    main()
