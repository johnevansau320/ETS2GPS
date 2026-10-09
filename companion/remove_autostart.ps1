# Removes the automatic start of the ETS2 GPS companion.
Unregister-ScheduledTask -TaskName "ETS2 GPS companion" -Confirm:$false -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*companion.py*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Write-Host "Autostart removed and the companion stopped."
