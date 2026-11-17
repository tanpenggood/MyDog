# MyDog

跨平台（Windows / macOS / Linux）MySQL 桌面管理工具，核心特色是**数据库间同步**（整库、表/视图/触发器/函数/存储过程，可选只同步结构、只同步数据、或结构+数据，支持对象多选与按名搜索）。

- 技术栈：Java 25 LTS + JavaFX 27 + Maven（Wrapper），jpackage 捆绑完整运行时，免装 Java
- 界面：中英双语，运行时可切换
- 进度看板：[PROGRESS.md](PROGRESS.md)

## 功能

- **连接管理**：多连接 CRUD + 测试连接；密码以 AES-GCM 加密存于 `~/.mydog/connections.json`
  （文件权限 0600，密钥 `~/.mydog/.secret.key`，全部原子写入，重启后无明文）
- **对象树**：库/表/视图/触发器/函数/存储过程懒加载 + 右键操作
- **SQL 编辑器**：多 Tab、语法高亮、`DELIMITER` 感知的多语句执行、结果集/消息面板
- **数据查看器**：keyset 分页、服务端排序与 WHERE 过滤，可直接编辑单元格
- **结构查看器**：SHOW CREATE 与列/索引/外键明细
- **同步向导（签名功能）**：五步向导——源/目标连接 → 同步范围（整库或按对象多选、按名过滤，
  结构/数据/两者）→ 差异对比 → 预览（逐对象勾选、完整脚本导出）→ 执行（进度、取消）+ 报告。
  入口：菜单「工具 → 数据库同步…」，或对象树里在某个库上右键「同步…」（该库预填为源库）。
  执行顺序保证正确性：外键检查关闭 → 表结构（依赖序）→ 数据 → 视图 → 例程 → 触发器最后
- **同步的两条安全默认**：只在目标存在的对象**默认不勾选**（勾上且开了「删除多余对象」才会出 DROP，
  删除动作会进警告清单）；视图/触发器依赖的基表若不在目标也不在本次范围内，会被**规划为跳过并在预览
  说明原因**，而不是执行到它才报 1146 并停住半应用的一轮
- **限制**：v1 面向 MySQL 8.0+（5.7 best-effort）；MariaDB 未支持

## 从源码构建

要求：**JDK 25**（LTS，如 Temurin/Corretto 25）+ 网络（首次拉取依赖）。无需本地安装 Maven/JavaFX。

```bash
./mvnw compile          # 编译
./mvnw test             # 单元测试（无外部依赖）
./mvnw javafx:run       # 启动应用（开发模式）
```

## 测试环境（Docker 双 MySQL）

集成测试与手工验收使用 `docker/compose.yaml` 提供的两个 MySQL 8 沙箱：
源库 `127.0.0.1:3307`、目标库 `127.0.0.1:3308`（root / root123，库名 `shop`，两侧预置差异化 fixture）：

```bash
cd docker && docker compose up -d --wait
```

## 运行测试

```bash
./mvnw test     # 仅单元测试（surefire，排除 "docker" 标签）
./mvnw verify   # 单元 + 集成测试（failsafe，@Tag("docker")）
```

集成测试在沙箱（3307/3308）不可达时自动跳过（`Assumptions.abort`），**不会**导致构建失败——
因此无 Docker 沙箱的环境直接跑 `verify` 是安全的，无需 `-Dit.skip`。

`.github/workflows/test.yml` 会在 CI 里用同一份 `docker/compose.yaml` 真起两个 MySQL，并在
`xvfb-run` 下执行 `verify`：`SyncWizardUiIT` 要按真按钮，Linux 上 JavaFX 需要 X11 + GTK；
测试进程的渲染固定软件后端（pom 的 `mydog.test.prism.order`，默认 `sw`，可用
`-Dmydog.test.prism.order=es2` 覆盖）。

```bash
# 只跑某几个集成测试类（多个用逗号）。注意：直接调 failsafe 目标不会重编译，
# 改过测试代码要先 test-compile，否则跑的是旧字节码。
./mvnw -DskipTests test-compile
./mvnw -Dit.test='DockerSyncIT,SyncWizardUiIT' failsafe:integration-test failsafe:verify
```

## 打包安装器（jpackage，非模块化 classpath 路线）

