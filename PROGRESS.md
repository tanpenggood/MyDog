# MyDog 开发进度看板

> 最后更新：2026-11-17 08:50 · 分支 `main` · 状态 **M4 完成（向导 + 依赖拒绝），M5 打包/CI 进行中**

整体进度：`M0 ████████████ 100% · M1 ████████████ 100% · M2 ████████████ 100% · M3 ████████████ 100% · M4 ████████████ 100% · M5 ███░░░░░░░░░ 25%`

完整设计计划（架构/选型理由/同步引擎设计）见 `~/.claude/plans/mysql-windows-macos-linux-sql-distributed-engelbart.md`。

---

## 里程碑总览

| 里程碑 | 内容 | 状态 | 验收标准 |
|---|---|---|---|
| **M0 环境** | JDK 25 LTS + Docker + compose 双 MySQL | ✅ **完成** | `java -version`=25.0.2；3307/3308 healthy |
| **M1 骨架** | pom / 包结构 / i18n 中英切换 / 连接管理（CRUD+测试+加密） | ✅ **完成**（已 commit `5290fae`） | 应用可跑；连 docker 库测试成功；密码密文；语言全量切换 |
| **M2 浏览编辑** | 对象树 / SQL 编辑器（RichTextFX）/ 数据查看器 / 结构查看器 | ✅ **完成** | 对源库跑通浏览、执行 SQL、看数据与 DDL |
| **M3 同步引擎（无 UI）** | metadata 解析 + DdlNormalizer + Comparator + DataDiffEngine + SyncPlan | ✅ **完成** | `./mvnw verify` 绿；同步后两侧 SHOW CREATE/CHECKSUM/行数一致（9 个端到端 IT 证明） |
| **M4 同步向导 UI** | 五步向导：源目标 → 范围 → 对比 → 预览勾选/导出 → 执行/取消/报告 | ✅ **完成** | 自动化点击全流程绿（`SyncWizardUiIT` 走完 5 步 + 返回 + 第二轮结构/DROP）；`./mvnw verify` 121 单测 + 24 IT 全绿 |
| **M5 打包发布** | 图标 / jpackage dmg / GitHub Actions(win+linux) / README | 🟡 **进行中** | 三平台产物可安装启动，CI 绿（本地 dmg 已产出；CI test job 待补） |

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

`./mvnw verify` → 当时 **116 单测 + 15 docker 集成测试，全绿**（M1 时是 15）。最新规模见上一节：121 + 24。

---

## M4 明细：同步向导 UI（本轮完成）

### 交付的代码

| 类 | 职责 |
|---|---|
| `ui/sync/SyncWizard` | 五步向导外壳（连接与库 → 对象范围 → 对比 → 预览勾选/导出 → 执行/取消/报告）；步骤面板缓存，所以「上一步」不丢用户选过的东西 |
| `ui/sync/SyncWizardModel` | 向导的后半程：凡触网的动作一律以 `javafx.concurrent.Task` 暴露（FX 线程绝不碰 JDBC），plan/report 存这里，UI 只是它的一层薄壳 |
| 向导的测试接缝 | `open()` / `showAndWait()` / `model()` / `paneOf()` / `currentStep()` / `dialog()`：让测试真按按钮，而不是只测模型 |
| `sync/SyncAction.dependsOn` + `sync/DdlRewriter.readsFrom` | 视图/触发器依赖哪张表：扫 `FROM`/`JOIN` 后的名字，字符串与注释免疫，`库.表` 限定名只认目标库，派生表不算依赖 |
| `ui/MainWindow`、`ui/ObjectTreeController` | 入口：`工具` 菜单，以及对象树库节点右键「同步…」（后者把当前库预填为源库） |
| `src/it/java/.../FxTestSupport` | 起真 toolkit 的测试基座（`fx` / `runOnFx` / `run(Task)` / `failureOf` / `waitUntil`），无显示环境自动 skip |

### 验收证据

