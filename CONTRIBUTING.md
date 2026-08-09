# 贡献指南 / Contributing

## 中文

感谢参与。提交前请先搜索现有 issue，并确保工作仅针对自有或获授权设备，不要提交用于绕过访问控制、窃取数据或隐藏恶意行为的功能。

1. 从 `main` 建立短分支；一个提交/PR 聚焦一个问题。
2. 使用 JDK 17 和仓库 Gradle Wrapper，不要提交 `local.properties`、密钥库、签名密码、APK 或用户数据。
3. 为行为变化先添加测试，运行：

   ```powershell
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

4. 涉及协议、opcode、路径或安全边界时，同步更新 `docs/`。
5. PR 说明需包含动机、验证命令、风险与界面变化；不要附真实设备 IP、TID/BID 私有记录或凭据。

发布版本由 `app/build.gradle.kts` 的唯一 `versionName` 决定。标签必须严格为 `v<versionName>`。发布工作流要求仓库 secrets `ANDROID_SIGNING_KEY_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；它只在人工 `workflow_dispatch` 确认时创建草稿 Release，不自动公开发布。

## English

Search existing issues first. Changes must target owned or authorized devices and must not add access-control bypass, data theft, or covert behavior.

Use JDK 17 and the Gradle Wrapper, add tests first, run `./gradlew testDebugUnitTest lintDebug assembleDebug`, and update protocol/security documentation with behavior changes. Never commit `local.properties`, signing material, passwords, APKs, device identifiers, or user data. Pull requests should state motivation, verification, risks, and UI impact.

Release tags must equal `v<versionName>`. Signing material is supplied only through the documented GitHub secrets; public release remains a separate manual decision.
