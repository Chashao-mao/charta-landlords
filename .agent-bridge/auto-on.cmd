@echo off
> "%~dp0inbox.txt" echo auto on
echo sent: auto on
timeout /t 2 >nul
type "%~dp0outbox.txt" 2>nul | more +0
pause