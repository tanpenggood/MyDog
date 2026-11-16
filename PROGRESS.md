# MyDog 开发进度看板

> 最后更新：2026-11-17 01:20 · 分支 `main` · 状态 **M1 收尾验收中**

整体进度：`M0 ████████████ 100% · M1 █████████░░░ 85% · M2 ░░░░░░░░░░░░ 0% · M3 ░░░░░░░░░░░░ 0% · M4 ░░░░░░░░░░░░ 0% · M5 ░░░░░░░░░░░░ 0%`

完整设计计划（架构/选型理由/同步引擎设计）见 `~/.claude/plans/mysql-windows-macos-linux-sql-distributed-engelbart.md`。

---

## 里程碑总览

| 里程碑 | 内容 | 状态 | 验收标准 |
|---|---|---|---|
| **M0 环境** | JDK 25 LTS + Docker + compose 双 MySQL | ✅ **完成** | `java -version`=25.0.2；3307/3308 healthy |
| **M1 骨架** | pom / 包结构 / i18n 中英切换 / 连接管理（CRUD+测试+加密） | 🟡 **代码完成，验收中** | 应用可跑；连 docker 库测试成功；密码密文；语言全量切换 |
| **M2 浏览编辑** | 对象树 / SQL 编辑器（RichTextFX）/ 数据查看器 / 结构查看器 | ⬜ 未开始 | 对源库跑通浏览、执行 SQL、看数据与 DDL |
| **M3 同步引擎（无 UI）** | metadata 解析 + DdlNormalizer + Comparator + DataDiffEngine + SyncPlan | ⬜ 未开始 | `./mvnw verify` 绿；同步后两侧 SHOW CREATE/CHECKSUM/行数一致 |
| **M4 同步向导 UI** | 五步向导：源目标 → 范围 → 对比 → 预览勾选/导出 → 执行/取消/报告 | ⬜ 未开始 | 结构/数据/两者 + DROP 选项 + 取消 + 错误注入全过 |
| **M5 打包发布** | 图标 / jpackage dmg / GitHub Actions(win+linux) / README | ⬜ 未开始 | 三平台产物可安装启动，CI 绿 |

M3 只依赖 M1 的连接层，可与 M2 并行推进。

---

## M1 明细

### 已完成 ✅

- [x] Maven Wrapper 引导（`.mvn/wrapper` + `mvnw`，Maven 3.9.16）
- [x] `pom.xml`：Java 25 基线 + JavaFX 27.0.1 + RichTextFX 0.11.7 + AtlantaFX 3.0.0 + Connector/J 26.10.0 + Gson 2.15.0 + JUnit 5.14.4
- [x] 包结构：`MyDogApp` / `core.AppContext` / `connection.*` / `i18n.I18n` / `ui.MainWindow` / `ui.dialog.ConnectionDialog`
- [x] i18n：`ResourceBundle` 双语 + 运行时整体重建 Scene（菜单 语言 → 中文/English）
- [x] 连接管理：新建 / 编辑 / 删除 / 测试连接，`connections.json` 原子写 + 0600
- [x] 密码加密 `PasswordCipher`：AES-GCM，`aes:` 前缀 base64，密钥 `~/.mydog/.secret.key`（随机 IV，篡改返回空串不抛异常）
- [x] `MySqlConnectionFactory`：集中驱动参数（UTF-8 / 强制 UTC 时区 / `allowPublicKeyRetrieval` / 批量重写）
- [x] **测试 15/15 绿**：9 单测（surefire）+ 6 个真库集成测试（failsafe `@Tag("docker")`）
  - `DockerMySqlConnectionIT` ×5：MySQL 8 免 TLS 认证、两侧 UTC 会话时区、utf8mb4 中文+emoji 往返、defaultSchema 绑定、fixture 差异校验
  - `DockerConnectionPersistenceIT` ×1：输入密码 → 加密 → 落盘 → 模拟重启读回 → 解密 → 真连；并断言错密码必须 `Access denied`
  - `ConnectionPersistenceTest` ×2 / `PasswordCipherTest` ×5 / `I18nBundleTest` ×2
