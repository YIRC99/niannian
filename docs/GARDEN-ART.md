# 念念花园素材

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

## 最终生成提示词

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
