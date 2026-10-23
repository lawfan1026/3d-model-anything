@echo off
powershell -NoProfile -File "%~dp0Start-PixelReplica.ps1"
if errorlevel 1 pause
