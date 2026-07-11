@echo off
rem Run as administrator if phones cannot connect (firewall blocked the server).
netsh advfirewall firewall delete rule name="PavBhajiPOS" >nul 2>&1
netsh advfirewall firewall add rule name="PavBhajiPOS" dir=in action=allow program="%~dp0PavBhajiPOS-server.exe" enable=yes profile=private,domain
if %errorlevel%==0 (
  echo OK - firewall now allows the POS server on private networks.
) else (
  echo FAILED - right-click this file and choose "Run as administrator".
)
pause
