念念创建提醒优化后实测（后端2.6.1源码）

打开 index.html 查看优化前后对比。
fixed-results.json：10题真实后端Handler+真实模型的连续单轮原始结果。
review.json：按原五项标准逐项审阅，100/100；8题完整满足，2题仅边界替代通过。
manifest.json：原题与各证据文件SHA-256，同时记录旧结果哈希。

本轮没有新版进程HTTP实测：临时进程启动命令被自动审批以blocked by policy拒绝。
新Windows/Linux amd64后端已构建，但原有2.6.0服务未替换，没有部署验收。
基线报告与原有后端HTTP结果：../2026-10-08/index.html。

重建报告：node scripts/build-reminder-comparison-report.mjs
真实模型回归：在backend目录设置NIANNIAN_LIVE_AI=1后运行
go test ./internal/ai -run '^TestLiveReminderFidelity($|Variations$)' -count=1 -v
真实模型会消耗已配置API额度；默认测试不调用真实模型。

结果是草稿质量观察，不代表通知/闹钟/日历/邮件实际送达。
调优中已使用这些题，本报告是定向回归，不是独立盲测。
