@echo off
> "%~dp0inbox.txt" echo dump
echo sent: dump
timeout /t 2 >nul
type "%~dp0outbox.txt" 2>nul | more +0
pause