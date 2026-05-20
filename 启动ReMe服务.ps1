<#
.SYNOPSIS
    Start ReMe service with OLLAMA local models + Milvus vector store
.DESCRIPTION
    - Milvus connection config is managed in ReMePython/config.yaml
    - LLM/Embedding model config is managed in this script
    - Startup logic is handled by ReMePython/start_reme.py
.NOTES
    Run in PowerShell: .\启动ReMe服务.ps1
#>

# --- Configuration (modify as needed) ---
$PROJECT_DIR = "D:\work\code\agentscope-ReMe"
$REME_PYTHON_DIR = "D:\work\code\yunxi-agent-platform\ReMePython"
$PORT = 8002
$LLM_MODEL = "ollama/qwen2.5:7b"
$EMBEDDING_MODEL = "ollama/bge-m3"
$OLLAMA_BASE_URL = "http://localhost:11434"

# --- Display configuration ---
Write-Host "=== ReMe Service Configuration ==="
Write-Host "Project Dir    : $PROJECT_DIR"
Write-Host "Extension Dir  : $REME_PYTHON_DIR"
Write-Host "Port           : $PORT"
Write-Host "LLM Model      : $LLM_MODEL"
Write-Host "Embedding Model: $EMBEDDING_MODEL"
Write-Host "OLLAMA URL     : $OLLAMA_BASE_URL"
Write-Host "Milvus Config  : $REME_PYTHON_DIR\config.yaml"
Write-Host ""

# --- Check Python ---
Write-Host "Checking Python..."
$pythonVersion = python --version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Python not found. Please install Python and add it to PATH."
    pause
    exit 1
}

# --- Check project directory ---
Write-Host "Checking project directory..."
if (-not (Test-Path $PROJECT_DIR)) {
    Write-Host "[ERROR] Project directory not found: $PROJECT_DIR"
    pause
    exit 1
}

Set-Location $PROJECT_DIR
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Cannot change to project directory"
    pause
    exit 1
}

# --- Check ReMePython extension directory ---
Write-Host "Checking ReMePython extension directory..."
if (-not (Test-Path $REME_PYTHON_DIR)) {
    Write-Host "[WARN] ReMePython extension directory not found: $REME_PYTHON_DIR"
    Write-Host "[WARN] Will use default memory backend (without Milvus)"
} else {
    Write-Host "ReMePython extension directory found."
}

# --- Install dependencies ---
Write-Host "Installing dependencies (may take a while on first run)..."
$pipResult = pip install -e ".[light,litellm]" 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Failed to install ReMe dependencies"
    Write-Host "Please run manually: pip install -e .[light,litellm]"
    pause
    exit 1
}

# Install pymilvus for Milvus vector store support
Write-Host "Installing pymilvus..."
$milvusResult = pip install pymilvus 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[WARN] Failed to install pymilvus. Milvus vector store will not be available."
    Write-Host "Please run manually: pip install pymilvus"
}

Write-Host "Dependencies installed."
Write-Host ""

# --- Set environment variables ---
$env:OPENAI_API_KEY = "ollama"
$env:PYTHONIOENCODING = "utf-8"
$env:FLOW_EMBEDDING_API_KEY = "ollama"

# Add ReMePython's parent directory to PYTHONPATH (so "import ReMePython" works)
$env:PYTHONPATH = "$env:PYTHONPATH;D:\work\code\yunxi-agent-platform"

# --- Start ReMe service ---
Write-Host "Starting ReMe service (OLLAMA + Milvus)..."
Write-Host ""

python "$REME_PYTHON_DIR\start_reme.py" `
    "http.port=$PORT" `
    "llm.default.model_name=$LLM_MODEL" `
    "embedding_model.default.model_name=$EMBEDDING_MODEL" `
    "embedding_model.default.base_url=$OLLAMA_BASE_URL/v1"

Write-Host ""
Write-Host "ReMe service has stopped."
pause