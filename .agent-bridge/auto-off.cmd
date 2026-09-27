@echo off
> "%~dp0inbox.txt" echo auto off
echo sent: auto off
timeout /t 2 >nul
type "%~dp0outbox.txt" 2>nul | more +0
pause