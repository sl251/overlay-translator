# 屏译 (PingYi) · Android 屏幕实时翻译

灵感来自桌面端 [baoxin1100/gameocr](https://github.com/baoxin1100/gameocr)。无需 ROOT，通过 MediaProjection / Shizuku 截屏 → 端侧或云端 OCR → LLM / DeepL 翻译 → 悬浮窗叠加显示，适用于游戏 / 视觉小说 / 漫画 / 任意屏上文字的实时翻译。

> 完整设计文档见 `..\Administrator\.claude\plans\https-github-com-baoxin1100-gameocr-vnr-quizzical-aho.md`。

## M0 当前实现

- MediaProjection + ImageReader 截屏，前台服务承载（Android 14+ `FOREGROUND_SERVICE_MEDIA_PROJECTION` 类型）
- 悬浮按钮触发器，单击触发一次，长按切换循环模式
- 帧差跳过（dHash）
- ML Kit on-device OCR：日 / 中 / 拉丁三个识别器，AUTO 模式按假名命中切换
- OpenAI 兼容 chat completions 翻译（默认 DeepSeek，可改任意 base URL）
- 译文整屏底部条显示，LRU 缓存命中跳过 token 消耗
- DataStore 持久化配置，Hilt DI

## 工程结构

```
app/src/main/java/com/gameocr/app/
  capture/          # Screenshotter 接口 + MediaProjection 实现 + FrameDeduper
  ocr/              # OcrEngine + MlKitOcrEngine（latin / ja / zh）
  translate/        # Translator + OpenAiTranslator + LRU 缓存
  overlay/          # FloatingButton + Overlay 整屏 / 按 boundingBox 显示
  service/          # CaptureService（前台服务主控）+ 通知
  data/             # Settings 模型 + DataStore Repository
  di/               # Hilt 模块
  ui/               # MainActivity + MainScreen + SettingsScreen + ViewModel + Theme
```

## 首次构建

### 1. 准备环境
- Android Studio Ladybug (2024.2) 或更新；JDK 17（AS 自带）
- Android SDK 35（compileSdk）+ Build-Tools 34+
- 设备 / 模拟器：Android 8.0 (API 26)+（Android 10+ 体验最好）

### 2. 拉项目
```
git clone <repo> GameOcr
cd GameOcr
cp local.properties.example local.properties
# 编辑 local.properties，把 sdk.dir 改成你机器上的 Android SDK 路径
```

### 3. 补 Gradle Wrapper jar
仓库里只提供了 `gradle/wrapper/gradle-wrapper.properties` 和 `gradlew[.bat]` 脚本，**没有**提交 `gradle-wrapper.jar`（二进制不便随项目分发）。补 jar 的两种方法：

- **方法 A（推荐）**：直接用 Android Studio 打开本项目根目录，AS 会自动下载 wrapper jar 并 sync。
- **方法 B**：在已装 Gradle 8.10+ 的环境下，根目录执行 `gradle wrapper --gradle-version 8.10.2`。

### 4. 配置 OpenAI 兼容翻译
启动 App → 主屏 → 翻译设置：
- **Base URL**：填带 `/v1/` 结尾的接口，比如：
  - SiliconFlow `https://api.siliconflow.cn/v1/`
  - OpenAI 官方 `https://api.openai.com/v1/`
  - 智谱 BigModel `https://open.bigmodel.cn/api/paas/v4/`
  - 自架 Ollama `http://你的IP:11434/v1/`
  - 其它任意 OpenAI 兼容端点都行
- **API Key**：对应平台的 sk-xxx
- **模型名**（按平台填，举例）：
  - SiliconFlow：`Qwen/Qwen2.5-7B-Instruct`、`Qwen/Qwen2.5-14B-Instruct`
  - OpenAI：`gpt-4o-mini`、`gpt-4o`
  - 智谱：`glm-4-flash`、`glm-4-plus`
  - Ollama：`qwen2.5:7b`、`llama3.1:8b`
- **Prompt 模板**：默认 galgame 口语化，可改

### 5. 首次运行
1. 装机后启动 App
2. 点"请求悬浮窗权限"→ 系统设置里允许悬浮窗
3. 点"启动截屏服务"→ 系统弹"开始截屏？"→ 立即开始
4. 切到任意游戏/视觉小说 → 点屏幕上的圆形按钮 → 2~3 秒内底部出译文条
5. 长按圆形按钮 → 切换"循环模式"（默认 1 秒一次，帧差跳静止画面）
6. 单击译文条 → 隐藏

## 路线图

- **M1**：区域选择（拉框 + 记忆）、SSE 流式译文、按 boundingBox 紧贴原文渲染、ROM 兼容引导
- **M2**：Shizuku 高级路径（免每次系统弹窗 + 全局快捷键）、云端 OCR（百度 / 腾讯）兜底、ChOcrLite NCNN 竖排日文
- **M3**：多翻译引擎（腾讯 / 百度 / Google）、对话历史、TTS

## 注意事项

- Android 14+ 每次启动截屏都会弹一次系统授权窗，这是 Google 设计，M2 上的 Shizuku 路径能免弹窗
- 部分 ROM（小米 / OPPO / VIVO）会杀后台 / 拦截悬浮窗，需加入电池白名单 + 允许后台启动
- 反作弊网游可能把 MediaProjection 判为外挂截屏 → 本项目仅适用于单机 / 视觉小说 / 漫画
- 设置了 `android:protectionLevel="FLAG_SECURE"` 的画面（部分网银、视频 App）截出来是黑屏，本项目不做绕过

## 许可

代码部分 Apache-2.0；模型 / 第三方依赖各自原协议。
