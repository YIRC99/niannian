# 首页木牌手写字体

`android/app/src/main/res/font/garden_handwriting.ttf` 是随 APK 内置的中文毛笔字体子集，供首页木牌使用。中文和动态数字均由同一个字体文件绘制，不依赖手机系统字体。

- 原字体：**Ma Shan Zheng Regular**（马善政），版本 2.003。
- 官方来源：[Google Fonts / ofl/mashanzheng](https://github.com/google/fonts/tree/406197b91ff39a93061c2c2eeaee67ddf2ae1f0d/ofl/mashanzheng)。固定提交为 `406197b91ff39a93061c2c2eeaee67ddf2ae1f0d`。
- 许可：[SIL Open Font License 1.1](garden-handwriting-OFL.txt)，原作者版权声明随文件完整保留；完整许可也写入子集字体的 name 表，随 APK 分发。
- 原许可未声明 Reserved Font Name。为明确区分修改后的子集，主动把内部 family 改为 **Niannian Garden Hand**，PostScript 名改为 `NiannianGardenHand-Regular`。
- 子集文本：`花园里，已种下 株走进我的花园花园暂未更新，点此查看0123456789-`，共 32 个不同字符，包含空格、中文逗号、全部十进制数字与 ASCII 负号。
- 成品大小：25,304 字节；原始字体大小：5,857,936 字节。

扩展木牌文案时，请同步扩展脚本中的 `CHARACTERS` 并重新生成，避免新增字符回退到系统字体。

## 复现

在仓库根目录运行以下 PowerShell 命令。原始字体保存在系统临时目录，仓库仅保留子集；工具由 `uv` 临时提供，无需安装全局 Python 或 fontTools。

```powershell
$fontSourcePath = Join-Path $env:TEMP 'niannian-MaShanZheng-Regular.ttf'
Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/google/fonts/406197b91ff39a93061c2c2eeaee67ddf2ae1f0d/ofl/mashanzheng/MaShanZheng-Regular.ttf' -OutFile $fontSourcePath
uv run --with fonttools==4.66.1 python docs/fonts/subset_garden_handwriting.py $fontSourcePath
```

脚本会在生成前后检查字符覆盖，同时保留 `.notdef` 轮廓、原作者版权和完整许可。原字体 SHA-256：`6d2546bb189c732a8ca29af9e22457b152387d158aa459e4ac2ce1e51788b7fb`。

子集 SHA-256：`17b3e76166cb69f7d7018db36c3abcad84e284c07872e047a0e0c9b6880ac997`。
