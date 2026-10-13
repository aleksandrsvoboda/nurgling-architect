@echo off
rem Nurgling Architect - offline base planner. Uses the bundled Java in runtime\ when present.
cd /d "%~dp0"
set JAVA=java
if exist "runtime\bin\java.exe" set JAVA=runtime\bin\java.exe
"%JAVA%" -Dsun.java2d.uiScale.enabled=false -Dhaven.prefspec=architect ^
  --add-exports=java.base/java.lang=ALL-UNNAMED ^
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED ^
  --add-exports=java.desktop/sun.java2d=ALL-UNNAMED ^
  --enable-native-access=ALL-UNNAMED ^
  -Xss8m -Xms1024m -Xmx4096m -jar app\architect.jar %*
if errorlevel 1 pause
