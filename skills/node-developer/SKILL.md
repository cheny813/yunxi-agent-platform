---
name: node-developer
description: Node.js and JavaScript development workflow for building, testing, and managing npm projects. Use when working with Node.js applications, running npm/yarn commands, building frontend projects, or managing JavaScript dependencies. Triggers on: "npm install", "npm run build", "yarn install", "node script", "npm test", or any Node.js development task.
---

# Node Developer

Node.js/JavaScript development workflow.

## Quick Start

```json
{
  "type": "execute",
  "command": "npm",
  "args": ["install"]
}
```

## Supported Operations

| Operation | Command | Description |
|-----------|---------|-------------|
| Install | `npm install` | Install dependencies |
| Build | `npm run build` | Build project |
| Test | `npm test` | Run tests |
| Dev | `npm run dev` | Start dev server |
| Start | `npm start` | Start application |

## NPM Commands

### Install Dependencies
```json
{
  "type": "execute",
  "command": "npm",
  "args": ["install"]
}
```

### Install Package
```json
{
  "type": "execute",
  "command": "npm",
  "args": ["install", "lodash"]
}
```

### Run Build
```json
{
  "type": "execute",
  "command": "npm",
  "args": ["run", "build"]
}
```

### Run Tests
```json
{
  "type": "execute",
  "command": "npm",
  "args": ["test"]
}
```

## Yarn Support

Replace `npm` with `yarn` for yarn commands:
```json
{
  "type": "execute",
  "command": "yarn",
  "args": ["install"]
}
```

## Common Issues

- **EACCES permission**: Use nvm or fix npm prefix
- **Module not found**: Run `npm install`
- **Version mismatch**: Delete node_modules and reinstall