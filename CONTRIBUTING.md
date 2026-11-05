# 参与开发

感谢你愿意帮忙改进 PocketAgent。提交改动前请先读一遍这份说明。

## 环境

- JDK 17
- Android SDK 35（`ANDROID_HOME` 指向 SDK 目录）
- 不需要 Android Studio：命令行 Gradle 就能跑完测试与打包

## 构建与测试

```bash
./gradlew :app:testDebugUnitTest    # JVM / Robolectric 测试，不需要真机
./gradlew :app:assembleRelease      # 打 release APK（需要签名配置，见 app/build.gradle.kts）
```

改动涉及网络、同步、工具调用时，请补对应的单元测试；只改 UI 文案也请至少保证 `testDebugUnitTest` 全绿。

## 提交改动

1. 一个 PR 只做一件事，说明「为什么改」而不只是「改了什么」。
2. 提交信息用 `feat:` / `fix:` / `docs:` / `test:` / `chore:` 前缀。
3. **不要提交**任何真实 API Key、SSH 私钥、内网主机名、IP、个人截图或体检/合同类文档。
   测试里需要示例值时用 `example.com`、`192.168.1.10`、`sk-test` 这类占位符。
4. 新增第三方依赖时同步更新 `NOTICE`，并在 PR 里说明许可证。

## 安全

发现漏洞请按 [SECURITY.md](SECURITY.md) 私下报告，不要开公开 issue。

## 许可证

本项目使用 [PolyForm Noncommercial 1.0.0](LICENSE)（源码公开，非商业使用免费）。
提交 PR 即表示同意你的贡献以同一许可证发布。
