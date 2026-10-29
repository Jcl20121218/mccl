@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"
if exist out rd /s /q out
mkdir out
set "srcs="
for /f "delims=" %%i in ('dir /s /b src\*.java') do set "srcs=!srcs! "%%i""
javac -encoding UTF-8 -cp "lib\gson-2.10.1.jar;lib\jna-5.14.0.jar;lib\jna-platform-5.14.0.jar" -d out !srcs!
if %errorlevel%==0 (echo ==== Compile OK ====) else (echo ==== Compile FAILED ====)
pause
