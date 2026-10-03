# Linux 部署

## 升级到 1.8.1

1.8.1 后端增加助手人格和模糊时间自主安排，兼容 1.8.0 及以上客户端；1.8.1 APK 同时更新输入示例。精确窗口 `end_at` 至少需要 1.8.0 客户端，更旧 APK 保存规则时会丢失该字段。

现有进程不会因构建或下载新包而自动替换、停止或启动。直接运行的部署请手动停止旧程序，备份并保留原 `data/`、可选 `config.local.json` 及运行环境配置，只替换对应架构的可执行文件，再在原目录自行启动。不要同时启动两份程序。新版私人后端保留原有内置 AI/SMTP 配置；已有本地文件和环境变量覆盖仍可继续使用。

AI 固定使用 `deepseek-flash` 并关闭深度思考。旧 `deepseek_model` 或 `NIANNIAN_DEEPSEEK_MODEL` 不再改变模型；密钥、服务地址和 SMTP 配置仍按原有方式加载。AI 规则校验失败或只返回补充信息/拒绝说明时，最多再安排一次；模糊条件主动补全，无法完整表达的规则给出有说明的替代草稿。上游连接、认证或余额等失败直接报错，不会改用其他模型或本地解析兜底。

## 直接启动

普通 Intel/AMD 服务器选 amd64，ARM 服务器选 arm64。无需 Go、Python、Docker 或数据库服务。

```sh
tar -xzf niannian-linux-amd64.tar.gz
cd niannian-linux-amd64
chmod +x niannian
./niannian
```

默认端口 56507。手机填 `http://服务器IP:56507`，云服务器需要允许该端口或使用反向代理。应用中的“我的”随时可以修改服务器地址。生产环境使用 HTTPS，避免密码、验证码和登录令牌在公网明文传输。

## 开机启动（推荐）

在解压目录运行：

```sh
sudo bash install-service.sh
```

会安装到 `/opt/niannian`，创建权限受限的服务账户，并由 systemd 运行。数据在 `/opt/niannian/data`。脚本不会删除旧数据；升级前先备份，使用新包再次执行即可。

检查：`systemctl status niannian`；日志：`journalctl -u niannian -n 50`；停止：`sudo systemctl stop niannian`。

程序内置此私人交付的 AI/SMTP 配置，可直接运行。覆盖配置时将 `config.local.json` 放在 `/opt/niannian` 并确保服务用户可读，随后重启服务。

## 备份与恢复

为了同时保存 SQLite 与照片，先短暂停止服务，再备份整个数据目录：

```sh
sudo systemctl stop niannian
sudo tar -czf niannian-backup.tar.gz -C /opt/niannian data
sudo systemctl start niannian
```

恢复时停止服务，将备份中的 `data/` 放回 `/opt/niannian/`，恢复属主 `sudo chown -R niannian:niannian /opt/niannian/data`，再启动。不要让两份程序同时写同一个数据目录；程序设有实例锁。

应用 JSON 导出用于查看和携带文字记录，完整备份仍要包含媒体文件。卸载 Android 不会删除服务器记录；更换服务器地址不会搬运旧数据。

## 健康检查

`curl http://127.0.0.1:56507/health` 返回 `status: ok` 表示服务正在响应，不代表 SMTP 最终投递成功。任务的发送历史会记录成功或失败。

完成换包并启动后，健康检查中的 `version` 应为 `1.8.1`。仅生成发布包或安装 APK 不会改变现有服务版本；若仍返回旧版本，说明请求仍由旧后端处理。健康检查成功也不代表新版已安装到手机或邮件已送达。

## Caddy 示例（可选）

```text
reminder.example.com {
    reverse_proxy 127.0.0.1:56507
}
```

自行替换域名并配置解析，Android 填 `https://reminder.example.com`。用了反向代理后可在配置中将 `addr` 设为 `127.0.0.1:56507`。
