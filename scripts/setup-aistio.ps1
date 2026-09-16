# setup-aistio.ps1
# One-shot launcher for the AgentScope-Service (aistio) control plane.
# It auto-applies the upstream Dockerfile.control domestic-network patches,
# rewrites the package-lock.json registry, builds the three Java plane jars
# via maven, then starts the aistio profile with docker compose.
# All upstream edits are idempotent and safe to re-run.

param(
    # AgentScope-Java repo root. Empty -> auto-detect a sibling dir named
    # agentscope-java or agentscope-java-2.0GA.
    [string] $AistioRepoRoot = '',
    [string] $AistioVersion  = '2.0.3-SNAPSHOT',
    [switch] $GitPull
)

$ErrorActionPreference = 'Stop'
$nl = [Environment]::NewLine
$yunxiRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$siblingRoot = (Resolve-Path (Join-Path $yunxiRoot '..')).Path

if ([string]::IsNullOrWhiteSpace($AistioRepoRoot)) {
    foreach ($name in @('agentscope-java', 'agentscope-java-2.0GA')) {
        $candidate = Join-Path $siblingRoot $name
        if (Test-Path $candidate) { $AistioRepoRoot = $candidate; break }
    }
}
if ([string]::IsNullOrWhiteSpace($AistioRepoRoot) -or -not (Test-Path $AistioRepoRoot)) {
    throw "Cannot find the AgentScope-Java repo next to this project (looked for agentscope-java / agentscope-java-2.0GA under $siblingRoot). Clone it there or pass -AistioRepoRoot <path>."
}

$repoRoot = (Resolve-Path $AistioRepoRoot).Path
$svcDir = Join-Path $repoRoot 'agentscope-service'

if (-not (Test-Path $svcDir)) {
    throw "Cannot find agentscope-service dir: $svcDir. Pass -AistioRepoRoot to the AgentScope-Java repo root."
}

Write-Host "==> repo root: $repoRoot" -ForegroundColor Cyan
Write-Host "==> service dir: $svcDir" -ForegroundColor Cyan
Write-Host "==> aistio version: $AistioVersion" -ForegroundColor Cyan

# Optional: pull upstream latest
if ($GitPull) {
    Write-Host '==> git pull origin main ...' -ForegroundColor Cyan
    Push-Location $repoRoot
    try { git pull origin main } finally { Pop-Location }
}

# Patch 1: upstream Dockerfile.control domestic-network fixes (idempotent)
# Issue A: final base image gcr.io/distroless/static:nonroot is unreachable in CN
# Issue B: Go modules via proxy.golang.org is blocked in CN
$dockerfile = Join-Path (Join-Path $svcDir 'docker') 'Dockerfile.control'
if (-not (Test-Path $dockerfile)) { throw "Cannot find Dockerfile.control: $dockerfile" }
$df = Get-Content $dockerfile -Raw

if ($df -notmatch 'RUNTIME_IMAGE=debian:bookworm-slim') {
    $argNode = 'ARG RUNTIME_IMAGE=debian:bookworm-slim' + $nl + 'FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS console'
    $df = $df.Replace('FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS console', $argNode)
    $runCa = 'FROM ${RUNTIME_IMAGE}' + $nl + 'RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*'
    $df = $df.Replace('FROM gcr.io/distroless/static:nonroot', $runCa)
    Set-Content -Path $dockerfile -Value $df -NoNewline
    Write-Host '[patch] Dockerfile.control: applied RUNTIME_IMAGE + ca-certificates (gcr.io -> debian:bookworm-slim)' -ForegroundColor Green
} else {
    Write-Host '[skip] Dockerfile.control already has RUNTIME_IMAGE patch' -ForegroundColor DarkGray
}

if ($df -notmatch 'GOPROXY=https://goproxy.cn') {
    $envGoproxy = 'FROM --platform=$BUILDPLATFORM golang:1.26 AS build' + $nl + '# proxy.golang.org is blocked in CN; use goproxy.cn' + $nl + 'ENV GOPROXY=https://goproxy.cn,direct GONOSUMCHECK=1 GOFLAGS=-mod=mod'
    $df = $df.Replace('FROM --platform=$BUILDPLATFORM golang:1.26 AS build', $envGoproxy)
    Set-Content -Path $dockerfile -Value $df -NoNewline
    Write-Host '[patch] Dockerfile.control: applied GOPROXY=goproxy.cn' -ForegroundColor Green
} else {
    Write-Host '[skip] Dockerfile.control already has GOPROXY patch' -ForegroundColor DarkGray
}

# Patch 2: package-lock.json pinned to Ali internal registry (idempotent)
$lock = Join-Path (Join-Path $svcDir 'frontend') 'package-lock.json'
if (Test-Path $lock) {
    $lockContent = Get-Content $lock -Raw
    $count = ([regex]::Matches($lockContent, 'registry.anpm.alibaba-inc.com')).Count
    if ($count -gt 0) {
        $lockContent = $lockContent.Replace('registry.anpm.alibaba-inc.com', 'registry.npmmirror.com')
        Set-Content -Path $lock -Value $lockContent -NoNewline
        Write-Host "[patch] package-lock.json: replaced $count internal-registry entries -> registry.npmmirror.com" -ForegroundColor Green
    } else {
        Write-Host '[skip] package-lock.json has no internal registry, skip' -ForegroundColor DarkGray
    }
} else {
    Write-Host '[warn] package-lock.json not found, skip registry rewrite (Dockerfile npm ci may fail on internal registry)' -ForegroundColor Yellow
}

# Build the three Java plane jars (submodules must be listed explicitly)
Write-Host '==> maven build of aistio plane jars (first run is slow, needs network)...' -ForegroundColor Cyan
Push-Location $repoRoot
try {
    mvn -pl agentscope-service/service-dataplane,agentscope-service/service-gateway,agentscope-service/service-scheduler -am install -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "maven build failed (exit=$LASTEXITCODE)" }
} finally { Pop-Location }
Write-Host '[ok] maven build done' -ForegroundColor Green

# Start aistio profile (always use PowerShell to set env vars)
Write-Host '==> docker compose up aistio profile ...' -ForegroundColor Cyan
$env:AISTIO_SRC = $svcDir
$env:AISTIO_ROOT = $repoRoot
$env:AISTIO_VERSION = $AistioVersion
Push-Location $yunxiRoot
try {
    docker compose --profile aistio up -d
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed (exit=$LASTEXITCODE)" }
} finally { Pop-Location }

Write-Host ''
Write-Host '==> aistio control plane started. Verify all containers are Up (healthy):' -ForegroundColor Green
Write-Host '    docker compose --profile aistio ps' -ForegroundColor White
Write-Host '==> Web console: http://localhost:8081 (served by Go aistiod control plane)' -ForegroundColor White
Write-Host '==> To let yunxi join, set yunxi.aistio.enabled=true in application.yml (see README).' -ForegroundColor White
