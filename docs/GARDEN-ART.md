# 念念花园素材

2.1.0 新增八张独立透明花卉 PNG：向日葵（sunflower）、玫瑰（rose）、薰衣草（lavender）、波斯菊（cosmos）、百合（lily）、三色堇（pansy）、鸢尾（iris）、木槿（hibiscus）。内置 image_gen 分别生成，文件为 `android/app/src/main/res/drawable-nodpi/garden_flower_{type}.png`，完整提示词及来源见 [flowers-expansion-2.1.txt](../UI设计/prompts/flowers-expansion-2.1.txt)。用于选花预览与真实开花阶段，种子、幼芽、枝叶继续复用。原四花素材与编号保持不变。

1.8.9 我的页新增 `profile_header.png`（2048×768、不透明），使用内置 image_gen 生成并复制入项目，左侧留白承载原生标题，右侧绣球和白栅栏；完整提示词见 [profile-implementation-assets.txt](../UI设计/prompts/profile-implementation-assets.txt)。底部花草、幼芽及木牌继续复用，木牌文本所需手写字库补齐至 64 字符。图片不承载账号信息、按钮或业务状态。

1.8.7 对照设计板 07、08、09 检查后，全部复用已有庭院、种子角色、四花、叶饰、木牌及日历横幅，未生成新图片。空花园的种子角色只是引导装饰；日记照片仅取真实附件。新增木牌和页尾文案所需手写字体已补齐至 60 字符，见 `docs/fonts/README.md`。

2026-10-03，使用内置 image_gen 工具生成。采用用户要求的二次元卡通风格、透明背景，原始 PNG 位于 `android/app/src/main/res/drawable-nodpi/`。图片与程序一起打包，无需联网获取。较早的写实手绘尝试未纳入项目。

| 文件 | 用途 |
| --- | --- |
| garden_seed.png | 新建提醒与尚未完成的种子 |
| garden_sprout.png | 1–2 次完成的小芽，应用图标 |
| garden_leaf.png | 3–6 次完成的枝叶 |
| garden_bloom.png | 7 次及以上完成的花朵 |
| garden_watering.png | 花园入口、空花园与完成反馈 |
| garden_backdrop_v2.png | 1.6.0 完整庭院背景，1086×1448、不透明；中央草地留给真实植物 |
| garden_bloom_pink_v2.png | 1.6.0 粉色波斯菊变体，1254×1254、透明背景 |
| garden_bloom_lilac_v2.png | 1.6.0 淡紫三色堇变体，1254×1254、透明背景 |

阶段是产品视觉规则，不是植物生长或心理学结论。只根据服务器上的真实完成次数计算，提醒次数、打开次数不会产生养分。归档后有完成记录的植物保留；漏做不清零、不枯萎。后台返回的最近记录不参与累计次数推算。

1.6.0 的三张新图片同样使用内置 image_gen 生成并复制入项目，原有素材保留。背景提示强调 3:4 纵向二次元庭院、上部围栏/藤蔓入口/小屋、边缘石板路和园艺道具、中央 12%—89% 横向及 28%—92% 纵向留出空草地；无文字、界面或假种植网格。最终构图的草地用于叠加真实植株，底部两个道具通过末排内收避让。

花朵提示以既有 `garden_bloom.png` 为风格参考：完整的三朵粉色波斯菊或淡紫三色堇、圆润鼠尾草绿叶、桃棕色土丘、清晰描边与少量分层阴影、真正透明背景，无文字或其他道具。生成尺寸按工具实际输出保留，未二次加工。

## 1.7.3 播种页素材与显示规则

对照播种页设计图新增三张离线素材，并沿用育苗盆；新素材提示词见 `docs/sowing-art-prompts.txt`。文案仍由原生控件绘制，手写字使用内置字体，字体来源与覆盖范围见 `docs/fonts/`。

| 文件 | 用途 |
| --- | --- |
| sowing_seed_mascot.png | 标题旁的种子角色 |
| sowing_corner_leaves.png | 页面右上角叶饰 |
| sowing_footer_foliage.png | 页面底部花草装饰 |
| garden_seed_pot_v3.png | 沿用育苗盆，仅在生成提醒草稿后展示 |

初始页面和未生成规则的手动填写状态不展示育苗盆。花种可选择，选中态使用浅绿底、描边及圆圈勾；保存后的品种由首页、花园和成长动画共用。选择仅在本机按账号与规范化服务器地址隔离保存，不跨手机同步，清除应用数据后丢失；未选择和旧任务沿用原 task_id 哈希分配。素材和选择均为客户端变更，后端无需更新。

## 1.7.0 晴日小庭院素材

以用户确认的三屏设计稿为风格参考，使用内置 image_gen 单独生成生产素材，原图直接复制到 `drawable-nodpi`；不把设计稿截图当 UI。实际提示词归档于 `docs/garden-v17-prompts.txt`。

| 文件 | 用途 |
| --- | --- |
| garden_home_hero_v3.png | 首页庭院，天空留给标题，底部融入奶油色内容区 |
| garden_courtyard_v3.png | 花园全幅场景，中央草地留给真实任务植物 |
| garden_seed_pot_v3.png | 播种页育苗盆，透明背景 |
| garden_seed_v3.png | 未完成阶段的种子与小土丘，透明背景 |
| garden_sprout_v3.png | 1–2 次完成的双叶幼芽，透明背景 |
| garden_leaf_v3.png | 3–6 次完成的枝叶，无花，透明背景 |
| garden_flower_tulip.png | 粉色郁金香，透明背景 |
| garden_flower_daisy.png | 黄白雏菊，透明背景 |
| garden_flower_lupine.png | 紫色羽扇豆，透明背景 |
| garden_flower_hydrangea.png | 粉色绣球，透明背景 |

