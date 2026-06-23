# GameOcr · Android 屏幕实时翻译

通过 MediaProjection / Shizuku 截屏 → 端侧或云端 OCR → LLM / 机器翻译 → 悬浮窗叠加显示。无需 ROOT，单机可用，面向视觉小说、漫画、游戏对话等任意屏上文字的实时翻译。

> 桌面端思路参考 [baoxin1100/gameocr](https://github.com/baoxin1100/gameocr) / VNR 类工具，移植到 Android 并在 OCR 与翻译管线上做了端云混合扩展。

## 功能

- **截屏**：MediaProjection + ImageReader（前台服务 `mediaProjection` 类型 / Android 14+ 兼容）；可选 Shizuku 路径免每次系统授权弹窗
- **触发**：悬浮按钮单击触发一次，长按切循环模式（默认 1 秒一次，dHash 帧差跳过静止画面）；可选无障碍服务接管音量键作为全局触发
- **区域选择**：全屏拉框，记忆上次区域，避免 OCR 全屏背景噪声
- **OCR 引擎**（路由式，按设置切换）：
  - ML Kit 端侧（拉丁 / 日 / 中），AUTO 模式按假名命中切换
  - PaddleOCR PP-OCRv5 mobile（ONNX Runtime，端侧多语种）
  - 云端兜底：百度 OCR / 腾讯 OCR
- **翻译**（路由式）：
  - OpenAI 兼容 chat completions（默认 DeepSeek，可改任意 base URL：SiliconFlow / 智谱 / Ollama / OpenAI ……）
  - DeepL
- **叠加显示**：整屏底部条 / 按 boundingBox 紧贴原文渲染；LRU 缓存命中跳过翻译 token 消耗

## 安装

1. 到本仓库的 [Releases](../../releases) 页下载最新 `GameOcr-x.y.z.apk`
2. 在 Android 设备上点击安装（首次需在系统设置允许"安装未知来源应用"）
3. 启动后依次授予 **悬浮窗**、**通知** 权限

只发布 `arm64-v8a` 架构。每个 APK 同时附带 `.sha256` 校验文件，可对照本地 `Get-FileHash` / `sha256sum` 输出确认下载完整性。

## 使用

1. 启动 App，点 **启动截屏服务**，确认系统弹出的"开始截屏？"对话框
2. 切到任意游戏 / 视觉小说 / 漫画 App
3. 点屏幕上的圆形悬浮按钮 → 2~3 秒内底部出现译文
4. 长按悬浮按钮 → 切换循环模式（默认 1 秒一次，dHash 跳过静止画面）
5. 单击译文条 → 隐藏

可选：
- 在系统"无障碍"里启用本应用，用音量键作为全局触发，免去手指点屏
- 安装 [Shizuku](https://github.com/RikkaApps/Shizuku) 并授权后，在设置切换到 Shizuku 截屏路径，免去每次的系统授权弹窗

## 配置

启动 App 后进入"设置"。

### OCR 引擎

| 引擎 | 适用场景 | 备注 |
|---|---|---|
| ML Kit (auto / latin / ja / zh) | 默认；日文 / 中文 / 拉丁字符 | 无需外网，端侧推理 |
| PaddleOCR PP-OCRv5 mobile | 多语种密排文字、UI 按钮 | 首次使用需下载 ONNX 模型，见下 |
| 百度 OCR | ML Kit / Paddle 漏检兜底 | 需 API Key，按量计费 |
| 腾讯 OCR | 同上 | 需 SecretId/SecretKey |

**PaddleOCR 模型下载**：设置 → "下载 PaddleOCR 模型"，自动从 HuggingFace / hf-mirror 镜像拉取以下三个文件到 `<filesDir>/models/paddle/`：

- `det.onnx`（DBNet 检测，约 4.5 MB）
- `rec.onnx`（CRNN 识别，约 15.7 MB）
- `keys.txt`（v5 字典，约 90 KB）

可在设置里自定义镜像 URL，或手动从本地文件导入。

### 翻译引擎

**OpenAI 兼容**：
- **Base URL**：以 `/v1/` 结尾，例如：
  - SiliconFlow `https://api.siliconflow.cn/v1/`
  - OpenAI 官方 `https://api.openai.com/v1/`
  - 智谱 BigModel `https://open.bigmodel.cn/api/paas/v4/`
  - 自架 Ollama `http://<host>:11434/v1/`
- **API Key**：对应平台的 sk-xxx
- **模型名**（示例）：
  - SiliconFlow: `Qwen/Qwen2.5-7B-Instruct`、`Qwen/Qwen2.5-14B-Instruct`
  - OpenAI: `gpt-4o-mini`、`gpt-4o`
  - 智谱: `glm-4-flash`、`glm-4-plus`
  - Ollama: `qwen2.5:7b`、`llama3.1:8b`
- **Prompt 模板**：默认 galgame 口语化风格，可自定义

**DeepL**：填 Auth Key（free 版 key 末尾带 `:fx`），自动选择 free / pro endpoint。

## 已知限制

- Android 14+ 每次首次启动截屏都会弹一次系统授权窗，这是 Google 设计；Shizuku 路径可绕过
- 部分 ROM（小米 / OPPO / VIVO）默认杀后台 / 拦截悬浮窗，需手动加入电池白名单 + 允许后台启动；应用内有兼容引导
- 反作弊网游可能把 MediaProjection 判为外挂截屏 → 本项目仅适用于单机 / 视觉小说 / 漫画
- 设置了 `FLAG_SECURE` 的画面（部分网银 / 视频 App）截出来是黑屏，本项目不做绕过
- PaddleOCR 端侧推理在低端机（骁龙 7 系以下）单次约 1~3 秒；推荐配合区域选择使用

## 路线图

- **M0（当前）**：MediaProjection 截屏 + ML Kit + PaddleOCR + OpenAI 兼容翻译 + 悬浮按钮 + 底部译文条
- **M1**：区域选择持久化、SSE 流式译文、按 boundingBox 紧贴原文渲染、ROM 兼容引导完善
- **M2**：Shizuku 高级路径完善（全局快捷键 + 免授权弹窗）、云 OCR 兜底链路、NCNN 竖排日文
- **M3**：多翻译引擎对比、对话历史、TTS、术语表

## 致谢

- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) · PP-OCRv5 mobile 模型
- [bukuroo/PPOCRv5-ONNX](https://huggingface.co/bukuroo/PPOCRv5-ONNX) · 已转 ONNX 的 v5 mobile 镜像
- [Shizuku](https://github.com/RikkaApps/Shizuku) · 免 ROOT 的高权限通道
- [ML Kit](https://developers.google.com/ml-kit) · Google 端侧 OCR

## 许可

代码采用 Apache-2.0。模型与第三方依赖各自保留原协议。

---

# 开发者

下面是开发者构建、调试与发版相关的内容；普通用户使用 [Releases](../../releases) 中的 APK 即可，不需要阅读这一部分。

## 技术栈

- Kotlin 2.x · Jetpack Compose · Hilt
- Android `minSdk 26` / `targetSdk 35`（Android 8.0+ 可运行，Android 10+ 体验最好）
- Retrofit + OkHttp + kotlinx.serialization
- DataStore（配置）+ Room（缓存）
- ONNX Runtime Android（PaddleOCR 端侧推理）
- ML Kit on-device text recognition
- Shizuku API

## 工程结构

```
app/src/main/java/com/gameocr/app/
  capture/    Screenshotter 接口 + MediaProjection / Shizuku 实现 + 区域选择 + 帧差
  ocr/        OcrEngine 接口 + ML Kit / PaddleOCR / 百度 / 腾讯 + RoutingOcrEngine
  translate/  Translator 接口 + OpenAI / DeepL + LRU 缓存 + RoutingTranslator
  overlay/    悬浮按钮 + 译文叠加渲染
  service/    CaptureService 前台服务（截屏 → OCR → 翻译 → 渲染 主控）
  trigger/    无障碍服务（音量键触发器，可选）
  shizuku/    Shizuku 权限与 IBinder 桥接
  rom/        小米 / OPPO / VIVO 等厂商 ROM 兼容引导
  data/       Settings 模型 + DataStore Repository
  di/         Hilt 模块
  ui/         MainActivity + MainScreen + SettingsScreen + ViewModel + Compose 主题

tools/local_ocr_debug/  PC 端复现 PaddleOCR 流水线的 Python 脚本（见"开发与调试"）
.github/workflows/      CI：push tag 自动发版到 Releases
```

## 构建

### 准备

- Android Studio Ladybug (2024.2) 或更新；JDK 17（Android Studio 自带）
- Android SDK 35（compileSdk）+ Build-Tools 34+
- 设备 / 模拟器：Android 8.0 (API 26) 起

### 克隆与配置

```bash
git clone https://github.com/<your-account>/GameOcr.git
cd GameOcr
cp local.properties.example local.properties
# 编辑 local.properties，把 sdk.dir 改成本机 Android SDK 路径
```

### 补齐 Gradle Wrapper jar

仓库未提交 `gradle/wrapper/gradle-wrapper.jar`（避免二进制入库），两种方式补：

- **方法 A（推荐）**：用 Android Studio 打开项目根目录，IDE 会自动下载 wrapper 并 sync
- **方法 B**：已装 Gradle 8.10+ 的环境下，根目录执行 `gradle wrapper --gradle-version 8.10.2`

### 编译

```bash
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # 直接安装到已连接设备
```

只支持 `arm64-v8a`（在 `app/build.gradle.kts` 的 `ndk.abiFilters` 配置；为控制 APK 体积，不打包 32 位与 x86）。

## 开发与调试

### PaddleOCR 本地调参

`tools/local_ocr_debug/` 提供 Python 脚本，可在 PC 上完整复现 Android 端 PaddleOCR 流水线，用于离线调参与回归对比：

```bash
# 1. 从设备拉出已安装的 ONNX 模型
adb exec-out "run-as com.gameocr.app.debug cat files/models/paddle/det.onnx" > tools/local_ocr_debug/models/det.onnx
adb exec-out "run-as com.gameocr.app.debug cat files/models/paddle/rec.onnx" > tools/local_ocr_debug/models/rec.onnx
adb exec-out "run-as com.gameocr.app.debug cat files/models/paddle/keys.txt" > tools/local_ocr_debug/models/keys.txt

# 2. 抓一张待测截图
adb exec-out screencap -p > sample.png

# 3. 用与 Android 端 1:1 等价的算法跑一遍
pip install onnxruntime pillow numpy opencv-python-headless pyclipper shapely
python tools/local_ocr_debug/run_v3_kotlin_equiv.py sample.png
```

输出 `sample.png.v3.png` 是带框可视化，控制台逐行打印每个检测框的位置、平均得分与识别文本。

仓库内还有 `run_v2.py`（PaddleOCR 官方做法对照基线，依赖 cv2 + pyclipper）用于评估改动收益。

## 发版

打 tag → 自动构建 → 上传到 GitHub Releases。`.github/workflows/release.yml` 在 `push tag v*` 时触发，跑 `assembleRelease` 并把签名 APK + sha256 发到对应 Release。

### 一次性：配置签名 keystore 与 GitHub Secrets

```bash
# 本地生成发布 keystore（密码、alias 自定，妥善保存）
keytool -genkeypair -v \
  -keystore release.jks \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -alias gameocr \
  -storepass <STORE_PASSWORD> -keypass <KEY_PASSWORD> \
  -dname "CN=GameOcr, OU=, O=, L=, ST=, C=CN"

# 转 base64 用于 GitHub Secrets（macOS/Linux 用 base64 -w0 release.jks）
certutil -encode release.jks release.jks.b64   # Windows
# 把 release.jks.b64 中 -----BEGIN/END----- 之间的内容（多行无所谓）复制走
```

在仓库 **Settings → Secrets and variables → Actions** 新增 4 个 secret：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | 上一步生成的 base64 字符串 |
| `RELEASE_KEYSTORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 上面 `-alias` 后的值，例如 `gameocr` |
| `RELEASE_KEY_PASSWORD` | key 密码（与 keystore 密码可相同） |

> ⚠ **不要把 `release.jks` 提交进仓库**，建议在仓库 `.gitignore` 中确认 `*.jks`、`*.keystore` 已忽略。

### 发版流程

```bash
# 1. 在主分支上把版本号过一遍（app/build.gradle.kts 的 versionName / versionCode）
# 2. 打 tag 并推上去
git tag v0.2.0
git push origin v0.2.0

# 3. 在仓库 Actions 页面观察 Release workflow 跑完
#    成功后到 Releases 页就能看到 GameOcr-0.2.0.apk 和 .sha256
```

CI 失败时常见原因：

- **Secret 未配齐**：workflow 会在第一步明确报 `Secret RELEASE_KEYSTORE_BASE64 is not set`
- **keystore 解码失败**：base64 多了换行或缺字符；用 `base64 -d` 本地验证一遍再粘
- **签名密码错**：对照 keystore 生成时填的 `-storepass` / `-keypass`，注意 alias 大小写

本地也可以手动跑相同流程：

```bash
export RELEASE_KEYSTORE_PATH=/path/to/release.jks
export RELEASE_KEYSTORE_PASSWORD=<STORE_PASSWORD>
export RELEASE_KEY_ALIAS=gameocr
export RELEASE_KEY_PASSWORD=<KEY_PASSWORD>
./gradlew clean assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

未设置 `RELEASE_KEYSTORE_PATH` 时 `assembleRelease` 不会失败，但产物是未签名 APK，无法直接安装；正常本地开发只跑 `assembleDebug` 即可。
