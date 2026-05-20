---
name: git-operator
description: Perform Git operations including status, commit, push, pull, branch management, and repository inspection. Use when working with Git repositories - checking status, creating commits, pushing to remote, pulling updates, creating/switching branches, or viewing logs. Triggers on: "git status", "git commit", "git push", "git pull", "git branch", "git log", "git checkout", or any Git-related task.
---

# Git Operator

Execute Git commands for version control operations.

## Quick Start

```json
{
  "type": "git",
  "operation": "status",
  "args": []
}
```

## Supported Operations

| Operation | Description | Example |
|-----------|-------------|---------|
| status | Show working tree status | `git status` |
| pull | Fetch and integrate | `git pull origin main` |
| push | Update remote | `git push origin main` |
| add | Stage changes | `git add .` |
| commit | Record changes | `git commit -m "message"` |
| log | Show commit history | `git log --oneline` |
| branch | List branches | `git branch -a` |
| checkout | Switch branches | `git checkout main` |
| diff | Show changes | `git diff` |
| stash | Save changes | `git stash` |

## Common Workflows

### Check Status

```json
{
  "type": "git",
  "operation": "status",
  "args": []
}
```

### Commit Changes

```json
{
  "type": "git",
  "operation": "commit",
  "args": ["-m", "feat: add new feature"]
}
```

### Push to Remote

```json
{
  "type": "git",
  "operation": "push",
  "args": ["origin", "main"]
}
```

### Pull Updates

```json
{
  "type": "git",
  "operation": "pull",
  "args": ["origin", "main"]
}
```

## Response Format

```json
{
  "status": "success",
  "operation": "status",
  "stdout": "On branch main\nnothing to commit",
  "stderr": "",
  "exitCode": 0
}
```

## Error Handling

- Not a Git repository: Returns error with message
- Merge conflicts: Returns exit code != 0 with conflict details
- Remote not found: Returns error with "could not read remote"