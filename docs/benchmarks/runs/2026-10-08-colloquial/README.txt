念念短口语创建提醒评测

先打开 index.html。每题可切换第1/2/3轮，查看真实返回、冻结标准和逐项证据。
新题文件：../../reminder-creation-colloquial-v2.json
旧详细题保留；新题平均15.4字，旧题平均95.2字（Unicode字符数，含标点）。

三轮：77、76、75，平均76/100。不能与旧题100分作同口径比较。
30次均HTTP 200，但返回草稿不等于理解正确；最明显的是缺少面试时间仍编排日期，和下午活动没有截止。
RC-06使用用户原句：后面每天中午记得提醒我还相机。
三轮均ongoing+until_done；完成后永久停。2轮未说明12点是补全建议，均值9.33/10。

round-1/2/3.json：连续三轮完整原始证据，包含每题两次真实模型输出。
review.json：逐项分数、证据、人工改进示例和问题分析。改进示例不是本轮实际输出。
manifest.json：题目/证据SHA-256、三轮总分和各项合计。
http-results.json：另10次直接访问当前运行2.6.1后端的真实结果，9次200、1次502。
此时五点截止已经过去，RC-02返回无未来提醒时间；真实时钟组单独观察，不计入76分。
临时账号删除后复查404，生成后任务数0。进程PID42316由本轮之外启动，本轮未启动或替换。

题目与评分在测试前提交为d110645；生产代码保持68df951基线，当前后端源码2.6.1。
评分组通过真实后端HTTP Handler调用deepseek-flash；隔离时间、数据库、邮件器，不启动调度器。
没有启动/替换运行中后端，没有创建提醒或验证真实渠道送达。
这次只重写评测并实测，没有修改生产提示词或修复新暴露的问题。

重建报告（仓库根目录）：node scripts/build-colloquial-benchmark-report.mjs
复测方法（backend目录，真实模型会消耗API额度）：
  设置NIANNIAN_LIVE_BENCHMARK=1
  设置NIANNIAN_BENCHMARK_SUITE为新题JSON的绝对路径
  设置NIANNIAN_BENCHMARK_OUTPUT为新的结果文件路径，不覆盖本次证据
  设置NIANNIAN_BENCHMARK_COMMIT为当前源码提交
  go test ./internal/app -run '^TestLiveReminderCreationBenchmark$' -count=1 -v
每个输出文件包含完整10题。三轮需分别调用，原始结果全部保留。