品种固定映射由 `GardenFlowers` 统一管理，列表、花园及生长动画共用。品种不影响真实生长阶段。旧素材保留，不作为新版本的任务花种。背景边缘植物属于环境装饰，不参与种植数量。

## 较早版本生成提示词

### anime_sprout

Create one polished 2D ANIME CARTOON game inventory icon for a cozy growth-garden Android app. Strictly flat Japanese anime / kawaii game art, clean confident dark sage outlines, simple flat pastel colors with just 1 or 2 cel-shaded tones, round friendly silhouette, chibi proportions. NO realism, NO gouache, NO watercolor, NO paper grain, NO photorealistic texture, NO 3D render, NO detailed natural veins. Looks like an asset in an adorable anime farming game. Pastel sage green, buttery yellow, peach brown. Square canvas, single centered subject filling about 75 percent of frame, full silhouette visible, true transparent background. No text, numbers, UI, border, watermark or checkerboard. A tiny lively plant sprout with two big round leaves and a short stem, emerging from one small simple rounded peach-brown mound of soil. One tiny white sparkle near a leaf. Charming and hopeful.

### anime_leaf

Create one polished 2D ANIME CARTOON game inventory icon for a cozy growth-garden Android app. Strictly flat Japanese anime / kawaii game art, clean confident dark sage outlines, simple flat pastel colors with just 1 or 2 cel-shaded tones, round friendly silhouette, chibi proportions. NO realism, NO gouache, NO watercolor, NO paper grain, NO photorealistic texture, NO 3D render, NO detailed natural veins. Looks like an asset in an adorable anime farming game. Pastel sage green, buttery yellow, peach brown. Square canvas, single centered subject filling about 75 percent of frame, full silhouette visible, true transparent background. No text, numbers, UI, border, watermark or checkerboard. A young round bushy plant with six rounded leaves and one small closed yellow flower bud at the top, emerging from one small simple rounded peach-brown mound of soil. Clear growth-stage icon, lovely playful anime shape.

### anime_bloom

Create one polished 2D ANIME CARTOON game inventory icon for a cozy growth-garden Android app. Strictly flat Japanese anime / kawaii game art, clean confident dark sage outlines, simple flat pastel colors with just 1 or 2 cel-shaded tones, round friendly silhouette, chibi proportions. NO realism, NO gouache, NO watercolor, NO paper grain, NO photorealistic texture, NO 3D render, NO detailed natural veins. Looks like an asset in an adorable anime farming game. Pastel sage green, buttery yellow, peach brown. Square canvas, single centered subject filling about 75 percent of frame, full silhouette visible, true transparent background. No text, numbers, UI, border, watermark or checkerboard. A compact healthy plant with THREE cheerful pale-yellow five-petal flowers, large round golden centers, lush rounded sage green leaves, growing on one small simple peach-brown soil mound. Lovely anime farming-game reward icon, beautifully balanced.

### anime_watering

Create one polished 2D ANIME CARTOON game inventory icon for a cozy growth-garden Android app. Strictly flat Japanese anime / kawaii game art, clean confident dark sage outlines, simple flat pastel colors with just 1 or 2 cel-shaded tones, round friendly silhouette, chibi proportions. NO realism, NO gouache, NO watercolor, NO paper grain, NO photorealistic texture, NO 3D render, NO detailed natural veins. Looks like an asset in an adorable anime farming game. Pastel sage green, buttery yellow, peach brown. Square canvas, single centered subject filling about 75 percent of frame, full silhouette visible, true transparent background. No text, numbers, UI, border, watermark or checkerboard. A chubby pastel-green watering can with rounded handle and simple spout, a little creamy yellow star emblem on the side, three small blue cartoon water drops floating from the spout. Friendly anime farming-game tool.

### anime_seed

Create one polished 2D ANIME CARTOON game inventory icon for a cozy growth-garden Android app. Strictly flat Japanese anime / kawaii game art, clean confident dark sage outlines, simple flat pastel colors with just 1 or 2 cel-shaded tones, round friendly silhouette, chibi proportions. NO realism, NO gouache, NO watercolor, NO paper grain, NO photorealistic texture, NO 3D render, NO detailed natural veins. Looks like an asset in an adorable anime farming game. Pastel sage green, buttery yellow, peach brown. Square canvas, single centered subject filling about 75 percent of frame, full silhouette visible, true transparent background. No text, numbers, UI, border, watermark or checkerboard. A single plump peach-colored SEED nestled in a small soft brown oval mound of soil, with a tiny heart-shaped leaf marking on the seed. A simple planted seed icon, not yet sprouted. Cute anime farming-game shape.
# 日历素材补充 · 2026-10-04

- `calendar_header.png`：日历庭院横幅，沿用首页庭院画风，单独生成横向构图，保留小门、花丛与睡猫，原首页图片未修改。
- `calendar_empty_journal.png`：空日历的书本与幼芽，透明 PNG。
- `calendar_mail.png`：邮件记录的花叶信封，透明 PNG。
- 均位于 `android/app/src/main/res/drawable-nodpi/`，使用内置 image_gen 生成，完整提示词见 `UI设计/prompts/calendar-implementation-assets.txt`。运行时沿用采样缓存，插画不表示真实完成记录或成长值；照片仍只读取服务端附件。
