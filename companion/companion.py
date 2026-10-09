"""ETS2 GPS companion: builds the road map from your installed game + active mods and serves it to the phone.

    python companion.py                 # build if needed, then serve on http://<pc-ip>:31378/
    python companion.py --rebuild       # force a rebuild (e.g. after changing mods)
    python companion.py --no-mods       # base game + DLC only

The phone app talks to two things on this PC:
  * (optional) it can speak the phone's voice prompts on this PC, e.g. in your headset: POST /say
  * the TruckSim GPS Telemetry Server (port 31377) for live truck position
  * this companion (port 31378) once, to download the map file
"""
import argparse
import base64
import subprocess
import urllib.parse
import hashlib
import http.server
import json
import os
import socket
import socketserver
import sys
import threading
import time

import build_map
from gamefiles import active_mod_packages, find_game_dir, mount_everything

PORT = 31378
HERE = os.path.dirname(os.path.abspath(__file__))
MAP_FILE = os.path.join(HERE, "etsmap.bin")
META_FILE = os.path.join(HERE, "etsmap.meta.json")


# Run without a console (pythonw / scheduled task): send all output to companion.log instead of failing.
if sys.stdout is None or sys.stderr is None or "--log" in sys.argv:
    _lf = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "companion.log"), "a", encoding="utf-8", buffering=1)
    sys.stdout = sys.stderr = _lf
    sys.argv = [a for a in sys.argv if a != "--log"]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def lan_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


def fingerprint(game_dir, use_mods):
    """Hash of everything that changes the map: game archives + mod list."""
    h = hashlib.sha1()
    h.update(b"v%d" % build_map.FORMAT_VERSION)
    for f in sorted(os.listdir(game_dir)):
        if f.endswith(".scs") and (f.startswith(("base_map", "def", "dlc_")) or f == "base.scs"):
            st = os.stat(os.path.join(game_dir, f))
            h.update(("%s:%d:%d" % (f, st.st_size, int(st.st_mtime))).encode())
    if use_mods:
        pk = active_mod_packages(lambda *a: None) or []
        for m in pk:
            h.update(("%s|%s" % (m.get("package"), m.get("workshopId"))).encode())
    return h.hexdigest()


def ensure_map(args):
    gd = find_game_dir(args.game_dir)
    fp = fingerprint(gd, not args.no_mods)
    meta = {}
    if os.path.exists(META_FILE):
        try:
            meta = json.load(open(META_FILE))
        except (OSError, ValueError):
            meta = {}
    if args.rebuild or not os.path.exists(MAP_FILE) or meta.get("fingerprint") != fp:
        log("building map from %s ..." % gd)
        fs, _ = mount_everything(gd, log, use_mods=not args.no_mods)
        build_map.build(fs, MAP_FILE)
        meta = {"fingerprint": fp, "built": int(time.time()), "bytes": os.path.getsize(MAP_FILE),
                "mods": not args.no_mods}
        json.dump(meta, open(META_FILE, "w"))
    else:
        log("map is up to date (%.1f MB)" % (os.path.getsize(MAP_FILE) / 1e6))
    return meta


SPEAK_PS = r'''
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SetOutputToDefaultAudioDevice()
$s.Rate = [int]$env:ETS2_VOICE_RATE
if ($env:ETS2_VOICE_NAME) { try { $s.SelectVoice($env:ETS2_VOICE_NAME) } catch {} }
[Console]::Out.WriteLine("READY " + $s.Voice.Name)
[Console]::Out.Flush()
while (($line = [Console]::In.ReadLine()) -ne $null) {
    if ($line -eq "") { continue }
    $text = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($line))
    $s.SpeakAsyncCancelAll()
    [void]$s.SpeakAsync($text)
}
'''


class Speaker:
    """Speaks guidance prompts on this PC (its default audio device, e.g. your headset) via Windows SAPI."""

    def __init__(self, rate=0, voice=""):
        self.proc = None
        self.lock = threading.Lock()
        self.rate, self.voice = rate, voice
        self.path = os.path.join(HERE, "_speaker.ps1")

    def _start(self):
        with open(self.path, "w", encoding="utf-8-sig") as f:
            f.write(SPEAK_PS)
        env = dict(os.environ, ETS2_VOICE_RATE=str(self.rate), ETS2_VOICE_NAME=self.voice)
        self.proc = subprocess.Popen(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", self.path],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        line = self.proc.stdout.readline().decode("utf-8", "ignore").strip()
        log("PC speech engine:", line or "(no response)")

    def say(self, text):
        text = text.strip()[:300]
        if not text:
            return False
        with self.lock:
            try:
                if self.proc is None or self.proc.poll() is not None:
                    self._start()
                self.proc.stdin.write(base64.b64encode(text.encode("utf-8")) + b"\n")
                self.proc.stdin.flush()
                return True
            except (OSError, ValueError) as e:
                log("speech failed:", e)
                self.proc = None
                return False


SPEAKER = Speaker()


class Handler(http.server.BaseHTTPRequestHandler):
    meta = {}

    def log_message(self, fmt, *a):
        log(self.client_address[0], fmt % a)

    def _reply(self, code, body=b"ok", ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/say":
            n = min(int(self.headers.get("Content-Length") or 0), 2000)
            text = self.rfile.read(n).decode("utf-8", "ignore")
            self._reply(200 if SPEAKER.say(text) else 500)
        else:
            self._reply(404, b"not found")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/say":
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            self._reply(200 if SPEAKER.say((q.get("text") or [""])[0]) else 500)
        elif path == "/info":
            body = json.dumps(dict(self.meta, name="ets2-gps-companion", port=PORT)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/map.bin":
            size = os.path.getsize(MAP_FILE)
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("X-Map-Built", str(self.meta.get("built", 0)))
            self.end_headers()
            with open(MAP_FILE, "rb") as f:
                while True:
                    chunk = f.read(1 << 16)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        else:
            body = b"ETS2 GPS companion. Endpoints: /info, /map.bin\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game-dir")
    ap.add_argument("--no-mods", action="store_true")
    ap.add_argument("--rebuild", action="store_true")
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--voice-rate", type=int, default=0, help="PC voice speed, -10 (slow) .. 10 (fast)")
    ap.add_argument("--voice", default="", help="PC voice name, e.g. \"Microsoft Zira Desktop\"")
    args = ap.parse_args()
    Handler.meta = ensure_map(args)
    SPEAKER.rate, SPEAKER.voice = args.voice_rate, args.voice
    srv = Server(("0.0.0.0", args.port), Handler)
    ips = lan_ips()
    log("serving the map on port %d" % args.port)
    for ip in ips:
        log("  phone app map URL:  http://%s:%d/map.bin   (telemetry server: %s:31377)" % (ip, args.port, ip))
    log("If the phone cannot connect, allow inbound TCP %d in Windows Firewall:" % args.port)
    log('  netsh advfirewall firewall add rule name="ETS2 GPS companion" dir=in action=allow protocol=TCP localport=%d'
        % args.port)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
