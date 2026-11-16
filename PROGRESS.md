# MyDog 开发进度看板

> 最后更新：2026-11-17 02:40 · 分支 `main` · 状态 **M3 完成，M4 同步向导开发中**

整体进度：`M0 ████████████ 100% · M1 ████████████ 100% · M2 ████████████ 100% · M3 ████████████ 100% · M4 ███░░░░░░░░░ 25% · M5 ░░░░░░░░░░░░ 0%`

完整设计计划（架构/选型理由/同步引擎设计）见 `~/.claude/plans/mysql-windows-macos-linux-sql-distributed-engelbart.md`。

---

## 里程碑总览

| 里程碑 | 内容 | 状态 | 验收标准 |
|---|---|---|---|
| **M0 环境** | JDK 25 LTS + Docker + compose 双 MySQL | ✅ **完成** | `java -version`=25.0.2；3307/3308 healthy |
| **M1 骨架** | pom / 包结构 / i18n 中英切换 / 连接管理（CRUD+测试+加密） | ✅ **完成**（已 commit `5290fae`） | 应用可跑；连 docker 库测试成功；密码密文；语言全量切换 |
| **M2 浏览编辑** | 对象树 / SQL 编辑器（RichTextFX）/ 数据查看器 / 结构查看器 | ✅ **完成** | 对源库跑通浏览、执行 SQL、看数据与 DDL |
| **M3 同步引擎（无 UI）** | metadata 解析 + DdlNormalizer + Comparator + DataDiffEngine + SyncPlan | ✅ **完成** | `./mvnw verify` 绿；同步后两侧 SHOW CREATE/CHECKSUM/行数一致（9 个端到端 IT 证明） |
| **M4 同步向导 UI** | 五步向导：源目标 → 范围 → 对比 → 预览勾选/导出 → 执行/取消/报告 | 🟡 **进行中** | 结构/数据/两者 + DROP 选项 + 取消 + 错误注入全过 |
| **M5 打包发布** | 图标 / jpackage dmg / GitHub Actions(win+linux) / README | ⬜ 未开始 | 三平台产物可安装启动，CI 绿 |

---

## M3 明细：同步引擎（本轮完成）

### 交付的代码

| 类 | 职责 |
|---|---|
| `metadata/DdlNormalizer` | SHOW CREATE 规范化：剥 AUTO_INCREMENT 计数 / DEFINER / 版本注释，`int(11)`≡`int`（`tinyint(1)` 保留），CURRENT_TIMESTAMP 归一，冗余 COLLATE 消除；**只在代码上下文生效**，字符串字面量与注释原样保留 |
| `metadata/TableDdlParser` | 括号/引号感知的表 DDL 解析 → 列 / 约束（PK·UNIQUE·KEY·FULLTEXT·SPATIAL·FK·CHECK）/ 表选项 |
| `sync/SchemaComparator` | 双侧读元数据 → `SyncPlan`：表按 FK 拓扑序，结构差异→ALTER，数据差异→流式 producer；无主键降级；count+CHECKSUM 快检；破坏性变更写 warnings |
| `sync/TableDiffer` | 列名 LCS 配对 → `ADD … AFTER` / `DROP` / `MODIFY`；索引外键按名配对 → `DROP`+`ADD`；字符集漂移 → `CONVERT TO`；合成**单条多子句 ALTER**，`DROP PRIMARY KEY` 置首 |
| `sync/DataDiffEngine` | 双流 keyset 游标归并（`WHERE (k1,k2) > (?,?,?)`），常量内存；游标不前进 / 键列含 NULL 一票否决 |
| `sync/JdbcRowReader` | 分块读 + `COUNT` + `CHECKSUM TABLE` |
| `sync/TableDataProducer` | DIFF / COPY 两模式，每次 produce 自开自关读连接（MySQL 流式读期间不能复用连接） |
| `sync/TableOrdering` | FK 拓扑排序，自引用/环容忍降级 |
| `sync/DdlRewriter` | 把 DDL 里的源库限定名改写为目标库（视图体/外键/触发器 ON 表），字面量与注释免疫 |
| `sync/SyncExecutor` | `FOREIGN_KEY_CHECKS=0` → 表结构 → 数据 → 视图 → 例程 → **触发器最后**；批量写 + 每 chunk 提交；取消在块边界收口 |
| `sync/DdlScriptGenerator` | 流式导出可独立执行的脚本（`USE` + 分隔符块 + 多行 INSERT 批 + 收尾 FK=1） |
| `sync/Cancellation` / `SyncScope` / `SyncPlan` / `SyncAction` / `SyncReport` | 计划模型与共享取消标志 |

### 验收证据：`DockerSyncIT` ×9（真库端到端）

- [x] `compareClassifiesEveryDriftExactlyOnce` — ALTER/CREATE/DATA/VIEW/PROCEDURE/TRIGGER 各一次，无主键表规划为回灌并出警告
- [x] `applyingThePlanLeavesNothingToApply` — **收敛性**：执行后再比较，选中动作集为空；行数、被删行、被改值全对
- [x] `objectDefinitionsAreEquivalentAfterSyncIncludingRewrittenQualifiers` — 表 `DdlNormalizer.equivalent`；视图/过程/触发器 DDL 相等且**不再引用源库名**；视图查询结果自证指向目标库
- [x] `structureOnlyModeDoesNotTouchRows` — 只同步结构时既有行一行不动，新表建成空表
- [x] `dataOnlyModeMovesRowsAndRefusesMissingTables` — 只同步数据时无任何 DDL（仅回灌的 DELETE），三表行数一致
- [x] `dropExtraneousRemovesTargetOnlyObjects` / `scopeRestrictsThePlanToWhatWasSelected`
- [x] `cancellingStopsAtTheNextActionAndReportsWhatWasSkipped`
- [x] `exportedScriptRebuildsAnEmptySchemaOnItsOwn` — 导出的 .sql 单靠文本把空库重放成与源库一致（比较器再查无差异）

