# 念念提醒 Android

原生 Java + Android Views，Android 8.0 及以上。仅使用 AndroidX Core 的 FileProvider，无 WebView，不需要手机后台常驻。应用内不包含 DeepSeek 或 SMTP 凭据。

界面开发先看 [UI 开发规划](../UI设计/开发规划.md)，再按 [图片索引](../UI设计/README.md) 打开对应设计稿；全部 24 张图片、页面编号和生成提示词均保存在项目根目录的 `UI设计/`。

## 1.8.5 日历空态插画

versionCode 18。无安排日期的书本幼芽从约 384px 小图采样改为 1536px 场景采样，显示高度 142dp → 114dp，缩小约 20%。不改动日历业务逻辑。

## 1.8.4 邮件记录固定布局

versionCode 17。第 35 页（邮件已发送）使用不滚动的固定布局，底部草丛独立作为背景，提示与按钮在前景，信封高度随剩余空间调整；右上树枝下移 31dp，当前 520dpi 手机约 101px。完成记录、照片与邮件异常页继续保留滚动。后端无需更新。

## 1.8.3 记录页装饰与清晰度

versionCode 16。记录和照片页的透明枝叶移到屏幕边缘的独立背景层，正文仍保持安全区；记录底部花草按原比例全宽显示。邮件主插画由约 384 像素小图采样改为 1536 像素场景采样，显示高度缩至 132dp。沿用原图，后端无需更新。实机观感由用户复看。

## 1.8.2 日历与记录

versionCode 15。按日历设计板补齐庭院横幅、月份胶囊、柔绿选中日期、四色标记、紧凑记录、每日分页、空态插画、加载占位及旧数据重试。完成和邮件记录使用枝叶装饰、状态与时间信息、备注及原任务入口；真实照片有加载与重试状态，缩略图重试成功后可继续查看大图。保持账号与月份隔离、原有返回路径、20 条分页和归档只读。后端无需更新。

## 1.8.1 助手式安排

versionCode 14。输入示例改为“等会儿提醒我搞卫生，时间你来安排”，不再要求用户先提供具体时间。后端 1.8.1 会主动补全模糊需求，以助手口吻在原有草稿提示中说明建议；点击确认才创建。没有新增草稿字段，1.8.0 客户端也兼容这项后端能力。

## 1.8.0 AI 安排与精确截止

客户端和后端需一起升级到 1.8.0。分钟催办草稿、摘要和调整规则支持精确截止 `end_at`，保存时保留秒数和时区；与结束日期二选一。旧 APK 会在保存时丢失此字段，因此不能用旧客户端创建或修改精确窗口。窗口已开始但未结束、仍有未来提醒时可确认；过期窗口不可创建。

AI 由后端固定使用 `deepseek-flash` 并关闭深度思考。首次延迟和后续间隔分别理解：“十分钟后收衣服”未给间隔时默认每 5 分钟继续；单次数小时窗口未给间隔时默认每 30 分钟。AI 补充的条件会显示在草稿提示中，保存前可以修改。“收一次就行”按完成一次后停止理解；明确“只通知一次，不用确认完成”时优先只通知。每天或每周固定小时窗口重复暂不支持。

JSON 或规则校验失败时，后端最多再让 AI 纠正一次，仍失败则提示重试或手动填写；客户端不做本地数字正则解析或固定规则兜底。升级 APK 不会自动替换或启动现有后端进程，后端手动换包时须保留 `data/` 和私人配置。

## 1.7.3 播种页与选花

versionCode 12。播种页按设计图补齐种子角色、右上叶饰与底部花草，返回、规则箭头、循环、完成和心形装饰使用细线图标；手写字体扩展到播种页文案。育苗盆只在生成草稿后显示，初始和未生成规则的手动填写状态不展示。

花种选择用浅绿底、描边和圆圈勾标明，任务保存成功后在本机记录所选品种，首页、花园和成长动画统一读取。记录按账号及规范化服务器地址隔离；未选择的新任务、旧任务及没有本地记录的任务继续使用原 task_id 哈希分配。选择不跨手机同步，清除应用数据后丢失。仅更新客户端，后端无需更新。

## 1.7.1 首页细节

versionCode 10。对照首页设计图调整播种胶囊、带支脚的木质种植告示牌、庭院到暖白页面的渐变、鼓励文案两侧叶饰，以及两级标题的深绿色与粗体字级。木牌和叶子为离线矢量资源，株数仍显示真实统计。沿用现有后端；手机视觉由用户验收。

## 1.7.0 晴日小庭院

versionCode 9。今天、花园、播种三页按确认设计稿落地，真实文字与操作仍为 Android 原生控件。首页庭院、花园草地、育苗盆及四种花、三个生长阶段均使用离线生成素材；小图和背景分别采样缓存。

花园四列三排，每片最多 12 株，只有选中植物显示名字，底部保留详情、分页和成长日记；场景高度依据实际可用视口调整。新建页展示四个品种，创建后按 task_id 固定分配，达到 7 次完成才开花；同任务的首页、花园和完成动画使用同一品种。草稿摘要保留不同完成模式、真实时间、日期范围与提醒上限，展开仍可编辑完整规则。

## 1.6.0 插画花圃

正式包 versionCode 为 8。新增完整庭院背景及粉色、淡紫色花朵，真实植物按 12 株一片直接摆在草地上，可以点击查看对应提醒、切换或跳转花圃。总种植数取服务端全量统计，生长阶段数来自已加载植物；服务端截断时明确说明范围。图片随 APK 离线提供，背景与小图标分别采样缓存。

