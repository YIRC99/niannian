# 念念提醒 Android

原生 Java + Android Views，Android 8.0 及以上。仅使用 AndroidX Core 的 FileProvider，无 WebView，不需要手机后台常驻。应用内不包含 DeepSeek 或 SMTP 凭据。

## 构建

需要 JDK 17+、Android SDK 35。设置 `ANDROID_HOME`，或在未纳入版本控制的 `local.properties` 中填写 `sdk.dir=你的SDK路径`。

```powershell
$env:JAVA_HOME = 'D:\DevEnv\Java\jdk21'
.\gradlew.bat :app:assembleDebug --no-daemon
```

正式包使用项目目录中本地生成的 `niannian-release.jks`，不会加入 Git。默认本地签名密码为 `niannian-local-release`，可通过 `NIANNIAN_KEYSTORE_PASSWORD` 覆盖。

```powershell
keytool -genkeypair -keystore niannian-release.jks -storepass niannian-local-release -keypass niannian-local-release -alias niannian -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Niannian"
.\gradlew.bat :app:assembleRelease --no-daemon
```

请妥善保存签名文件，后续升级必须使用同一个签名。产物路径：`app/build/outputs/apk/release/app-release.apk`。

## 使用

1. 首次打开填写 Go 服务地址。局域网填写电脑或服务器的 IP，例如 `http://192.168.1.10:8080`，线上建议 `https://你的域名`。地址不含 `/api/v1`。
2. 输入邮箱并发送验证码，登录状态通过 Android Keystore 加密保存在手机。此邮箱用于接收提醒。
3. 输入自然语言生成可编辑草稿，或直接手动创建。支持一次、每日、每周、每月范围、日期范围、固定天数间隔和「每天提醒直到完成」。
4. 任务详情可完成、附备注、调用系统相机、选择相册、延后、暂停或归档。完成凭证的照片会压缩为 JPEG 上传到自己的服务器。
5. 记录页查看完成文字、照片和邮件发送结果。我的页面可以切换服务器、检查连接、导出文字记录、退出登录。完整备份需要同时保存服务器媒体目录。

切换服务器会清理当前账号和页面缓存；旧请求的结果不会进入新服务器的界面。服务器地址、证书或网络出错时展示可重试的错误。

## 本次验证边界

已构建并签名正式 APK，执行 Android Lint。没有给真实邮箱发送测试邮件，没有在手机上执行视觉验收。

请在实际手机快速验收：白蓝界面与键盘显示、服务器切换、验证码登录、AI 草稿编辑、完成后停止本期提醒、相机与相册上传、记录中的照片。相机由设备系统应用提供，需要有可用相机应用。
