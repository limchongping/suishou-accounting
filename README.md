# AI朋友圈文案改写器 Android APK

本仓库沿用原有 Android WebView 启动壳，当前主分支构建目标为 AI 朋友圈文案改写工具。

## 功能

- 输入一段朋友圈原文，生成多条不同表达版本。
- 支持通用、商品分享、生活分享、营销推广、客户反馈、节日祝福等场景。
- 支持自然口语、真诚朴实、温暖亲切、轻松幽默、专业可信、简洁有力等风格。
- 可设置文案长度、生成数量、保留原文事实、简短自然和不出现价格金额。
- 兼容 DeepSeek、OpenAI、阿里云百炼兼容接口、硅基流动及自定义 OpenAI 兼容 API。
- Android 原生 HTTPS 网络桥接，不依赖 WebView CORS。

## 构建 APK

推送到 `main` 分支、创建针对 `main` 的 Pull Request，或在 GitHub Actions 中手动运行 **Build AI Moments Copywriter APK**，都会触发构建。

构建成功后，在对应 Actions 运行记录的 Artifacts 中下载 `AI-Moments-Copywriter-APK`，其中包含 `AI-Moments-Copywriter.apk`。

## 安装和使用

1. 下载 APK 并安装到 Android 手机。
2. 打开应用，选择 API 服务商。
3. 填写模型名称、API Base URL 和自己的 API Key。
4. 点击“测试连接”，成功后粘贴朋友圈原文并生成。

## 隐私和安全

- API Key 仅保存在当前页面运行期间的内存中，不写入 APK，也不写入浏览器本地存储。
- API 请求仅允许 HTTPS。
- API Key 由手机直接发送至用户选择的模型服务商。请勿在公共设备上输入密钥。
- 使用模型服务需要手机联网，并且对应服务商账户具有可用额度。