底部导航独立居中图标与文字，统一图标容器和字体边距。原有账号、任务及记录保留；花园兼容既有接口，分钟催办仍需新版后端。手机观感和交互由用户验收。

## 1.5.0 分钟催办

正式包 versionCode 为 7，需配套 1.5.0 后端。新建和调整规则支持“每隔几分钟催一次”：默认 15 分钟后首次提醒、每隔 5 分钟继续，完成后永久停止。AI 草稿的首次时间保留秒数和时区；修改其他字段不会截断首次时间。实际邮件收取和手机交互由用户验收。

## 1.4.0 密码与自动登录

正式包 versionCode 为 6。注册及首次设置密码用邮箱验证码，日常用邮箱密码。默认将邮箱、密码、令牌与服务器绑定信息整体加密保存在 Android Keystore 保护的 AES/GCM 记录中，禁用备份；兼容迁移旧版加密令牌。

普通打开优先复用登录状态，过期或没有令牌时可用已保存的密码自动尝试一次。主动退出后不自动登录，仍保留默认填写。相同规范地址保存不注销；不同地址清掉旧令牌和页面缓存，自动验证成功进入首页，失败显示首页登录入口。没有在新服务器注册或密码不同会失败，不自动创建账号。旧后端缺少密码接口时提示升级到 1.4.0。

## 1.3.1 生长动画

正式包 versionCode 为 5。完成后播放约 2.5 秒的浇水、养分与植物展开动画；阶段门槛才发芽、长叶或开花，同阶段完成只呈现吸收养分。前台可见的庭院有轻微枝叶摇摆，离开页面或切后台即停止。尊重系统关闭动画设置，支持手动重看插画。后端需更新到 1.3.1 才会返回准确的生长结果；旧后端使用中性完成反馈。

## 1.3.0 卡通成长花园

界面统一为暖白柔绿的二次元卡通风，底部为今天、花园、日历、我的。任务创建为播种、完成为补充养分；1 次发芽、3 次长叶、7 次开花。漏做不会扣除累计成长。图片生成素材内置在安装包，阶段由服务器真实完成次数决定。

花园读取新版 `GET /api/v1/garden`，后端需同步升级到 1.3.0。读取失败、旧服务、加载中、空花园和截断分别说明，不使用演示数据填充。花园最近记录可回看文字照片，查看提醒后返回原入口；慢网下旧植物点击响应不会覆盖新的选择。

1.3.0 正式包 versionCode 为 4。保持原签名和包名，可覆盖升级，保留登录及本地设置。编译、Lint 和签名校验已完成；实际页面观感与交互由用户手机验收。

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
2. 新用户验证邮箱并设置密码，日常直接用邮箱密码登录。旧账号可从“注册 / 首次设置密码”或“我的”中验证一次邮箱设置密码，保留原记录。此邮箱继续接收提醒。
3. 输入自然语言生成可编辑草稿，或直接手动创建。支持一次、每日、每周、每月范围、日期范围、固定天数间隔、「每天提醒直到完成」和分钟催办。分钟催办可选精确截止，先检查草稿中的首次时间、间隔、完成方式和 AI 补充提示，再确认保存。
4. 任务详情可完成、附备注、调用系统相机、选择相册、延后或归档。首页和详情都有提醒开关，开启是柔绿色、暂停是灰色。完成凭证的照片会压缩为 JPEG 上传到自己的服务器。
5. 记录页用月历查看未来安排、邮件提醒与完成记录。蓝色表示待提醒或已提醒、绿色表示已完成、黄色表示到点未完成、红色表示周期结束仍未完成。点日期看当天安排，再点记录查看文字、照片，点未来安排进入提醒详情。我的页面可以切换服务器、检查连接、导出文字记录、退出登录。完整备份需要同时保存服务器媒体目录。

切换服务器会清理旧令牌和页面缓存，保留加密账号密码并自动尝试登录；旧请求的结果不会进入新服务器的界面。服务器地址、证书或网络出错时展示可重试的错误。

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

本次构建、检查与交付记录见 `../docs/VERIFICATION.md`。文档中的功能说明不代表当前手机已安装新版、服务器已换包或真实邮箱已收信；页面观感、交互及最终邮件投递需要实际使用验收。

请在实际手机快速验收：注册设置密码、默认填写与自动登录、相同地址保持登录、切换成功/失败、主动退出、卡通界面与生长动画、键盘显示、AI 草稿编辑、完成后停止本期提醒、相机与相册上传、记录中的照片。相机由设备系统应用提供，需要有可用相机应用。

会话及服务器地址策略检查（54 项断言，覆盖同源保留、跨服务器令牌隔离、退出、过期、凭证替换、IPv6 和旧无效地址纠正）：

```powershell
New-Item -ItemType Directory -Force build/session-tests | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 --release 17 -d build/session-tests app/src/main/java/dev/niannian/reminder/SessionPolicy.java tests/SessionPolicyTest.java
& "$env:JAVA_HOME/bin/java.exe" -cp build/session-tests dev.niannian.reminder.SessionPolicyTest
```

生长播放状态检查（18 项断言，覆盖首次播放、页面返回、旋转恢复、真实阶段、重复响应及账号清理）：

```powershell
New-Item -ItemType Directory -Force build/growth-tests | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 --release 17 -d build/growth-tests app/src/main/java/dev/niannian/reminder/GrowthAnimationState.java tests/GrowthAnimationStateTest.java
& "$env:JAVA_HOME/bin/java.exe" -cp build/growth-tests dev.niannian.reminder.GrowthAnimationStateTest
```
