# Toast → Snackbar 迁移报告

> 基线：`9e12de8`（迁移前）。本次迁移 Toast **37 处全部清零**，改为语义化 Snackbar。
> 沙箱无 Android SDK/Gradle，所有改动经**静态校验**；编译与实机行为需本地验证。

## 一、架构

```
RenPyMobileTheme {
  SnackbarHostState + SnackbarMessenger
  CompositionLocalProvider(LocalMessenger) {
    Box {
      AppNav(AnimatedContent{ 各屏幕 })   // 全局 host 在导航之外，转场不跟随滑动
      IdeSnackbarHost(...)                 // 屏幕级提示
    }
  }
}
```

BuildSheet（ModalBottomSheet，浮在全局 host 之上的 dialog 窗口）内：
```
CompositionLocalProvider(LocalMessenger provides sheetMessenger) {
  Box { 原内容 Column; IdeSnackbarHost(sheetHostState) }  // 独立 host，提示不被 sheet 遮挡
}
```

**关键决策**：用 Box 叠加而非嵌套 Scaffold——各屏幕已有自己的 Scaffold，嵌套会叠加 padding 破坏布局；`SnackbarHost` 是独立 composable，无需 Scaffold 即可工作。

## 二、改动清单

| 提交 | 内容 |
|---|---|
| `16523da` | 新增 `SnackbarMessenger.kt`（基础设施），MainActivity 全局 host + 迁移双击退出提示 |
| `bfa743b` | BuildSheet 独立 host + 迁移其 7 处 Toast |
| `64c65b0` | 其余 30 处（EditorScreen 7 / BuildScreen 6 / SettingsScreen 5 / ProjectTree 5 / AiProvidersScreen 4 / WelcomeScreen 2）|

**新增文件**：`ui/theme/SnackbarMessenger.kt`（156 行）
- `MessageTone` 语义枚举 + 图标 + 语义色
- `IdeSnackbarVisuals : SnackbarVisuals`（携带 tone）
- `SnackbarMessenger.show()`（非 suspend，内部 launch）
- `LocalMessenger` CompositionLocal
- `IdeSnackbarHost`（图标 + 消息的自定义渲染）

**修改文件**：MainActivity、BuildSheet、BuildScreen、EditorScreen、SettingsScreen、ProjectTree、AiProvidersScreen、WelcomeScreen（共 8 个）

## 三、语义分级映射（零编造色值）

| 语气 | 场景 | 颜色来源 | 图标 |
|---|---|---|---|
| SUCCESS | 已保存、已导入、已删除、编译成功、已是最新 | `IdeSemanticColors.success`（项目原 `0xFF4CAF50`）| `CheckCircle` |
| ERROR | 保存/安装/分享失败、包名错误、编译失败、检查失败 | `MaterialTheme.colorScheme.error` | `Error` |
| WARNING | 无打开文件、至少保留一个服务商、无日志内容 | `IdeSemanticColors.warning`（项目原 `0xFFE65100`）| `Warning` |
| INFO | 未保存更改、已切换、取消编译、位置提示、插件未开放 | `MaterialTheme.colorScheme.primary` | `Info` |

图标来自项目已依赖的 `material-icons-extended`（namespace 与现有用法一致）。颜色全部取自项目已有 token，自动适配明暗主题与 5 套配色。

## 四、上下文分类处理

| 类型 | 处理 | 示例 |
|---|---|---|
| Composable 回调 | `LocalMessenger.current` | 各屏幕 onClick |
| 协程 `withContext(Main)` | `messenger.show()` 非 suspend 入口 | BuildScreen/BuildSheet 编译结果 |
| `LaunchedEffect` | 同上，保留清状态逻辑 | SettingsScreen 更新检查 |
| 非 Composable 函数 | 加 `onMessage: (String, MessageTone) -> Unit` 参数，调用点传 messenger | `runFs`/`doImport`/`installApk`/`shareApk` |

`duration` 映射：`LENGTH_SHORT → Short`，`LENGTH_LONG → Long`。

## 五、静态校验结果

- ✅ 全项目 `Toast.makeText` / `android.widget.Toast` **0 残留**
- ✅ 所有改动文件括号平衡；对比基线，diff 增删行括号**净变化为 0**
- ✅ 6 个新增 theme 符号全部有定义；所有 `LocalMessenger.current` 使用文件都有对应 import
- ✅ 每个使用 messenger 的文件都有 messenger 定义（作用域正确）
- ✅ 语义色全部来自已有 token；图标 namespace 与项目现有用法一致

## 六、风险与已知问题

| 级别 | 项 | 说明 |
|---|---|---|
| 🔴 | **未能编译** | 沙箱无 SDK/Gradle。请本地 `./gradlew assembleDebug`。重点确认 `SnackbarHostState.showSnackbar(visuals)`（已查官方参考页为稳定 API）、`IdeSnackbarVisuals` 实现的 4 个 override 属性签名 |
| 🟠 | **BuildSheet 遮挡** | 已用独立 host 解决，但需实机确认：sheet 打开时点"复制日志/包名错误"提示可见 |
| 🟡 | Snackbar 队列 | `SnackbarHostState` 一次只显示一条，连续触发会排队（与原 Toast 叠盖行为不同）。验收时测连点 |
| 🟡 | 缩进风格 | BuildSheet 的 Provider/Box 包裹层缩进偏浅（功能正确、括号平衡）。因无法编译，未做大范围缩进调整以免引入风险 |
| 🟡 | Reduce Motion | Snackbar 自身进出动画由 M3 内部管理，需实机确认系统"移除动画"下表现 |

## 七、人工验收清单

- [ ] 编译通过 `./gradlew assembleDebug`
- [ ] 编辑器保存成功/失败 → 绿色✓/红色✗ snackbar
- [ ] 文件树新建/删除/导入 → 语义色正确
- [ ] 编译结果 → 成功绿/失败红，LONG 时长
- [ ] **BuildSheet 打开时**：包名错误、复制日志、安装/分享失败提示可见（独立 host 验证）
- [ ] 双击返回 → "再按一次退出"提示可见
- [ ] 更新检查 → 已是最新（绿）/检查失败（红）
- [ ] 连续快速操作 → 提示排队不丢失
- [ ] 深色模式 + 各配色下语义色对比度舒适

## 八、回滚

```bash
git revert 64c65b0 bfa743b 16523da   # 逐步撤销
# 或整体回到迁移前
git reset --hard 9e12de8
```
