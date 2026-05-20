@echo off
chcp 65001 >nul

REM Simple batch file to start agentscope-ReMe
REM Uses ASCII characters only to avoid encoding issues

echo Starting agentscope-ReMe...
echo.

echo Verify Python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Python not found or not in PATH
    pause
    exit /b 1
)

echo Check project directory
if not exist "D:\work\code\agentscope-ReMe" (
    echo ERROR: Project directory not found
    echo Path: D:\work\code\agentscope-ReMe
    pause
    exit /b 1
)

echo Go to project directory
cd /d "D:\work\code\agentscope-ReMe"
if %errorlevel% neq 0 (
    echo ERROR: Cannot change to project directory
    pause
    exit /b 1
)

echo Install dependencies (this may take a while)
pip install -e .[light,litellm]
if %errorlevel% neq 0 (
    echo ERROR: Failed to install dependencies
    echo Please try manual installation: pip install -e .[light,litellm]
    pause
    exit /b 1
)

echo Dependencies installed successfully
echo.

echo Starting ReMe with OLLAMA mode...
echo Model: qwen2.5:7b
echo Port: 8002
echo API Key: Not required (local OLLAMA)
echo.

REM Start the application
python -m reme_ai.main http.port=8002 llm.default.model_name=ollama/qwen2.5:7b embedding_model.default.model_name=text-embedding-v4 vector_store.default.backend=memory --custom-llm-provider ollama --llm-api-key ollama

pause