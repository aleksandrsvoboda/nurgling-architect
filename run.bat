@echo off
rem Nurgling Architect - offline base planner. Same JVM flags as the game client.
cd /d "%~dp0"
java -Dsun.java2d.uiScale.enabled=false -Dhaven.prefspec=architect ^
  --add-exports=java.base/java.lang=ALL-UNNAMED ^
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED ^
  --add-exports=java.desktop/sun.java2d=ALL-UNNAMED ^
  --enable-native-access=ALL-UNNAMED ^
  -Xss8m -Xms1024m -Xmx4096m -jar build\architect.jar %*
