@echo off
setlocal

if "%APP_HOME%"=="" (
    echo ERROR: APP_HOME environment variable is not set.
    pause
    exit /b 1
)

"%APP_HOME%\bin\java.exe" -jar container-kitty-launcher-1.0-SNAPSHOT.jar
endlocal