- `SyncWizardUiIT` — **真按钮全流程**（需 docker + 显示）：选连接与库 → 对象范围（含按名过滤；目标独有表默认不勾）→ 自动比较 → 预览取消勾选一张表 → 执行并核对目标行数 → 连点 4 次「上一步」回第 1 步且选择仍在 → 换「仅结构」+「删除目标多余对象」+ 勾上 `t_scratch` 再走一遍：新计划无 DATA 动作、有 `t_extra` 的 CREATE、有 `t_scratch` 的 DROP
- `SyncWizardModelIT` ×6（无需显示）：每步都走 `Task`；plan/report 的捕获与失效规则；范围排除；`DATA_ONLY` 对缺表出 SKIP；导出用存储的 plan；两端同库自检；没有 plan 就执行 → 任务失败而不是 FX 线程抛异常
- `DockerSyncIT` ×11（引擎端到端，本轮新增 2 个依赖用例）
- **121 单测 + 24 集成测试**，`./mvnw verify` 全绿（M1 时 15 个测试）

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
| 10 | **向导点「下一步」整个对话框消失**（自动化点击测试抓到，手工同样能复现） | JavaFX 的 `Dialog` 对按钮条上**任何**按钮的 ACTION 都会 `setResultAndClose()`——按钮条的语义是“结束对话框”，不是“在对话框里导航”，`ButtonType.NEXT` 也不例外 | Back/Next 改用 `addEventFilter(ActionEvent.ACTION)`：先 `consume()` 再自己办事；Close 保留原生关闭行为，但加过滤器先 `model.cancel()`（DDL 不可回滚，传输不该活得过窗口） |
| 11 | 范围里没选基表时，视图/触发器仍被规划成 CREATE，执行到它才报 1146，并且**整轮停住**（已应用的不会回滚） | 规划阶段从不检查对象依赖的表在目标上会不会存在 | 比较阶段：基表既不在目标也不在本次计划 → 出 SKIP 并写进 warnings（预览当场说明“不会创建”）；执行阶段：运行时基表不在目标 → 该动作 SKIPPED，不级联停轮。触发器基表直接取元数据 `TriggerDef.table`，视图用 `DdlRewriter.readsFrom` |
| 12 | 应用内切英文是空操作（界面仍中文） | `ResourceBundle` 候选链把 **OS 默认 locale** 插在 base bundle 之前：中文机器上 `getBundle(..., ENGLISH)` 命中的是 `Messages_zh_CN`；自定义 `Control.getCandidateLocales` 也拦不住 | 补一个**故意留空**的 `Messages_en.properties`：空 bundle 的 parent 是 base bundle，`containsKey`/`keySet` 沿 parent 查，英文赢过 OS locale；`I18nBundleTest.englishWinsOverTheOperatingSystemLocale` 锁死 |
| 13 | 向导三个按钮文案跟着**操作系统**语言，不跟应用语言 | JavaFX 内置 `ButtonType` 的默认文本由 OS locale 决定 | 每一步都显式 `setText`（上一步 / 下一步·应用 / 关闭·取消），文案走 `I18n.tr` |
| 14 | 第 2 步「全选」默认把**目标独有**对象也勾上 = 默认排了一批 DELETE | 载入候选后无条件 `selectAllCheck.setSelected(true)`，其监听器把所有行（含只在目标存在的）一并勾选 | 载入时不自动全选：源侧对象默认勾选，目标独有默认不勾；过滤只改可见集不改勾选；全选框状态由可见行反推（`applyingSelectAll` 防回环） |
| 15 | 换了源/目标库后，第 2 步还是上一轮的对象列表 | `candidatesLoaded` 缓存没随端点失效 | `advance()` 离开第 1 步时置 `candidatesLoaded=false`；`loadCandidates()` 先清行再显示进度 |
| 16 | 模型/向导测试一跑就 `Task must only be used from the FX Application Thread` | `Task` 默认 `Mode.PLATFORM`：progress/message/value 在 FX 线程投递（生产正确），代价是非 FX 线程不得读它的状态 | 测试用 `Platform.startup` 起真 toolkit、worker 线程启动任务、结果经 `CompletableFuture` 回传（`FxTestSupport`）；无显示则 `Assumptions.abort` 跳过 |
| 17 | 向导 IT 在「下一步」前空等 90 秒 | 等待条件写成 combo 的 **items 含目标库**，而第 1 步读的是 combo 的 **value**（列表先到、预选值后到）；另外 `AppContext.createIn` 构造时会重新应用存档 locale，测试必须在它之后 `I18n.setLocale("en")` | 等待条件改成断言“下一步真正会读到的值”；并在 `click()` 里先断言对话框还开着——GUI 测试丢了窗口要当场报错，而不是 20 行后报个不相干的错 |

