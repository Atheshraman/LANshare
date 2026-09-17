@echo off
REM Run this as Administrator (right-click -> "Run as administrator").
REM Adds a firewall rule allowing inbound connections to the app on the
REM Private network profile, so other LAN peers can reach it even if the
REM automatic Windows prompt was dismissed or never appeared.

set APP_PATH="%ProgramFiles%\LAN Share\LAN Share.exe"

netsh adv-firewall firewall add rule name="LAN Share" dir=in action=allow program=%APP_PATH% enable=yes profile=private

echo.
echo Firewall rule added for %APP_PATH%
echo If your app is installed somewhere else, edit APP_PATH in this script and re-run.
pause