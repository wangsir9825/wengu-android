# 温故 · Wengu

把生活和学习中的经验精简成一句话，放进手机壁纸，让过去的总结多几次被看见和回想的机会。

**当前版本：0.3.0（个人试用版）**，支持 Android 8.0 / API 26 及以上。

- [百度网盘下载（最新版，提取码 pzu7）](https://pan.baidu.com/s/1-ZV3McrNwXdkg2V2Bq14pg?pwd=pzu7)
- [GitHub 下载 0.3.0 APK](https://github.com/wangsir9825/wengu-android/releases/download/v0.3.0/wengu-v0.3.0-android.apk)
- [更新说明与历史发布](https://github.com/wangsir9825/wengu-android/releases)
- [作品介绍与制作记录](https://www.zhutufang.cn/articles/wengu-experience-wallpaper/)
- [筑途坊作品索引](https://github.com/wangsir9825/zhutufang-works)
- [安装与使用说明](USER_GUIDE.txt)

## 功能

- 记录一句话经验及背后的经历，管理重要程度、私密、暂停和归档状态。
- 在锁屏、桌面或两者显示经验，提供预览、背景选择和手动轮换。
- 按 1、3、7、14、30、60 天的展示策略安排经验再次出现。
- 使用自己选择的本地文件夹备份经验、设置和照片，并支持恢复历史备份。
- 无需账户，没有联网权限，可离线使用。

## 安装与升级

下载 APK，在 Android 手机的文件管理器中打开，按系统提示允许此次安装来源后安装。已有温故发布版请覆盖更新，更新前完成一次本地备份；不要先卸载。

0.3.0 与已有 0.1.0、0.1.1、0.2.0 交付包的发布签名一致。0.2.0 → 0.3.0 已做覆盖升级验证。

## 构建源码

环境：JDK 17、Android SDK Platform 35、Build Tools 35.0.0。Gradle Wrapper 固定为 8.13，Android Gradle Plugin 为 8.13.2。

安装 Android SDK 后，用 `ANDROID_HOME` 环境变量指定 SDK，或在本地 `local.properties` 填写 `sdk.dir`。该文件不提交到 Git。

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

macOS / Linux：

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。也可在 Windows 使用 `.\build.ps1 -Test -Lint`。

发布者的签名私钥不包含在源码中。自行构建签名发布版时，使用自己的密钥库 `.tools/wengu.jks`（别名 `wengu`），参考 `signing.properties.example` 配置 `.tools/signing.properties`，再执行 `assembleRelease`。自行签名的包不能覆盖已有的原签名发布版。

## 目录

```text
app/src/main/java/com/wengu/app/   应用实现
app/src/main/res/                 界面与资源
app/src/main/assets/wallpapers/   内置壁纸与设计说明
app/src/test/                    核心与 Robolectric 测试
app/src/androidTest/             专用设备流程测试
```

设备流程测试会重置应用数据、创建测试备份和修改壁纸，只在专用测试设备上运行。

## 验证与使用边界

交付版本已有 24 项核心测试、6 项专用 Android 设备流程及覆盖升级验证记录。公开源码的构建检查另见本次版本发布说明。

WorkManager 的执行时间受系统后台限制影响，计划时间不保证准时；小米真机长期后台表现仍需实际使用观察。经验展示策略不测量记忆效果。本地备份需要确认完成，公共 ZIP 无密码，也没有云同步。

## 反馈与许可

欢迎通过 Issues 提交设备型号、Android 版本、应用版本和复现步骤。源码现已公开，当前未指定开放授权许可证。
