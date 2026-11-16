# MyDog

跨平台（Windows / macOS / Linux）MySQL 桌面管理工具，核心特色是**数据库间同步**（整库、表/视图/触发器/函数/存储过程，可选只同步结构、只同步数据、或结构+数据，支持对象多选与按名搜索）。

- **进度看板：[PROGRESS.md](PROGRESS.md)** ← 当前在做什么、验收标准、命令速查都在这里
- 技术栈：Java 25 LTS + JavaFX 27 + Maven（Wrapper），jpackage 捆绑运行时分发
- 界面：中英双语，运行时可切换

## 快速开始

```bash
cd docker && docker compose up -d --wait     # 起两个测试库（源 3307 / 目标 3308）
./mvnw verify                                # 单测 + 集成测试
./mvnw javafx:run                            # 启动应用
```

## 状态

开发中（M1 骨架收尾）。功能清单与里程碑见 [PROGRESS.md](PROGRESS.md)。

## License

Apache 2.0
