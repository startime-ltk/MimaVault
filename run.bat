@echo off
chcp 65001 >nul
title 密码大师 PasswordMaster
cd /d "%~dp0"
if not exist "target\PasswordMaster.jar" (
    echo [错误] 未找到 target\PasswordMaster.jar
    echo 请先在项目目录运行 mvn clean package 进行编译打包。
    pause
    exit /b 1
)
java -jar "target\PasswordMaster.jar"
if errorlevel 1 pause
