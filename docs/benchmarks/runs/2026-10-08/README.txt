念念创建提醒实测，2026-10-08

打开 index.html 查看报告。页面可离线使用，内含输入、生成安排、逐项评分、两组原始响应和模型修复过程。

文件：
http-results.json：实际访问既有后端 http://127.0.0.1:56507 的10次响应，服务器使用真实时间。
fixed-results.json：本地源码HTTP Handler使用原定时间、隔离数据库与真实模型的10次响应。
review.json：人工逐项审阅记录，5项×2分×10题；本次严格评分88分。
manifest.json：原始文件、评分、HTML及用例集的SHA-256，供核对本次证据。

运行组原话未改，没有注入伪造“当前时间”。因此跨午夜、二月、跨年、深夜等条件只在固定时间组覆盖。
运行二进制版本2.6.0，构建记录含modified=true；不能证明与当前源码完全相同，两组不可混为一组。
固定组模型返回deepseek-flash，10题共11次模型调用，RC-10触发后端自带的1次修复。
运行组测试账号创建后只调用解析及读取，没有确认创建任务；结束后删除，并以404复核。
固定组未启动服务器或调度器，邮件为测试替身，任务/通知/完成/计划/奖励记录均为0。
没有测试任务保存、手机触发或邮件实际送达。Go测量脚本PASS只代表采集及副作用检查通过，不代表语义满分。

重新测试请使用新的输出目录，保留本次原始结果。运行HTTP组需要本地配置中的管理密钥；脚本只在内存使用凭证。
在项目根目录运行（PowerShell 7.5+）：
  ./scripts/run-reminder-http-benchmark.ps1 -OutputPath 'D:\code_file\niannian\work\benchmark-new-run\http-results.json'

固定时间组，在 backend 目录运行：
  $env:NIANNIAN_LIVE_BENCHMARK='1'
  $env:NIANNIAN_BENCHMARK_OUTPUT='D:\code_file\niannian\work\benchmark-new-run\fixed-results.json'
  $env:NIANNIAN_BENCHMARK_COMMIT=(git rev-parse HEAD)
  go test ./internal/app -run '^TestLiveReminderCreationBenchmark$' -count=1 -v -timeout 15m

重新测试后必须重新审阅并编写对应review.json，不可沿用本次分数；报告生成器不自动判断语义。
在项目根目录生成对应目录下的HTML：
  node scripts/build-reminder-benchmark-report.mjs 'D:\code_file\niannian\work\benchmark-new-run'

本次仅增加测量脚本、测试入口和报告，未修改创建提醒业务实现。没有新启动后台项目服务，原有后端保持原状态。