---

## 环境与命令速查

```bash
# 集成测试沙箱（源 3307 / 目标 3308，root/root123，演示库 shop）
cd docker && docker compose up -d --wait

# 全量测试（单测 + 依赖 docker 的集成测试；docker 没起时 IT 自动跳过而非失败）
JAVA_HOME=/Users/ios/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home ./mvnw verify

# 只跑某一类集成测试（多个类用逗号；**必须先 test-compile**，直接调 failsafe 目标不会重编译）
JAVA_HOME=.../openjdk-25.0.2/Contents/Home ./mvnw -DskipTests test-compile
JAVA_HOME=.../openjdk-25.0.2/Contents/Home ./mvnw -Dit.test='DockerSyncIT,SyncWizardUiIT' failsafe:integration-test failsafe:verify

# 测试进程的渲染后端：pom 里 surefire/failsafe 固定 -Dprism.order=${mydog.test.prism.order}，默认 sw（零 GPU 调用，
# 规避本机 AMD Metal 驱动崩溃）；要调试真 GPU 就 -Dmydog.test.prism.order=es2

# 启动应用（日常手动跑：es2 = OpenGL）
JAVA_HOME=.../openjdk-25.0.2/Contents/Home ./mvnw javafx:run

# 自动化冒烟（零 GPU，规避本机 AMD 驱动 bug）
JAVA_HOME=.../openjdk-25.0.2/Contents/Home ./mvnw javafx:run -Dmydog.prism.order=sw
```

- 用户配置目录：`~/.mydog/`（`connections.json` / `settings.json` / `.secret.key`，均 0600）
- 技术基线：**Java 25 LTS**（补丁至 ~2030）+ JavaFX 27.0.1 + jpackage 捆绑运行时；升级红线：未来整体升 JDK 29 LTS + JavaFX 29，不单点升级

---

## 下一步：M5 打包发布收尾

1. ✅ 图标（`src/main/packaging/`：icns/ico/512px + `make_icons.py` 再生成）、jpackage 三平台 profile、`.github/workflows/release.yml`（win msi / linux deb / mac dmg 原生 runner + artifact）
2. ⬜ **CI test job**：ubuntu runner + 两个 MySQL service 容器（3307/3308）+ xvfb，让 `@Tag("docker")` 的 24 个集成测试（含向导点击测试）在 CI 真跑，而不是在 release job 里被 skip
3. ⬜ README 校对：`sync.*` 向导入口（工具菜单 / 树右键）、`mydog.test.prism.order`、依赖拒绝规则（视图基表不在范围内会被跳过并出警告）、新测试规模
4. ⬜ 手工验收（需要用户）：语言切换、五步向导全路径、取消、导出脚本/报告
5. ⬜ 打 tag 出第一版安装包；未签名 dmg 的右键打开步骤已在 README

### M4 遗留的已知边界（v1 有意不做）

- 目标库不存在时由用户在向导第 1 步自己建（应用不代替用户 `CREATE SCHEMA`）
- `CREATE TABLE IF NOT EXISTS` / `DROP ... IF EXISTS` 这类 DDL 语句本身不做“取消”——取消只在 chunk 边界收口
- 视图基表依赖只识别 `FROM`/`JOIN`（子查询里的表也识别，派生表与 CTE 名不识别）：认不出的名字一律不当依赖，宁可多试一次由 MySQL 报错，也不误跳过用户的对象
