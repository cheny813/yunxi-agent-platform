---
name: shell-executor
description: Execute shell commands on Windows, Linux, or macOS. Use when running system commands, scripts, or console applications that require shell access.
---

# Shell Executor

Execute shell commands across platforms with proper working directory and environment.

## Quick Start

```json
{
  "type": "execute",
  "command": "echo",
  "args": ["Hello"],
  "cwd": "D:\\project"
}
```

## Supported Operations

| Operation | Description | Example |
|-----------|-------------|---------|
| execute | Run any shell command | `mvn test`, `npm run build` |
| spawn | Execute with args | `["git", ["push", "-u", "origin", "main"]]` |

## Windows Commands

```batch
:: Maven
mvn clean install
mvn test -Dtest=UserTest

:: NPM
npm install
npm run build
npm test

:: Git
git status
git pull origin main
git add .
git commit -m "fix: bug"
git push

:: System
dir
type filename
tasklist
ipconfig
```

## Linux/macOS Commands

```bash
# Maven
mvn clean install

# NPM/Node
npm install
npm test

# Git
git status
git pull

# System
ls -la
cat filename
ps aux
```

## Response Format

```json
{
  "status": "success",
  "stdout": "command output",
  "stderr": "errors",
  "exitCode": 0
}
```

## Error Handling

- Command not found: Returns error with "command not found" message
- Timeout: Default 60s timeout, returns timeout error
- Permission denied: Returns exit code 1 with permission message