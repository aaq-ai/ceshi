# NotificationDemo

基于 WebView 封装的 Android 应用，加载指定网页并支持通知弹窗功能。

## 功能

- WebView 全屏加载网页（支持 JavaScript、DOM Storage、地理位置等）
- 通知渠道管理（NotificationChannel，高优先级弹窗）
- Android 13+ 运行时通知权限适配（POST_NOTIFICATIONS）
- 小米 HyperOS / MIUI 专属通知设置跳转
- 点击通知跳转到指定 Activity
- 支持网页内文件上传、JS 弹窗、返回键导航

## 目标网页

`https://app-c0d3yeieus5d.appmiaoda.com/`

## 环境要求

- Android 16 (API 36)
- Xiaomi HyperOS 3.0
- minSdk: 24 (Android 7.0+)

## 构建

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (需签名)
./gradlew assembleRelease
```

## GitHub Actions 自动构建

推送代码到 `main` 分支后，GitHub Actions 会自动构建 Debug 和 Release APK。
构建完成后在仓库的 **Actions** 页面下载 APK 文件。

## 小米 HyperOS 通知权限设置

1. **设置** → **应用设置** → **应用管理** → 找到本应用
2. **通知管理**：
   - 开启「允许通知」
   - 对应渠道 → 开启「悬浮通知」和「锁屏通知」
   - 重要程度设为「紧急」
3. **权限管理**：
   - 开启「后台弹出界面」（悬浮窗权限）
   - 开启「锁屏显示」
4. **自启动管理**（HyperOS 3.0）：
   - 允许应用自启动

## 项目结构

```
app/src/main/
├── AndroidManifest.xml          # 权限声明
├── java/com/example/notificationdemo/
│   ├── MainActivity.kt          # WebView + 权限管理
│   ├── NotificationHelper.kt    # 通知工具类
│   └── SplashActivity.kt        # 启动页
└── res/
    ├── layout/
    │   ├── activity_main.xml     # WebView 布局
    │   └── activity_splash.xml   # 启动页布局
    ├── drawable/
    │   ├── ic_notification.xml   # 通知图标
    │   └── ic_launcher_foreground.xml
    ├── values/
    │   ├── strings.xml
    │   ├── colors.xml
    │   └── themes.xml
    └── xml/
        └── network_security_config.xml
```