- [x] GUI 实测：应用启动正常、界面渲染正常、中文界面生效、`shop-src` 连接测试返回「连接成功。服务器版本：8.0.42」

### 待验收 ⬜

- [ ] 建 `shop-dst`（3308）并测试成功
- [ ] 工具栏「测试连接」→ 状态栏显示结果（本轮刚改的设计，待你点一下）
- [ ] 完全退出后检查 `~/.mydog/connections.json` 为 `aes:` 密文且能解回明文
- [ ] 语言来回切换确认无遗漏文案
- [ ] `git commit` M1 骨架

---

## 本轮修掉的问题（含根因，避免复犯）

| # | 问题 | 根因 | 修法 |
|---|---|---|---|
| 1 | **IDEA 反复崩溃**（AMD 驱动 assert） | `AMDMTLBronzeDriver 7.0.24` 在 macOS 15.7.9 上不稳定；IDEA 的 Java2D 默认走 Metal，MyDog 窗口启动带来的 GPU 负载变化触发其命令缓冲崩溃（崩溃在 IDEA 进程内，MyDog 本身正常） | ① `idea.vmoptions` 加 `-Dsun.java2d.metal=false`（**已验证生效**：`jinfo` 显示 `sun.java2d.metal=false`，IDEA 稳定运行）② MyDog 渲染管线参数化，自动化跑用 `sw` 零 GPU 调用 |
| 2 | **测试连接报 Access denied** | `ConnectionDialog` 把密码写死成空串 `open(p, "")`，输入框密码从未交给驱动 | 新增 `plainPassword()`：优先取输入框明文，否则解密已存密文；两条路径（弹窗内 / 工具栏）都走它 |
| 3 | **集成测试静默不跑** | failsafe 的 `testSourceDirectory` 只负责"选类"，**不参与编译**，`src/it/java` 从未被 javac 编出来 | 用 build-helper 把 `src/it/java` 挂为 test 源根；靠 `docker` 标签分组隔离 surefire/failsafe |
| 4 | 弹窗文字截成 `连接成功...` | `Dialog` 不可调宽且按内容算尺寸，wrap 标签塌成窄条 | 消息标签给显式宽度（440/400） |
| 5 | 工具栏测试"点了没反应" | 非模态小窗在 macOS 上可能被主窗压住；按钮文案误用「取消」 | **去掉小窗**，结果写主窗口状态栏（绿/红），并加 `LOG.info` 便于追查 |

---

## 环境与命令速查

```bash
# 集成测试沙箱（源 3307 / 目标 3308，root/root123，库名 shop）
cd docker && docker compose up -d --wait

# 全量测试（单测 + 依赖 docker 的集成测试；docker 没起时 IT 会自动跳过而非失败）
JAVA_HOME=/Users/ios/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home ./mvnw verify

# 启动应用（日常手动跑：es2 = OpenGL）
JAVA_HOME=/Users/ios/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home ./mvnw javafx:run

# 自动化冒烟（零 GPU，规避本机 AMD 驱动 bug）
JAVA_HOME=/Users/ios/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home ./mvnw javafx:run -Dmydog.prism.order=sw
```

- 用户配置目录：`~/.mydog/`（`connections.json` / `settings.json` / `.secret.key`，均 0600）
- 技术基线：**Java 25 LTS**（补丁至 ~2030）+ JavaFX 27.0.1 + jpackage 捆绑运行时；升级红线：未来整体升 JDK 29 LTS + JavaFX 29，不单点升级

---

## 下一步：M2 浏览编辑（待 M1 验收后开工）

1. 对象树（库/表/视图/触发器/函数/存储过程 懒加载 + 右键菜单）
2. SQL 编辑器（多 Tab、语法高亮、执行全部/选中、DELIMITER 感知切分、多结果集 + 消息面板）
3. 数据查看器（分页 / 排序 / WHERE 过滤 / 单元格编辑）
4. 结构查看器（DDL 展示与复制）
