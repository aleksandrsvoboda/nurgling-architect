# Brings the planner window to the front (for screenshots during development).
$p = Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object { $_.CommandLine -like '*architect.jar*' } | Select-Object -First 1
if($p) { Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.Interaction]::AppActivate([int]$p.ProcessId) }
