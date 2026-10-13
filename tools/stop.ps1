Get-CimInstance Win32_Process -Filter "Name like 'java%'" | Where-Object { $_.CommandLine -like '*architect.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