v1 采用非模块化 classpath 打包：`copy-dependencies` + 应用 jar 汇入 `target/app`，
jpackage 捆绑完整 JDK 25 运行时（未做 jlink 裁剪——macOS 实测 app ~137 MB / dmg 66 MB，
属预期量级；模块化裁剪留 v2）。JavaFX 平台 jar 由 openjfx POM 的 OS profile 自动解析，
**不要**给依赖加 classifier。启动器配置里追加了
`--module-path=$APPDIR --add-modules=javafx.controls` 两个 java-option：classpath 直启
`Application` 子类会被 JDK 启动器拒绝（"JavaFX runtime components are missing"），
而 openjfx 的 `-<os>` classifier jar 自带 module-info 与原生库，从模块路径解析即可正常启动。

| 平台 | 命令（在本机系统上执行） | 产物 |
|---|---|---|
| macOS (dmg) | `./mvnw -P native-mac verify` | `target/dist/MyDog-1.0.0.dmg` |
| Windows (msi) | `mvnw.cmd -P native-win verify` | `target/dist/MyDog-1.0.0.msi` |
| Linux (deb) | `./mvnw -P native-linux verify` | `target/dist/mydog_1.0.0_amd64.deb` |

jpackage 不能跨平台构建：三个安装器分别由 `.github/workflows/release.yml` 在
windows-latest / ubuntu-latest / macos-latest 原生 runner 上产出（Temurin 25），
产物以 artifact 形式上传。注意 macos-latest 为 Apple Silicon，v1 的 dmg 是 arm64 版。

本地打 Windows 的 msi 需要先装 **WiX Toolset v3** 并让 `candle.exe` / `light.exe` 在 PATH 上
（jpackage 生成 msi 硬依赖它，与项目代码无关）；release.yml 的 Windows 任务已自动安装，
Linux 的 deb 与 macOS 的 dmg 没有这类外部依赖。三个 profile 的 `--input` 都是 `target/app`，
图标用绝对路径传给 `--icon`，无需把图标复制进输入目录。

### macOS 未签名说明

v1 的 dmg **未做代码签名与公证**。首次打开会提示"无法验证开发者"：
在 Finder 中**右键点击 MyDog.app → 打开 → 打开**即可（每个用户只需一次）。
可选后续步骤（v2/发布版）：`codesign --deep --sign -`（本地签名）→ Developer ID 签名 +
`notarize`/`staple`（需 Apple Developer 账号），在 workflow 中作为 mac 任务追加。

### 已知硬件问题：部分 AMD GPU Mac 上 JavaFX 渲染异常

在搭载 AMD Radeon Pro 555X/560X 一类显卡、系统为 macOS 15.7.x 的 Intel Mac 上，
JavaFX 的 Metal 渲染管线可能异常（花屏/崩溃）。规避方法是强制走 OpenGL 或软件渲染：

- 源码运行：`./mvnw javafx:run -Dmydog.prism.order=es2`（OpenGL，默认）
  或 `-Dmydog.prism.order=sw`（软件渲染，最稳）。pom 把应用与测试的参数**分开**：
  `mydog.prism.order`（应用，默认 `es2`）与 `mydog.test.prism.order`（surefire/failsafe 的
  测试 fork，默认 `sw`），互不干扰。
- 打包后的 app：编辑 `MyDog.app/Contents/app/MyDog.cfg`，在 `[JavaOptions]` 段追加一行
  `-Dprism.order=es2`（或 `sw`）。Windows/Linux 不受此问题影响。

## 目录结构速览

```
PROGRESS.md                 # 进度看板：里程碑状态、根因台账、命令速查
pom.xml                     # 三个 native-* profile 见"打包"一节
docker/                     # 双 MySQL 沙箱 compose + 差异化 fixture
src/main/packaging/         # 安装器图标 + make_icons.py 再生成脚本
.github/workflows/          # test.yml：沙箱 + xvfb 跑全量测试；release.yml：三平台安装器
src/main/java/com/mydog/    # core/connection/metadata/sql/sync/ui/i18n/util
src/it/java/                # failsafe 集成测试（@Tag("docker")）
```

图标再生成（macOS 上执行，需 `sips`/`iconutil`）：`python3 src/main/packaging/make_icons.py`

## License

Apache 2.0
