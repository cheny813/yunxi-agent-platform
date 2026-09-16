@echo off
cd /d d:\work\code\yunxi-agent-platform
docker compose -f docker-compose.yml -f docker-compose.multi-instance.yml build > scripts\architecture-guard\drill_build.log 2>&1
echo BUILD_EXIT=%ERRORLEVEL% >> scripts\architecture-guard\drill_build.log
