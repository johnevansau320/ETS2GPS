# Installs the lane overlay for the current user (no administrator rights): copies the exe to
# %LOCALAPPDATA%\Programs\ETS2 Lane Overlay and adds a Start Menu shortcut. Optionally starts it with Windows.
$ErrorActionPreference = "Stop"
$exe = Join-Path $PSScriptRoot "publish\Ets2LaneOverlay.exe"
if (-not (Test-Path $exe)) { $exe = Join-Path $PSScriptRoot "Ets2LaneOverlay.exe" }
if (-not (Test-Path $exe)) { throw "Ets2LaneOverlay.exe not found: run build.ps1 first (or put the exe next to this script)." }
$dir = Join-Path $env:LOCALAPPDATA "Programs\ETS2 Lane Overlay"
New-Item -ItemType Directory -Force $dir | Out-Null
Copy-Item $exe $dir -Force
$target = Join-Path $dir "Ets2LaneOverlay.exe"
$sh = New-Object -ComObject WScript.Shell
function Shortcut($path) { $s = $sh.CreateShortcut($path); $s.TargetPath = $target; $s.WorkingDirectory = $dir; $s.Description = "ETS2 GPS lane overlay"; $s.Save() }
Shortcut (Join-Path ([Environment]::GetFolderPath("Programs")) "ETS2 Lane Overlay.lnk")
Write-Host "Installed to $dir (Start Menu: ETS2 Lane Overlay)"
$ans = Read-Host "Also start it automatically when you log in to Windows? (y/n)"
if ($ans -eq "y") { Shortcut (Join-Path ([Environment]::GetFolderPath("Startup")) "ETS2 Lane Overlay.lnk"); Write-Host "Autostart added." }
$ans = Read-Host "Start it now? (y/n)"
if ($ans -eq "y") { Start-Process $target }
