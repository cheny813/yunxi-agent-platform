# yunxiClaw - AI 电脑助手

让 AI 能够在您的电脑上执行操作。

## 概述

yunxiClaw 是一款桌面客户端应用，连接 AI 助手后，可以帮助您：
- 在电脑上执行系统命令
- 管理 Git 仓库（status、pull、push、commit）
- 运行测试和构建项目
- 浏览和编辑文件

## 功能特性

### 🔗 连接服务器
- 配置 WebSocket 服务器地址
- 实时与 AI 助手通信

### ⚡ 快捷操作
| 功能 | 说明 |
|------|------|
| 📂 浏览文件 | 选择并读取文件 |
| 📊 Git 状态 | 查看仓库状态 |
| ⬇️ Git Pull | 拉取最新代码 |
| 🧪 运行测试 | mvn test |
| 📦 Maven 构建 | 编译打包 |
| 📥 npm install | 安装依赖 |
| 🧹 Maven 清理 | clean |
| 💻 系统信息 | 查看客户端信息 |

### ⌨️ 自定义命令
- 输入任意命令执行
- 支持所有系统命令

### 📁 工作目录
- 切换当前工作目录
- 默认使用系统目录

## 快速开始

### 环境要求
- Node.js 18+
- Electron 28+

### 安装

```bash
cd agent-desktop
npm install
```

### 启动

```bash
npm start
```

### 两种连接模式

#### 模式 1：本地服务器模式
- 桌面客户端启动本地 WebSocket 服务器 (`ws://localhost:9876`)
- AI 直接连接本地服务器
- 适用于：本地 AI 测试、单用户场景

#### 模式 2：中继服务器模式（推荐）
- 桌面客户端连接到后端中继服务器 (`ws://localhost:40001/ws/desktop`)
- AI 通过后端 API 转发命令到桌面客户端
- 适用于：远程 AI 控制、多客户端管理

详见 [RELAY_MODE.md](RELAY_MODE.md)

### 开发模式

```bash
npm run dev
```

### 构建发布

```bash
# Windows
npm run build

# macOS
npm run build:mac

# Linux
npm run build:linux
```

## 技术架构

```
┌─────────────────────────────────────┐
│         AI 大模型                   │
│    (ChatGPT / Claude 等)           │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│       后端服务 (yunxi-agent)        │
│    • 技能引擎                      │
│    • 命令转发                      │
└─────────────────┬───────────────────┘
                  │ WebSocket
                  ▼
┌─────────────────────────────────────┐
│      yunxiClaw 桌面客户端            │
├─────────────────────────────────────┤
│  主进程                             │
│  • 命令执行 (spawn)                │
│  • 文件操作 (fs)                   │
│  • Git 操作                        │
│  • IPC 通信                        │
├─────────────────────────────────────┤
│  渲染进程                           │
│  • UI 界面                         │
│  • 用户交互                        │
└─────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│        用户本地机器                │
│  • 文件系统                        │
│  • 命令行工具                      │
│  • Git/Maven/Node 等              │
└─────────────────────────────────────┘
```

## API 参考

### 主进程暴露的 API

```javascript
// 系统信息
agentAPI.getSystemInfo()
agentAPI.getCwd()
agentAPI.setCwd(path)

// 文件操作
agentAPI.listDir(path)        // 列出目录
agentAPI.readFile(path)      // 读取文件
agentAPI.writeFile(path, content)  // 写入文件
agentAPI.selectFile()        // 选择文件
agentAPI.selectFolder()      // 选择目录

// 命令执行
agentAPI.executeCommand(command, args)  // 执行命令

// Git 操作
agentAPI.gitOperation(operation, args)  // Git 操作

// 工具
agentAPI.openExternal(url)
agentAPI.showMessage(options)
```

## 安全说明

- ⚠️ 执行命令前请确认命令安全
- 建议在可信环境下使用
- 敏感操作需要用户确认

## 快捷键

| 快捷键 | 功能 |
|--------|------|
| Ctrl+Shift+C | 显示窗口 |
| Ctrl+R | 刷新界面 |
| F12 | 开发者工具 |

## 常见问题

### Q: 连接服务器失败？
A: 请检查服务器地址是否正确，确保服务器已启动。

### Q: 命令执行超时？
A: 部分命令（如 mvn test）可能需要较长时间，系统默认 60 秒超时。

### Q: 如何退出应用？
A: 点击托盘图标 → 退出，或使用菜单 → 文件 → 退出。

## Q: 网络连接 GitHub 下载 Electron 超时？
方案 1：设置 npm 镜像
npm config set registry https://registry.npmmirror.com
npm install

方案 2：使用 electron-mirror
npm config set electron_mirror https://npmmirror.com/mirrors/electron/
npm install

方案 3：手动下载 如果网络有问题，可以从
https://github.com/electron/electron/releases 手动下载对应版本的 electron zip，然后解压到 node_modules/electron/dist/ 目录。

## 更新日志

### v1.0.0 (2024-04-11)
- ✅ 初始版本
- ✅ 连接服务器
- ✅ 命令执行
- ✅ Git 操作
- ✅ 文件浏览
- ✅ 快捷操作

## 许可证

MIT License

## 作者

YGC (YuanGuang Cloud)