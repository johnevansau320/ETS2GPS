@echo off
REM Builds the ETS2 road map from your game + active mods (first run / after mod changes),
REM then serves it to the phone app on port 31378. Needs Python 3.9+ (no extra packages).
cd /d "%~dp0"
python companion.py %*
pause
