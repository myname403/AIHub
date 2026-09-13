@echo off
rem ============================================================
rem AIHub 微服务一键启动脚本（Windows）
rem 用法：双击运行即可。会先清理旧进程，再依次启动三个服务。
rem 前提：docker compose up -d（中间件已在运行）
rem 端口：gateway=8080  platform=8081  ai=8082
rem 日志：本目录 logs\ 下各服务同名 .log
rem ============================================================
chcp 65001 >nul
setlocal
set JAVA=D:\Java_JDK\jdk-17.0.1\bin\java.exe
set BASE=%~dp0
set LOGDIR=%BASE%logs
if not exist "%LOGDIR%" mkdir "%LOGDIR%"

echo [1/3] 清理旧进程（8080/8081/8082）...
for %%P in (8080 8081 8082) do (
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%%P "') do (
        echo   结束进程 %%p（端口 %%P）
        taskkill /F /PID %%p >nul 2>&1
    )
)
timeout /t 2 /nobreak >nul

echo [2/3] 启动服务（最小化窗口，日志见 logs\）...
start "aihub-platform" /min "%JAVA%" -jar "%BASE%aihub-platform-service\target\aihub-platform-service.jar" > "%LOGDIR%\platform.log" 2>&1
start "aihub-ai"       /min "%JAVA%" -jar "%BASE%aihub-ai-service\target\aihub-ai-service.jar"           > "%LOGDIR%\ai.log" 2>&1
timeout /t 8 /nobreak >nul
start "aihub-gateway"  /min "%JAVA%" -jar "%BASE%aihub-gateway\target\aihub-gateway.jar"                 > "%LOGDIR%\gateway.log" 2>&1

echo [3/3] 等待启动完成（约 40 秒）...
timeout /t 40 /nobreak >nul

echo 验证健康状态：
curl -s -o nul -w "gateway  8080 -> %%{http_code}\n" http://127.0.0.1:8080/actuator/health
curl -s -o nul -w "platform 8081 -> %%{http_code}\n" http://127.0.0.1:8081/actuator/health
curl -s -o nul -w "ai       8082 -> %%{http_code}\n" http://127.0.0.1:8082/actuator/health
echo.
echo 完成！前端 http://localhost:5173   管理端 http://localhost:8849   Swagger http://localhost:8081/swagger-ui.html
pause
