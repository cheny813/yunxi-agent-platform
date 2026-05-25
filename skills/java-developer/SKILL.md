---
name: java-developer
description: Java development workflow for building, testing, and managing Java projects. Use when building Java applications with Maven/Gradle, running tests, analyzing code, or working with Spring Boot projects. Supports Maven commands (mvn), Gradle (gradle), and Java tools.
---

# Java Developer

Java development workflow for Maven/Gradle projects.

## Quick Start

```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["test"]
}
```

## Supported Operations

| Operation | Command | Description |
|-----------|---------|-------------|
| Build | `mvn clean package` | Compile and package |
| Test | `mvn test` | Run unit tests |
| Run | `mvn spring-boot:run` | Run Spring Boot app |
| Clean | `mvn clean` | Clean target directory |
| Compile | `mvn compile` | Compile source |

## Maven Commands

### Build Project
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["clean", "package", "-DskipTests"]
}
```

### Run Tests
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["test", "-Dtest=UserServiceTest"]
}
```

### Run Spring Boot
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["spring-boot:run"]
}
```

### Clean Build
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["clean", "install"]
}
```

## Common Issues

- **Build failed**: Check pom.xml dependencies
- **Test failed**: Run with `-DskipTests=false` to see details
- **Out of memory**: Set MAVEN_OPTS `-Xmx512m`

## Project Structure

```
project/
├── src/
│   ├── main/java/      # Source code
│   ├── test/java/      # Test code
│   └── resources/      # Config files
├── pom.xml             # Maven config
└── target/             # Build output
```