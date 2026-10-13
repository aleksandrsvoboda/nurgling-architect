# Dev launcher: starts the planner detached with logs in build\out.log / build\err.log.
Set-Location (Split-Path $PSScriptRoot -Parent)
& "$PSScriptRoot\stop.ps1"
$a = @($env:ARCHITECT_JVM -split ' ' | Where-Object { $_ }) + '-Dsun.java2d.uiScale.enabled=false','-Dhaven.prefspec=architect','--add-exports=java.base/java.lang=ALL-UNNAMED','--add-exports=java.desktop/sun.awt=ALL-UNNAMED','--add-exports=java.desktop/sun.java2d=ALL-UNNAMED','--enable-native-access=ALL-UNNAMED','-Xss8m','-Xmx4096m','-jar','build\architect.jar'
$p = Start-Process -FilePath java -ArgumentList $a -RedirectStandardOutput build\out.log -RedirectStandardError build\err.log -PassThru
"started $($p.Id)"
