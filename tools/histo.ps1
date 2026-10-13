$p = Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object { $_.CommandLine -like '*architect.jar*' } | Select-Object -First 1
if(-not $p) { "not running"; exit }
jcmd $p.ProcessId GC.class_histogram | Select-Object -First 25
