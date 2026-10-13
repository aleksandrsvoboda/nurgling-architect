# Dev: launch with extra JVM flags and program args, e.g. -Jvm '-Darchitect.tracegrids=true' -AppArgs '--plan','x.hplan'
param([string]$Jvm = '', [string[]]$AppArgs = @())
Set-Location (Split-Path $PSScriptRoot -Parent)
& "$PSScriptRoot\stop.ps1"
$a = @($Jvm -split ' ' | Where-Object { $_ }) + '-Dsun.java2d.uiScale.enabled=false','-Dhaven.prefspec=architect','--add-exports=java.base/java.lang=ALL-UNNAMED','--add-exports=java.desktop/sun.awt=ALL-UNNAMED','--add-exports=java.desktop/sun.java2d=ALL-UNNAMED','--enable-native-access=ALL-UNNAMED','-Xss8m','-Xmx4096m','-jar','build\architect.jar' + $AppArgs
$p = Start-Process -FilePath java -ArgumentList $a -RedirectStandardOutput build\out.log -RedirectStandardError build\err.log -PassThru
"started $($p.Id)"
