@echo off
rem Creates a Startup shortcut so the POS server launches when Windows boots.
powershell -NoProfile -Command "$s=(New-Object -COM WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Startup')+'\PavBhajiPOS.lnk');$s.TargetPath='%~dp0PavBhajiPOS-server.exe';$s.WorkingDirectory='%~dp0';$s.Save()"
if %errorlevel%==0 (
  echo OK - server will start automatically when this laptop boots.
) else (
  echo Something went wrong. You can also do it manually:
  echo   Win+R, type: shell:startup, press Enter, and copy a shortcut
  echo   to PavBhajiPOS-server.exe into that folder.
)
pause
