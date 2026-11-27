# Ren'Py Mobile

> 一款运行在 Android 上的 Ren'Py 集成开发环境（IDE）——脚本编辑、游戏编译、APK 打包与 AI 编程助手，全部在手机上完成。开源免费。

- **版本**：v0.3.7（首个公开版本）
- **平台**：Android（minSdk 24 / targetSdk 28）
- **技术栈**：Kotlin + Jetpack Compose + Material 3 Expressive，原生桥接 `librenpython.so`，内置 Python / renpy-mcp

---

## 功能一览

> **首个公开版本** · 发布于 2026-11-23 · **多语言支持更新** · 发布于 2026-11-27
>
> 从零构建的完整 Android Ren'Py 集成开发环境，集脚本编辑、游戏编译、APK 打包与 AI 编程助手于一体。
> 本次更新新增界面国际化（i18n）：支持简体中文 / 繁體中文 / English / 日本語 / Русский 及跟随系统，切换即时生效，并支持导入自定义翻译包。详见 [I18N_GUIDE.md](I18N_GUIDE.md)。

## 一、编辑器

- **脚本编辑器**：支持 Ren'Py 脚本编辑与语法处理，配备文本选择菜单与代码模糊显示
- **文件树管理**：
  - 支持项目私有目录、公共目录与自定义路径
  - 路径可随时切换，也能一键切回原路径
  - AI 助手入口集成在文件树底部
- **布局助手**：可视化辅助布局工具
- **缓存机制**：编译与资源缓存，显著提升二次构建速度

## 二、编译与运行

- **原生桥接（native bridge）**：通过 `librenpython.so` 在设备端直接运行 Ren'Py 脚本
- **游戏编译**：完整的编译进度反馈，支持中途取消
- **错误诊断**：编译错误日志捕获与展示，便于定位问题

## 三、APK 打包

- **RAPT 打包**：一键将 Ren'Py 游戏打包为 Android APK
- **SDK 管理**：Android SDK 下载、安装与路径配置
- **构建工具链**：Gradle、build-tools、keystore 签名密钥的完整配置与管理
- **构建日志**：实时日志查看与一键复制
- **APK 定制**：自定义启动图、应用图标、应用名称与版本号

## 四、AI 助手

### 大模型支持
- **多协议兼容**：OpenAI、Claude、Gemini 三种 API 格式
- **多服务商档案**：每个服务商独立配置，token 互不干扰
- **模型列表与余额**：自动获取可用模型列表与账户余额
- **流式输出**：SSE 实时响应，思考链（reasoning）与正文分离展示
- **客户端标识开关**：可自定义 User-Agent，内置常用白名单预设

### MCP 工具调用
- **内置 Python 运行时**：无需外部依赖即可执行 AI 相关脚本
- **renpy-mcp 移植**：80+ 工具覆盖 Ren'Py 开发场景
- **渐进式加载**：工具按 core / read / edit / scenario 等分组按需加载
- **上下文压缩**：超长对话自动抽取式压缩，避免超限
- **智能约束**：自动过滤设备端不可用的工具

### 对话体验
- **思考/正文去重**：交叉去重与消息级相似度去重，杜绝重复内容
- **聊天历史**：搜索、上下跳转、排序、预览与删除确认
- **富文本交互**：文字选择、引用回复、Markdown 渲染、字号调节
- **交互卡片**：`ask_user` 询问卡片，支持多轮对话
- **面板常驻**：文件树与 AI 面板常驻切换，动画流畅不卡顿

## 五、界面与交互

- **Material 3 Expressive**：全新 MD3 表达性设计语言
- **品牌标识**：代码绘制的六芒星图标，应用于桌面图标、引导页与主界面；白底 + 深空蓝渐变 + 镂空设计
- **状态栏适配**：edge-to-edge 全屏，内容不与状态栏重叠
- **导航布局**：侧栏底部导航，AI 助手快速入口
- **动效与模糊**：面板切换动画、代码模糊遮罩、完成弹窗
- **统一设置项**：所有设置采用一致的样式规范

## 六、界面语言与翻译（本次更新）

- **6 种界面语言**：跟随系统 / 简体中文 / 繁體中文 / English / 日本語 / Русский，切换即时生效无需重启
- **繁简转换**：基于 opencc 的简繁转换，繁体覆盖率约 97%
- **内置译文**：英文覆盖约 82%，日语/俄语覆盖约 47%，未命中项原样显示简体中文
- **自定义翻译包**：导入/导出 JSON 翻译包，可按 `code` 覆盖内置译文，用户包保存在 App 私有目录
- **开发者友好**：用 `t("中文原文")` 包裹即可纳入翻译，支持 `{0}` 占位符参数

详细用法见 [I18N_GUIDE.md](I18N_GUIDE.md)。

## 七、设置与更新

- **关于页面**：版本信息与产品介绍
- **检查更新**：自动请求更新接口，Markdown 格式公告，版本对比，支持跳转浏览器下载
- **QQ 交流群**：内置加群入口

## 下载

| 类型 | 地址 |
| --- | --- |
| APK | https://yuju.99kpk.top:81/pan/1045/RenPyMobile-apk.zip |
| 源码 | https://yuju.99kpk.top:81/pan/1045/RenPyMobile-source.zip |

---

## 构建

使用 Android Studio 或 Gradle 直接构建：

```bash
./gradlew :app:assembleDebug
```

> 说明：本项目使用 Gradle 9.9.2 + Android Gradle Plugin。首次构建需联网下载 Compose BOM 与依赖。
> `local.properties` 中的 `sdk.dir` 请指向你本地的 Android SDK 路径（该文件已加入 `.gitignore`，不会被提交）。

## 目录结构

```
app/src/main/java/com/renpy/mobile/
├── MainActivity.kt          # 主入口与导航
├── ai/                      # AI 配置、LLM 客户端、AgentLoop、MCP
├── ui/                      # Compose 界面（编辑器、设置、AI 助手等）
├── project/                 # 项目管理
├── installer/               # SDK 下载与 APK 打包
└── ...
termux-libs/                 # 内置 Termux 终端与原生桥接
app/src/main/assets/aimcp/   # 内置 Python + renpy-mcp 工具
```

## 许可

本项目开源免费。第三方组件（Termux、renpy-mcp、Ren'Py 等）各自遵循其原始许可证。
