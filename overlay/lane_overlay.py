"""ETS2 GPS lane overlay: the lane panel as an always-on-top, movable, resizable window on the PC screen.

    pythonw lane_overlay.py                         # talks to the companion on this PC (http://127.0.0.1:31378)
    pythonw lane_overlay.py --server http://192.168.1.20:31378

The GPS app on the tablet (or the web app) sends the lane panel to the companion; this window shows it.

  * drag the panel to move it, drag its edges / corners to resize, mouse wheel = resize
  * Ctrl + mouse wheel = opacity, or right-click > Opacity (also in the tray icon menu)
  * right-click: opacity, size presets, always on top, hide when there is no lane data, demo lanes (for placing it)

Needs PyQt6 (pip install PyQt6). ETS2 must run in windowed / borderless mode for any overlay to show on top of it.
"""
import argparse
import json
import math
import os
import sys
import threading
import time
import urllib.request

from PyQt6.QtCore import QPointF, QRectF, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import QAction, QColor, QCursor, QFont, QIcon, QPainter, QPainterPath, QPen, QPixmap
from PyQt6.QtWidgets import (QApplication, QInputDialog, QLabel, QMenu, QSlider, QSystemTrayIcon, QVBoxLayout,
                             QWidget, QWidgetAction)

CELL, PAD, PANEL_H = 50.0, 10.0, 112.0   # design size of the lane panel (same as the tablet / web panel)
EDGE = 9                                 # width of the resize zone at the window edges, in pixels
STALE_S = 6.0                            # lane data older than this counts as "no data"
DEFAULT_SERVER = "http://127.0.0.1:31378"
SETTINGS = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "ETS2-GPS", "lane_overlay.json")
DEMO = {"lanes": [{"a": 0, "p": True, "alt": None}, {"a": 0, "p": True, "alt": None}, {"a": 12, "p": False, "alt": None}],
        "caption": "6.4 km", "age": 0.0}


def load_settings():
    try:
        with open(SETTINGS, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_settings(d):
    try:
        os.makedirs(os.path.dirname(SETTINGS), exist_ok=True)
        with open(SETTINGS, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=1)
    except OSError:
        pass


# ------------------------------------------------------------------------------ drawing --

def design_size(n_lanes, caption):
    return max(n_lanes * CELL + 2 * PAD, 80.0 if caption else 0.0), PANEL_H


def draw_arrow(p, cx, h, deg, color, bright):
    w, bottom, top = 7.0, h - 34.0, 14.0
    a = max(-100.0, min(100.0, deg))
    th = math.radians(a)
    dx, dy = math.sin(th), -math.cos(th)
    straight = abs(a) < 12
    bend_y = top + 8 if straight else (top + bottom) / 2
    ln = 0.0 if straight else 24.0
    ex, ey = cx + dx * ln, bend_y + dy * ln
    if straight:
        tip_x, tip_y, fx, fy = cx, top, 0.0, -1.0
    else:
        tip_x, tip_y, fx, fy = ex, ey, dx, dy
    hl, hw = 13.0, 11.0

    def shaft():
        path = QPainterPath(QPointF(cx, bottom))
        if straight:
            path.lineTo(cx, tip_y + hl * 0.97)
        else:
            path.lineTo(cx, bend_y)
            path.lineTo(ex - fx * hl * 0.97, ey - fy * hl * 0.97)
        return path

    p.setBrush(Qt.BrushStyle.NoBrush)  # the shaft is a stroke, never a filled shape
    if bright:
        glow = QPen(QColor(60, 235, 130, 80), w + 7)
        glow.setCapStyle(Qt.PenCapStyle.FlatCap); glow.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
        p.setPen(glow); p.drawPath(shaft())
    pen = QPen(color, w)
    pen.setCapStyle(Qt.PenCapStyle.FlatCap); pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
    p.setPen(pen); p.drawPath(shaft())
    bx, by = tip_x - fx * hl, tip_y - fy * hl
    head = QPainterPath(QPointF(tip_x, tip_y))
    head.lineTo(bx - fy * hw, by + fx * hw)
    head.lineTo(bx + fy * hw, by - fx * hw)
    head.closeSubpath()
    p.setPen(Qt.PenStyle.NoPen); p.setBrush(color); p.drawPath(head)


def draw_panel(p, lanes, caption, w, h):
    """Draws the panel in design coordinates (w x h)."""
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor(38, 46, 62, 235))
    p.drawRoundedRect(QRectF(0, 0, w, h), 14, 14)
    if caption:
        p.setPen(QColor(255, 255, 255, 230))
        f = QFont("Segoe UI"); f.setBold(True); f.setPixelSize(14); p.setFont(f)
        p.drawText(QRectF(0, h - 27, w, 20), Qt.AlignmentFlag.AlignCenter, caption)
    x0 = (w - len(lanes) * CELL) / 2
    for i, lane in enumerate(lanes):
        cx = x0 + CELL * (i + 0.5)
        if i > 0:
            pen = QPen(QColor(255, 255, 255, 110), 1.5, Qt.PenStyle.DashLine)
            p.setPen(pen)
            p.drawLine(QPointF(x0 + CELL * i, 10), QPointF(x0 + CELL * i, h - 30))
        if lane.get("alt") is not None:
            draw_arrow(p, cx, h, lane["alt"], QColor(255, 255, 255, 95), False)
        if lane.get("a") is None:
            continue
        if lane.get("p"):
            draw_arrow(p, cx, h, lane["a"], QColor(60, 235, 130), True)
        else:
            draw_arrow(p, cx, h, lane["a"], QColor(245, 247, 250), False)


