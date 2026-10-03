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

1. 首次打开填写 Go 服务地址。局域网填写电脑或服务器的 IP，例如 `http://192.168.1.10:56507`，线上建议 `https://你的域名`。地址不含 `/api/v1`。
2. 输入邮箱并发送验证码，登录状态通过 Android Keystore 加密保存在手机。此邮箱用于接收提醒。
3. 输入自然语言生成可编辑草稿，或直接手动创建。支持一次、每日、每周、每月范围、日期范围、固定天数间隔和「每天提醒直到完成」。
4. 任务详情可完成、附备注、调用系统相机、选择相册、延后或归档。首页和详情都有提醒开关，开启是蓝色、暂停是灰色。完成凭证的照片会压缩为 JPEG 上传到自己的服务器。
5. 记录页用月历查看未来安排、邮件提醒与完成记录。蓝色表示待提醒或已提醒、绿色表示已完成、黄色表示到点未完成、红色表示周期结束仍未完成。点日期看当天安排，再点记录查看文字、照片，点未来安排进入提醒详情。我的页面可以切换服务器、检查连接、导出文字记录、退出登录。完整备份需要同时保存服务器媒体目录。

切换服务器会清理当前账号和页面缓存；旧请求的结果不会进入新服务器的界面。服务器地址、证书或网络出错时展示可重试的错误。

## 1.2.0 日历安排与提醒开关

保留白蓝配色，缩小字号和留白，首页改为紧凑提醒列表。AI 草稿直接显示事项、规则摘要和保存按钮；具体规则与更多设置按需展开。开始/结束日期及每月起止日成对排列，窄屏和大字体自动换行。提醒时间通过时间按钮选择、修改或移除。

月历通过 `GET /api/v1/calendar?month=YYYY-MM&timezone=手机时区` 获取当月未来安排和已有记录，周期状态由服务器计算。进入记录页、切换月份和刷新时重新读取，选择日期只更新本地列表。月份与请求序号共同隔离旧响应，账号切换会清理所有日历缓存。失败不会把其他月份的数据当作当前月；同月旧数据会注明可能已过时。旧服务器明确提示更新后端，结果截断会提示仅展示部分安排。

提醒开关使用局部接口 `PUT /api/v1/tasks/{id}/paused`，只提交 `paused`，不覆盖规则。保存中禁用开关，失败恢复原状态并提示；手机列表的开关与规则摘要同行。详情中的“查看日历安排”会定位下一次提醒所在日期，没有下一次提醒时回到今天。

每日超过 20 条分页显示，照片进入详情后加载。返回详情/照片保留选定月份、日期和页码；未来安排进入任务详情后也返回原日历。已归档提醒只读。邮件发送失败单独显示，不当成任务过期。

日期归组的独立测试（在 `android` 目录运行，需要 JDK 17+）：

```powershell
New-Item -ItemType Directory -Force build/record-dates-tests | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 --release 17 -d build/record-dates-tests app/src/main/java/dev/niannian/reminder/RecordDates.java tests/RecordDatesTest.java
& "$env:JAVA_HOME/bin/java.exe" -cp build/record-dates-tests dev.niannian.reminder.RecordDatesTest
```

覆盖午夜跨日、时区偏移、夏令时、无效时间、排序及月历边界，共 52 项断言。

月份请求隔离测试（7 项断言，覆盖乱序月份、重复刷新、手机时区和账号清理）：

```powershell
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 --release 17 -d build/record-dates-tests app/src/main/java/dev/niannian/reminder/CalendarRequestGuard.java tests/CalendarRequestGuardTest.java
& "$env:JAVA_HOME/bin/java.exe" -cp build/record-dates-tests dev.niannian.reminder.CalendarRequestGuardTest
```

## 本次验证边界

已构建并签名正式 APK，执行 Android Lint。没有给真实邮箱发送测试邮件，没有在手机上执行视觉验收。

请在实际手机快速验收：白蓝界面与键盘显示、服务器切换、验证码登录、AI 草稿编辑、完成后停止本期提醒、相机与相册上传、记录中的照片。相机由设备系统应用提供，需要有可用相机应用。
