@echo off
rem Starts the ETS2 GPS lane overlay (needs PyQt6: pip install PyQt6). Extra arguments are passed on, e.g. --server http://192.168.1.20:31378
start "" pythonw "%~dp0lane_overlay.py" %*
