---
name: devops-engineer
description: DevOps operations including Docker, Kubernetes, CI/CD pipelines, and infrastructure management. Use when building Docker images, managing containers, deploying to Kubernetes, configuring CI/CD, or monitoring services. Triggers on: "docker build", "kubectl", "ci/cd", "deploy", "kubernetes", "docker-compose", or any DevOps task.
---

# DevOps Engineer

DevOps workflow for containerization and deployment.

## Quick Start

```json
{
  "type": "execute",
  "command": "docker",
  "args": ["ps"]
}
```

## Docker Operations

### Build Image
```json
{
  "type": "execute",
  "command": "docker",
  "args": ["build", "-t", "myapp:latest", "."]
}
```

### Run Container
```json
{
  "type": "execute",
  "command": "docker",
  "args": ["run", "-d", "-p", "8080:8080", "myapp:latest"]
}
```

### List Containers
```json
{
  "type": "execute",
  "command": "docker",
  "args": ["ps", "-a"]
}
```

### View Logs
```json
{
  "type": "execute",
  "command": "docker",
  "args": ["logs", "container_id"]
}
```

## Docker Compose

### Start Services
```json
{
  "type": "execute",
  "command": "docker-compose",
  "args": ["up", "-d"]
}
```

### Stop Services
```json
{
  "type": "execute",
  "command": "docker-compose",
  "args": ["down"]
}
```

### View Logs
```json
{
  "type": "execute",
  "command": "docker-compose",
  "args": ["logs", "-f"]
}
```

## Kubernetes Operations

### Get Pods
```json
{
  "type": "execute",
  "command": "kubectl",
  "args": ["get", "pods"]
}
```

### Get Services
```json
{
  "type": "execute",
  "command": "kubectl",
  "args": ["get", "services"]
}
```

### Apply Config
```json
{
  "type": "execute",
  "command": "kubectl",
  "args": ["apply", "-f", "deployment.yaml"]
}
```

### View Pod Logs
```json
{
  "type": "execute",
  "command": "kubectl",
  "args": ["logs", "pod_name"]
}
```

## CI/CD Common Commands

### Build with Gradle
```json
{
  "type": "execute",
  "command": "gradle",
  "args": ["build"]
}
```

### Build with Maven
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["clean", "package", "-DskipTests"]
}
```

## Common Issues

- **Port already in use**: Check and stop conflicting containers
- **Image not found**: Build image first or pull from registry
- **Permission denied**: Use `sudo` on Linux or check Docker permissions