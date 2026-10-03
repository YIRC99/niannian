#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 sudo bash install-service.sh 安装开机启动服务"
  exit 1
fi
if [ ! -f ./niannian ]; then
  echo "请将脚本与 niannian 程序放在同一目录"
  exit 1
fi
if ! id niannian >/dev/null 2>&1; then
  useradd --system --home-dir /opt/niannian --shell /usr/sbin/nologin niannian
fi
install -d -m 750 -o niannian -g niannian /opt/niannian /opt/niannian/data
systemctl stop niannian.service 2>/dev/null || true
if [ "$(pwd -P)/niannian" != /opt/niannian/niannian ]; then
  install -m 750 -o root -g niannian ./niannian /opt/niannian/niannian
else
  chmod 750 /opt/niannian/niannian
  chown root:niannian /opt/niannian/niannian
fi
if [ -f config.local.json ] && [ "$(pwd -P)/config.local.json" != /opt/niannian/config.local.json ]; then
  install -m 640 -o root -g niannian config.local.json /opt/niannian/config.local.json
fi
install -m 644 niannian.service /etc/systemd/system/niannian.service
systemctl daemon-reload
systemctl enable --now niannian.service
echo "已启动。查看状态：systemctl status niannian"
echo "查看日志：journalctl -u niannian -n 50"
