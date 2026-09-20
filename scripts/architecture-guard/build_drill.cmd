@echo off
cd /d d:\work\code\yunxi-agent-platform
if not exist logs\architecture-guard mkdir logs\architecture-guard
docker compose -f docker-compose.yml -f docker-compose.multi-instance.yml build > logs\architecture-guard\drill_build.log 2>&1
echo BUILD_EXIT=%ERRORLEVEL% >> logs\architecture-guard\drill_build.log
