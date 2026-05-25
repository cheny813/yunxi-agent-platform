---
name: qa-tester
description: Run tests, analyze coverage reports, and perform quality assurance for JavaScript/Java projects. Use when executing test suites, checking code coverage, running unit tests, integration tests, or analyzing test results. Supports Jest, Mocha, JUnit, and other test frameworks.
---

# QA Tester

Run tests and analyze code quality.

## Quick Start

```json
{
  "type": "execute",
  "command": "npm",
  "args": ["test"]
}
```

## Supported Operations

| Operation | Framework | Command |
|-----------|-----------|---------|
| Run Tests | Jest | `npm test` |
| Run Tests | Maven | `mvn test` |
| Coverage | Jest | `npm run test:coverage` |
| Coverage | Maven | `mvn test -Djacoco` |

## JavaScript Testing

### Run Jest Tests
```json
{
  "type": "execute",
  "command": "npm",
  "args": ["test", "--", "--watchAll=false"]
}
```

### Run with Coverage
```json
{
  "type": "execute",
  "command": "npm",
  "args": ["run", "test:coverage"]
}
```

### Run Specific Test File
```json
{
  "type": "execute",
  "command": "npm",
  "args": ["test", "--", "user.test.js"]
}
```

## Java Testing

### Run Maven Tests
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["test"]
}
```

### Run Specific Test Class
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["test", "-Dtest=UserServiceTest"]
}
```

### Run with Coverage
```json
{
  "type": "execute",
  "command": "mvn",
  "args": ["verify", "-Djacoco.skip=false"]
}
```

## Test Result Analysis

### View Test Reports
```json
{
  "type": "execute",
  "command": "type",
  "args": ["target\\surefire-reports\\TEST-*.xml"]
}
```

## Common Issues

- **Tests failing**: Check test output for assertion errors
- **Timeout**: Increase timeout with `--testTimeout=30000`
- **Coverage low**: Focus on critical business logic paths