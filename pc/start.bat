@echo off
cd /d "%~dp0"
if not exist node_modules (
  echo Первый запуск: устанавливаю зависимости, подождите...
  call npm install
)
start "" "%~dp0node_modules\electron\dist\electron.exe" "%~dp0."
