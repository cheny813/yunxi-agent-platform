---
name: file-manager
description: Read, write, list, and manage files on the local machine. Use when reading source code, editing configuration files, creating new files, listing directory contents, or any file system operations. Triggers on: "read file", "write file", "list directory", "create file", "edit file", "browse files", or any task requiring file system access.
---

# File Manager

Perform file system operations on the user's machine.

## Quick Start

```json
{
  "type": "read-file",
  "path": "D:\\project\\src\\main.java"
}
```

## Supported Operations

| Operation | Description | Example |
|-----------|-------------|---------|
| list-dir | List directory contents | `D:\project` |
| read-file | Read file content | `D:\project\pom.xml` |
| write-file | Write to file | `D:\project\test.java` |

## list-dir

List all files and directories in a path:

```json
{
  "type": "list-dir",
  "path": "D:\\project\\src"
}
```

Returns:
```json
{
  "status": "success",
  "path": "D:\\project\\src",
  "files": [{"name": "Main.java", "size": 1024}],
  "dirs": [{"name": "utils"}]
}
```

## read-file

Read file content (max 5MB):

```json
{
  "type": "read-file",
  "path": "D:\\project\\pom.xml"
}
```

Returns:
```json
{
  "status": "success",
  "path": "D:\\project\\pom.xml",
  "name": "pom.xml",
  "size": 2048,
  "content": "<?xml version=\"1.0\"...>"
}
```

## write-file

Create or overwrite file:

```json
{
  "type": "write-file",
  "path": "D:\\project\\test.java",
  "content": "public class Test { }"
}
```

Returns:
```json
{
  "status": "success",
  "path": "D:\\project\\test.java"
}
```

## Path Handling

- Windows: `D:\project\src` or `D:/project/src`
- Linux/macOS: `/home/user/project/src`
- Relative paths resolve from working directory