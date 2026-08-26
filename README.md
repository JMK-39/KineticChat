# KineticChat

[简体中文](#简体中文) | [English](#english)

## 简体中文

### 模组定位

**KineticChat** 是 Kinetic 系列的现代化聊天增强模块，目标是解除原版聊天长度和历史记录限制，并为长时间服务器游玩提供更实用的聊天回溯、复制和防刷屏能力。

### 主要功能

- **超长聊天与指令**：突破原版 256 字符限制，默认可将聊天/指令最大长度提高到 16384 字符。
- **大容量聊天历史**：客户端可保存远高于原版的历史行数，默认上限 10000 行。
- **服务器历史持久化**：本机/集成服务器侧为玩家保存有大小限制的聊天历史，并优先淘汰最旧记录。
- **可拖拽滚动条**：聊天界面右侧增加可视化滚动条，便于快速翻阅大量历史。
- **高精度时间戳**：可为每条消息显示 `[HH:mm:ss.SSS]` 时间。
- **重复消息合并**：连续相同消息自动叠楼并显示次数，减少系统消息或重复文本刷屏。
- **聊天头像**：玩家消息前可以显示 8×8 皮肤头像，并根据 UUID 恢复历史头像信息。
- **聊天复制画布**：聊天界面提供复制入口，可打开独立画布查看并复制历史文本。
- **签名剥离**：可在服务器广播阶段移除聊天签名，同时保留服务器侧聊天事件处理流程。
- **客户端与服务端配置分离**：服务端规则由服务器保存，纯客户端显示偏好保留本地控制。

### 配置文件

```text
config/kineticcore/chat.toml
```

常见设置：

- `max_chat_length`
- `max_chat_history_lines`
- `enable_draggable_scrollbar`
- `enable_timestamp`
- `enable_compact_chat`
- `strip_chat_signatures`
- `enable_chat_heads`

### 运行环境

- Minecraft 1.20.1
- Minecraft Forge 47.x
- Java 17
- KineticCore：必须

## English

### Overview

**KineticChat** is a modern chat enhancement module for the Kinetic family. It removes restrictive vanilla limits and adds practical tools for long-running multiplayer sessions.

### Key Features

- Extended chat and command length, configurable up to a much larger limit than vanilla.
- Large client chat history.
- Bounded server-side history persistence.
- Draggable chat scrollbar.
- Millisecond-precision timestamps.
- Repeated-message compaction with counters.
- Player skin heads in chat history.
- Dedicated chat copy canvas.
- Optional late-stage chat signature stripping.
- Separation between server rules and local client display preferences.

### Configuration

```text
config/kineticcore/chat.toml
```

### Requirements

- Minecraft 1.20.1
- Minecraft Forge 47.x
- Java 17
- KineticCore: required


## 开源协议与版权 (License)

Copyright (C) 2024-2026 XYAT.

本项目基于 **GNU Lesser General Public License v3.0 (LGPLv3)** 协议开源。

This project is open-sourced under the **GNU Lesser General Public License v3.0 (LGPLv3)**.
