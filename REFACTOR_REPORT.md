# M3 Expressive UI 重构报告

> 项目：Ren'Py Mobile IDE（Jetpack Compose + Material3）
> 基线提交：`11dbf58` · 最终提交：`a8f7524`
> 全部 5 阶段已完成，共修改 9 个文件（+197 / −17 行），新增 3 个 token 文件。

## 参考依据（均已实读 + 官方交叉验证）
- `abhixv/google-m3-expressive-design-skill`（原仓库重定向）：`tokens.md` / `platforms.md` / `motion.md`
- `asimawdah/material-expressive-skill`：SKILL + design foundations
- `canine-labs/m3-expressive-design-md`：DESIGN.md §7（动效）/ §73/181（48dp 触控）
- `albermonte/android-skills`：`compose-mapping.md`
- 官方：`MaterialExpressiveTheme` API 签名、`DropdownMenu.shape` 参数、`material.io/blog/m3-expressive-motion-theming`、Androidify 示例

## 分阶段变更

| 阶段 | 提交 | 改动 |
|---|---|---|
| 1 主题与 token | `7069880` | `MaterialTheme`→`MaterialExpressiveTheme`（显式传 colorScheme 保留品牌 48 色）；新增 `@OptIn(ExperimentalMaterial3ExpressiveApi)`；新增 `IdeShapes`（M3 10 级圆角）、`SemanticColors`（warning/success/Git 状态）；日志面板 4 处硬编码色 → 语义 token（值不变） |
| 2 全局容器/导航 | `1474258` | 新增 `MotionTokens`（spatial/effects spec + Reduce Motion）；MainActivity 导航转场接入 token 并支持系统"移除动画"降级 |
| 3 编辑器周边 | `a16896e` | 修正 AiProvidersScreen 3 处、EditorScreen 1 处硬编码 spring/tween → motionScheme token，尊重 Reduce Motion |
| 4 命令面板/反馈 | `046f217` | 现有 QuickToolsMenu（DropdownMenu）圆角 → M3 CornerMedium token；确认未引入 FAB |
| 5 无障碍收尾 | `a8f7524` | 循环装饰动画（思考点/光标/滚动提示）Reduce Motion 时静态化；完成 48dp 触控与焦点审计 |

## 关键设计决策
- **不新建命令面板**：项目已有 QuickToolsMenu 作为命令入口，避免重复组件。注：其中"撤销/缩进/搜索/跳行/Lint"为无功能占位项（属业务逻辑，未在本次范围）。
- **Toast 不迁 Snackbar**：全项目 20+ 处反馈用原生 Toast，改 Snackbar 需 Scaffold 基础设施且改变交互行为，违反"严格保持功能不变"，列为后续建议。
- **SetupWizard 私有动效函数保留**：其内部 mSpatial/mEffects 有 11 处调用且已验证，行为与共享 token 一致，避免触碰。
- **高密度区零改动**：编辑器/文件树/终端/日志的代码字体、高密度布局、Lazy 列表均未改动。
- **零编造色值**：warning/success 沿用项目原值；Git 状态色映射到现有 ColorScheme 角色，自动适配深浅主题。

## 验证结果
- ✅ 静态校验：9 个改动文件括号结构与基线一致，无语法失衡；所有本地 import 可解析
- ✅ 包声明 / import 路径一致性检查通过
- ⚠️ **未能编译验证**：沙箱无 Android SDK / Gradle

## 人工验收清单（请在你本地执行）
1. `./gradlew assembleDebug` 编译通过（重点确认 `MaterialExpressiveTheme` 在当前 BOM 可用、opt-in 生效）
2. 打开项目 → 编辑器 → 切标签 → 文件树导航 → 终端 → 打包面板，路径无回归
3. 主题切换（浅/深、5 套配色）→ 品牌色保留、日志色正确
4. 系统设置开启"移除动画" → 导航/面板/思考点/光标动效停止但功能正常
5. 编辑器区域文字长时间阅读无刺眼、信息密度未下降
6. 文本选择菜单 48dp 触控目标可正常点击

## 回滚
任一阶段有问题可用 git 回滚，例如：`git revert a8f7524`，或整体 `git reset --hard 11dbf58`（会丢弃全部重构）。
