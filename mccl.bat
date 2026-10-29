@echo off
setlocal
chcp 65001 >nul
set "BASE=%~dp0"
set "MCCL_HOME=%BASE%"
java "-Dfile.encoding=UTF-8" -cp "%BASE%out;%BASE%lib\gson-2.10.1.jar;%BASE%lib\jna-5.14.0.jar;%BASE%lib\jna-platform-5.14.0.jar" mccl.Main %*
endlocal
