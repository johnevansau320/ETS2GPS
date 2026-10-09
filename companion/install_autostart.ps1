# Starts the ETS2 GPS companion automatically (hidden, no window) every time you log in to Windows.
# Run:  right-click > "Run with PowerShell"   (no administrator rights needed)
$ErrorActionPreference = "Stop"
$src = Split-Path -Parent $MyInvocation.MyCommand.Path

# Use the REAL python (not the Microsoft-Store "WindowsApps" alias, which often fails at logon).
$py = $null
$pyExe = (Get-Command python.exe -ErrorAction Stop).Source
$real = (& $pyExe -c "import sys; print(sys.executable)").Trim()
$cand = Join-Path (Split-Path $real) "pythonw.exe"
if (Test-Path $cand) { $py = $cand } else { throw "pythonw.exe not found next to $real" }

# Run from a permanent folder so moving/deleting the download folder cannot break autostart.
$dir = Join-Path $env:LOCALAPPDATA "ETS2-GPS-companion"
New-Item -ItemType Directory -Force $dir | Out-Null
Get-ChildItem $src -Exclude "__pycache__","rb_debug.json","companion.log" | Copy-Item -Destination $dir -Recurse -Force
$script = Join-Path $dir "companion.py"

$action   = New-ScheduledTaskAction -Execute $py -Argument "`"$script`"" -WorkingDirectory $dir
$trigger  = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$trigger.Delay = "PT30S"   # give the network a moment first
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
            -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "ETS2 GPS companion" -Action $action -Trigger $trigger -Settings $settings `
    -Description "Serves the ETS2 road map and speaks guidance for the ETS2 GPS phone app" -Force | Out-Null

Write-Host "Installed: the companion will start hidden at every login."
Write-Host "Python : $py"
Write-Host "Folder : $dir"
Write-Host "Log    : $(Join-Path $dir 'companion.log')"
$ans = Read-Host "Start it now too? (y/n)"
if ($ans -eq "y") { Start-ScheduledTask -TaskName "ETS2 GPS companion"; Write-Host "Started." }
