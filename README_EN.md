# Ren'PyndIDE

> A Ren'Py IDE for Android — edit scripts, compile games, package APKs and use an AI assistant, all on your phone. Open source and free.

## Editor
- Script editing: Ren'Py syntax handling, text selection menu, code blur
- File tree: private / public / custom paths, switch anytime, one-click restore
- Layout assistant: visual layout aids
- Cache: compile and resource caching for faster rebuilds

## Compile & Run
- Native bridge: run Ren'Py scripts directly on the device
- Game compilation: full progress feedback, cancellable
- Error diagnostics: capture and display compile logs

## APK Packaging
- One-click RAPT packaging into an Android APK
- SDK download, installation and path configuration
- Full management of Gradle / build-tools / signing keys
- Real-time build logs with one-tap copy
- Custom splash screen, icon, app name and version

## AI Assistant
- Multi-protocol: OpenAI / Claude / Gemini, with isolated provider profiles
- Model list and balance, streaming output, reasoning separated from the answer
- Built-in Python runtime with 80+ ported renpy-mcp tools
- Progressive tool loading, automatic context compression, filtering of unavailable tools
- Reasoning/answer deduplication, chat history search, Markdown rendering, font size control
- ask_user interactive cards with multi-turn dialogue

## Multilingual UI (this update)
- 6 languages: Follow system / Simplified Chinese / Traditional Chinese / English / 日本語 / Русский, instant switching
- Built-in translations: English ~82%, Traditional Chinese ~97%, Japanese and Russian ~47%; untranslated strings fall back to Simplified Chinese
- Custom translation packs: import / export JSON and override built-in translations
- Wrap any string with `t("中文原文")` to make it translatable; supports `{0}` placeholders

See [I18N_GUIDE.md](I18N_GUIDE.md) (Chinese) for details.

## 中文

参见 [README.md](README.md)。