# ------------------------------------------------------------------------------- window --

class Overlay(QWidget):
    data_in = pyqtSignal(object)

    def __init__(self, server, settings):
        super().__init__()
        self.server = server.rstrip("/")
        self.cfg = settings
        self.data = None            # last good reply of /lanes
        self.reachable = False
        self.demo = False
        self.clickthrough = False
        self.setWindowTitle("ETS2 GPS lane panel")
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setMouseTracking(True)
        self.setMinimumSize(110, 44)
        self.opacity = float(self.cfg.get("opacity", 0.9))
        self.topmost = bool(self.cfg.get("topmost", True))
        self.hide_idle = bool(self.cfg.get("hide_idle", False))
        self.apply_flags()
        w, h = self.cfg.get("size", [330, 150])
        self.resize(int(w), int(h))
        if "pos" in self.cfg:
            self.move(*[int(v) for v in self.cfg["pos"]])
        self.setWindowOpacity(self.opacity)
        self.save_later = QTimer(self); self.save_later.setSingleShot(True); self.save_later.setInterval(700)
        self.save_later.timeout.connect(self.persist)
        self.data_in.connect(self.on_data)
        self.stop = False
        threading.Thread(target=self.poll, daemon=True).start()
        self.timer = QTimer(self); self.timer.timeout.connect(self.tick); self.timer.start(500)
        self.make_tray()

    # ---- flags / settings
    def apply_flags(self):
        flags = Qt.WindowType.FramelessWindowHint
        if self.topmost:
            flags |= Qt.WindowType.WindowStaysOnTopHint
        if self.clickthrough:
            flags |= Qt.WindowType.WindowTransparentForInput
        was = self.isVisible()
        self.setWindowFlags(flags)
        if was:
            self.show()

    def persist(self):
        g = self.geometry()
        self.cfg.update({"pos": [g.x(), g.y()], "size": [g.width(), g.height()], "opacity": round(self.opacity, 2),
                         "topmost": self.topmost, "hide_idle": self.hide_idle, "server": self.server})
        save_settings(self.cfg)

    def closeEvent(self, e):
        self.stop = True
        self.persist()
        super().closeEvent(e)

    def moveEvent(self, e):
        super().moveEvent(e)
        self.save_later.start()

    def resizeEvent(self, e):
        super().resizeEvent(e)
        self.save_later.start()

    # ---- data
    def poll(self):
        while not self.stop:
            try:
                with urllib.request.urlopen(self.server + "/lanes", timeout=1.2) as r:
                    self.data_in.emit(json.loads(r.read().decode("utf-8")))
            except Exception:
                self.data_in.emit(None)
            time.sleep(0.25)

    def on_data(self, d):
        self.reachable = d is not None
        self.data = d
        self.update()

    def current(self):
        """(lanes, caption) to show, or None."""
        d = DEMO if self.demo else self.data
        if not d or not d.get("lanes"):
            return None
        age = d.get("age")
        if age is None or age > STALE_S:
            return None
        return d["lanes"], d.get("caption", "")

    def tick(self):
        idle = self.current() is None
        want_through = idle and self.hide_idle
        if want_through != self.clickthrough:
            self.clickthrough = want_through
            self.apply_flags()
        self.update()

    # ---- painting
    def paintEvent(self, _e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setRenderHint(QPainter.RenderHint.TextAntialiasing)
        cur = self.current()
        if cur is None:
            if self.hide_idle:
                return
            lanes, caption = [], ""
            w, h = 3 * CELL + 2 * PAD, PANEL_H
            s = min(self.width() / w, self.height() / h)
            p.translate((self.width() - w * s) / 2, (self.height() - h * s) / 2)
            p.scale(s, s)
            p.setPen(Qt.PenStyle.NoPen); p.setBrush(QColor(38, 46, 62, 150)); p.drawRoundedRect(QRectF(0, 0, w, h), 14, 14)
            p.setPen(QColor(255, 255, 255, 170))
            f = QFont("Segoe UI"); f.setPixelSize(13); p.setFont(f)
            msg = "Waiting for lane data" if self.reachable else "Companion not reachable"
            p.drawText(QRectF(8, 0, w - 16, h), Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap, msg)
            return
        lanes, caption = cur
        w, h = design_size(len(lanes), caption)
        s = min(self.width() / w, self.height() / h)
        p.translate((self.width() - w * s) / 2, (self.height() - h * s) / 2)
        p.scale(s, s)
        draw_panel(p, lanes, caption, w, h)

    # ---- mouse: move, resize, wheel
    def edges_at(self, pos):
        x, y, w, h = pos.x(), pos.y(), self.width(), self.height()
        e = Qt.Edge(0)
        if x < EDGE: e |= Qt.Edge.LeftEdge
        if x > w - EDGE: e |= Qt.Edge.RightEdge
        if y < EDGE: e |= Qt.Edge.TopEdge
        if y > h - EDGE: e |= Qt.Edge.BottomEdge
        return e

    def mouseMoveEvent(self, ev):
        e = self.edges_at(ev.position())
        L, R, T, B = Qt.Edge.LeftEdge, Qt.Edge.RightEdge, Qt.Edge.TopEdge, Qt.Edge.BottomEdge
        if e in (L | T, R | B):
            self.setCursor(Qt.CursorShape.SizeFDiagCursor)
        elif e in (R | T, L | B):
            self.setCursor(Qt.CursorShape.SizeBDiagCursor)
        elif e in (L, R):
            self.setCursor(Qt.CursorShape.SizeHorCursor)
        elif e in (T, B):
            self.setCursor(Qt.CursorShape.SizeVerCursor)
        else:
            self.setCursor(Qt.CursorShape.SizeAllCursor)

    def mousePressEvent(self, ev):
        if ev.button() == Qt.MouseButton.LeftButton:
            e = self.edges_at(ev.position())
            wh = self.windowHandle()
            if wh is None:
                return
            if e:
                wh.startSystemResize(e)
            else:
                wh.startSystemMove()
        elif ev.button() == Qt.MouseButton.RightButton:
            self.make_menu().exec(QCursor.pos())

    def mouseReleaseEvent(self, ev):
        self.persist()

    def wheelEvent(self, ev):
        step = 1 if ev.angleDelta().y() > 0 else -1
        if ev.modifiers() & Qt.KeyboardModifier.ControlModifier:
            self.set_opacity(self.opacity + 0.05 * step)
        else:
            f = 1.08 if step > 0 else 1 / 1.08
            self.resize(max(110, int(self.width() * f)), max(44, int(self.height() * f)))
            self.persist()

    def set_opacity(self, v):
        self.opacity = max(0.15, min(1.0, v))
        self.setWindowOpacity(self.opacity)
        self.persist()

    # ---- menu / tray
    def make_menu(self):
        m = QMenu()
        box = QWidget(); lay = QVBoxLayout(box); lay.setContentsMargins(12, 6, 12, 6)
        lab = QLabel("Opacity %d%%" % round(self.opacity * 100))
        sl = QSlider(Qt.Orientation.Horizontal); sl.setRange(15, 100); sl.setValue(round(self.opacity * 100)); sl.setMinimumWidth(180)

        def moved(v):
            lab.setText("Opacity %d%%" % v); self.set_opacity(v / 100.0)
        sl.valueChanged.connect(moved)
        lay.addWidget(lab); lay.addWidget(sl)
        wa = QWidgetAction(m); wa.setDefaultWidget(box); m.addAction(wa)
        sizes = m.addMenu("Size")
        for name, w in (("Small", 220), ("Medium", 330), ("Large", 480), ("Extra large", 700)):
            sizes.addAction(name, lambda w=w: self.set_width(w))
        m.addSeparator()
        a = QAction("Always on top", m, checkable=True, checked=self.topmost); a.triggered.connect(self.toggle_topmost); m.addAction(a)
        a = QAction("Hide when there is no lane data", m, checkable=True, checked=self.hide_idle)
        a.triggered.connect(self.toggle_hide_idle); m.addAction(a)
        a = QAction("Show demo lanes (for placing)", m, checkable=True, checked=self.demo)
        a.triggered.connect(self.toggle_demo); m.addAction(a)
        m.addSeparator()
        m.addAction("Server address…", self.ask_server)
        m.addAction("Reset position and size", self.reset_geometry)
        m.addSeparator()
        m.addAction("Quit", QApplication.quit)
        return m

    def make_tray(self):
        pm = QPixmap(32, 32); pm.fill(Qt.GlobalColor.transparent)
        p = QPainter(pm); p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setBrush(QColor(38, 46, 62)); p.setPen(Qt.PenStyle.NoPen); p.drawRoundedRect(QRectF(1, 1, 30, 30), 6, 6)
        p.scale(0.45, 0.45); p.translate(8, -4)
        draw_arrow(p, 16, 70, 0, QColor(60, 235, 130), False)
        p.end()
        self.tray = QSystemTrayIcon(QIcon(pm), self)
        self.tray.setToolTip("ETS2 GPS lane panel")
        self.tray.activated.connect(lambda reason: self.make_menu().exec(QCursor.pos()))
        self.tray.show()

    def set_width(self, w):
        cur = self.current()
        n = len(cur[0]) if cur else 3
        cap = cur[1] if cur else ""
        dw, dh = design_size(n, cap)
        self.resize(w, int(w * dh / dw))
        self.persist()

    def toggle_topmost(self, on):
        self.topmost = on; self.apply_flags(); self.persist()

    def toggle_hide_idle(self, on):
        self.hide_idle = on; self.persist(); self.update()

    def toggle_demo(self, on):
        self.demo = on; self.update()

    def ask_server(self):
        text, ok = QInputDialog.getText(self, "Companion address", "Companion URL (e.g. http://192.168.1.20:31378):", text=self.server)
        if ok and text.strip():
            t = text.strip()
            if not t.startswith("http"):
                t = "http://" + t
            self.server = t.rstrip("/"); self.persist()

    def reset_geometry(self):
        self.resize(330, 150); self.move(100, 100); self.persist()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default=None, help="companion URL, default http://127.0.0.1:31378")
    ap.add_argument("--demo", action="store_true", help="start with demo lanes shown")
    ap.add_argument("--snapshot", help="render the panel to this PNG and exit (self-test)")
    args = ap.parse_args()
    cfg = load_settings()
    server = args.server or cfg.get("server") or DEFAULT_SERVER
    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(True)
    ov = Overlay(server, cfg)
    if args.demo or args.snapshot:
        ov.demo = True
    if args.snapshot:
        ov.resize(330, 150)
        ov.grab().save(args.snapshot)
        return
    ov.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
