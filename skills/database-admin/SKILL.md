---
name: database-admin
description: Database operations including SQL query execution, backup/restore, schema management, and performance optimization. Use when running SQL queries, managing database schemas, performing backups, or optimizing query performance. Supports MySQL, PostgreSQL, and other databases. Triggers on: "sql query", "database backup", "create table", "run sql", "mysql", or any database task.
---

# Database Admin

Database operations and management.

## Quick Start

```json
{
  "type": "execute",
  "command": "mysql",
  "args": ["-u", "root", "-p", "-e", "SHOW DATABASES;"]
}
```

## MySQL Operations

### Connect to Database
```bash
mysql -u username -p database_name
```

### Show Tables
```sql
SHOW TABLES;
```

### Describe Table
```sql
DESCRIBE table_name;
```

### Run Query
```json
{
  "type": "execute",
  "command": "mysql",
  "args": ["-u", "root", "-p", "mydb", "-e", "SELECT * FROM users LIMIT 10;"]
}
```

## Backup and Restore

### Export Database
```json
{
  "type": "execute",
  "command": "mysqldump",
  "args": ["-u", "root", "-p", "mydb", ">", "backup.sql"]
}
```

### Import Database
```json
{
  "type": "execute",
  "command": "mysql",
  "args": ["-u", "root", "-p", "mydb", "<", "backup.sql"]
}
```

## Schema Operations

### Create Table
```sql
CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    email VARCHAR(100)
);
```

### Add Column
```sql
ALTER TABLE users ADD COLUMN created_at TIMESTAMP;
```

### Create Index
```sql
CREATE INDEX idx_email ON users(email);
```

## Performance

### Show Process List
```sql
SHOW PROCESSLIST;
```

### Explain Query
```sql
EXPLAIN SELECT * FROM users WHERE email = 'test@example.com';
```

### Show Indexes
```sql
SHOW INDEX FROM users;
```

## Common Issues

- **Connection refused**: Check MySQL service is running
- **Access denied**: Verify username and password
- **Table not found**: Check database is selected