### 测试规模

`./mvnw verify` → **116 单测 + 15 docker 集成测试，全绿**（M1 时是 15）。

---

## 本轮修掉的问题（含根因，避免复犯）

| # | 问题 | 根因 | 修法 |
|---|---|---|---|
| 1 | **IDEA 反复崩溃**（AMD 驱动 assert） | `AMDMTLBronzeDriver 7.0.24` 在 macOS 15.7.9 上不稳定，IDEA 的 Java2D 默认走 Metal；崩溃在 IDEA 进程内，MyDog 本身正常 | `idea.vmoptions` 加 `-Dsun.java2d.metal=false`（已验证生效）；自动化 GUI 跑用 `-Dmydog.prism.order=sw` 零 GPU |
| 2 | 测试连接报 Access denied | `ConnectionDialog` 把密码写死空串 | `plainPassword()`：优先输入框明文，否则解密已存密文 |
| 3 | 集成测试静默不跑 | failsafe 的 `testSourceDirectory` 只选类不编译 | build-helper 挂 `src/it/java` 为 test 源根 |
| 4 | 弹窗文字截断 / 按钮文案错 | 不可调宽 `Dialog` 按内容算尺寸 | 消息标签显式宽度；结果改主窗状态栏 |
| 5 | **过程/触发器同步把 sql_mode 当 DDL 执行** | `SHOW CREATE PROCEDURE` 的 DDL 在第 6 列、`SHOW CREATE TRIGGER` 在 `SQL Original Statement` 列，旧 `showCreate()` 一律读第 2 列 | 按列标签定位（`Create *` / `SQL Original Statement`），位置读法仅作兜底 |
| 6 | `readTable` 的字符集/行数估计恒为默认值 | JOIN 了 MariaDB 专有的 `information_schema.COLLATION_COLLATE`，MySQL 8 报 Unknown table，被 `catch (SQLException ignored)` 吞掉；且列名写错（`DEFAULT_COLLATION_NAME` 实为 `DEFAULT_COLLATE_NAME`） | 去掉错误 JOIN，只留 `CHARACTER_SETS`；**删掉静默 catch**——错误必须浮出来 |
| 7 | 导出脚本里 TIMESTAMP 偏移 8 小时 | `sqlLiteral` 用 `Timestamp.toLocalDateTime()`，按 JVM 本地时区渲染；而所有 MyDog 会话强制 UTC，回写即错位 | Timestamp 一律按 **UTC** 文本渲染（含非零小数秒），DATETIME（`LocalDateTime`）保持原样 |
| 8 | 过程/视图每次比较都"有差异"，永远同步不完 | 对象体里的源库限定名没重写就参与比较；且 `normalizeObjectDdl` 按行裁剪，两行写法 vs 一行写法不等 | 比较前先走与执行同一条 `portable()` 重写；对象文本比较再折叠空白 run |
| 9 | 数据一致时仍每次都排 DATA 动作 | 比较阶段从不验证数据是否真的不同 | keyed 表走 count+`CHECKSUM TABLE` 快检，相等则出 SKIP（详情写明"未逐行校验"），大表不做校验以免拖慢比较 |

---

## 环境与命令速查

```bash
# 集成测试沙箱（源 3307 / 目标 3308，root/root123，演示库 shop）
cd docker && docker compose up -d --wait

# 全量测试（单测 + 依赖 docker 的集成测试；docker 没起时 IT 自动跳过而非失败）
JAVA_HOME=/Users/ios/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home ./mvnw verify

# 只跑同步引擎端到端
JAVA_HOME=.../openjdk-25.0.2/Contents/Home ./mvnw verify -Dit.test=DockerSyncIT

# 启动应用（日常手动跑：es2 = OpenGL）
JAVA_HOME=.../openjdk-25.0.2/Contents/Home ./mvnw javafx:run

# 自动化冒烟（零 GPU，规避本机 AMD 驱动 bug）
JAVA_HOME=.../openjdk-25.0.2/Contents/Home ./mvnw javafx:run -Dmydog.prism.order=sw
```

- 用户配置目录：`~/.mydog/`（`connections.json` / `settings.json` / `.secret.key`，均 0600）
- 技术基线：**Java 25 LTS**（补丁至 ~2030）+ JavaFX 27.0.1 + jpackage 捆绑运行时；升级红线：未来整体升 JDK 29 LTS + JavaFX 29，不单点升级

---

## 下一步：M4 同步向导 UI

1. `ui/sync/SyncWizard` 五步：连接与库选择 → 对象范围（多选/全选/名字过滤）→ 对比 → 预览树勾选 + 脚本导出 → 执行（进度 + 取消）+ 报告
2. `SyncWizardModel` 承载比较/执行（`Task` 化，FX 线程绝不碰 JDBC），使 UI 可薄到不需要 GUI 也能测
3. i18n：`sync.*` 全量文案（中英）
4. 从主窗口菜单/工具栏/树右键入口挂上
5. M5：图标、jpackage、GitHub Actions matrix、README 收尾
