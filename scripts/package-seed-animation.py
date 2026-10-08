"""Package the imagegen 4x4 action sheet; preserve alpha and align feet for playback.

Run with: uv run --with pillow python scripts/package-seed-animation.py
Artwork is generated, not drawn or repainted by this script.
"""
from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'UI设计/animations/seed-thinking'
source = Image.open(OUT / 'source.png').convert('RGBA')
durations = [360, 90, 90, 110, 140, 140, 180, 400, 90, 90, 140, 260, 140, 120, 140, 220]
frames, anchors = [], []
for index in range(16):
    col, row = index % 4, index // 4
    frame = source.crop((round(col*source.width/4), round(row*source.height/4),
                         round((col+1)*source.width/4), round((row+1)*source.height/4)))
    mask = frame.getchannel('A').point(lambda alpha: 255 if alpha > 96 else 0)
    box = mask.getbbox()
    if not box:
        raise ValueError(f'Empty frame {index}')
    foot_top = box[3] - max(1, round((box[3]-box[1])*.08))
    feet = mask.crop((0, foot_top, frame.width, box[3])).getbbox()
    anchors.append(((feet[0]+feet[2])/2, box[3], box[3]-box[1]))
    frames.append(frame)
scale = 222 / max(anchor[2] for anchor in anchors)
atlas = Image.new('RGBA', (1024, 1024))
preview = []
for index, (frame, (anchor_x, baseline, height)) in enumerate(zip(frames, anchors)):
    # One scale across all frames; foot anchors prevent camera jitter.
    resized = frame.resize((round(frame.width*scale), round(frame.height*scale)), Image.Resampling.LANCZOS)
    cell = Image.new('RGBA', (256, 256))
    cell.alpha_composite(resized, (round(128-anchor_x*scale), round(240-baseline*scale)))
    atlas.alpha_composite(cell, ((index%4)*256, (index//4)*256))
    paper = Image.new('RGBA', cell.size, '#fffcf5')
    paper.alpha_composite(cell)
    preview.append(paper.convert('RGB'))
atlas.save(ROOT / 'android/app/src/main/res/drawable-nodpi/sowing_thinking_sheet.png', optimize=True)
atlas.save(OUT / 'thinking-atlas.png', optimize=True)
preview[0].save(OUT / 'thinking.gif', save_all=True, append_images=preview[1:],
                duration=durations, loop=0, disposal=2, optimize=False)
(OUT / 'timing.json').write_text(json.dumps({'grid':[4,4], 'cell':256, 'durations_ms':durations,
    'loop_ms':sum(durations), 'gif_background':'#fffcf5', 'android_atlas':'transparent RGBA'}, indent=2), encoding='utf-8')
gif = Image.open(OUT / 'thinking.gif')
assert gif.n_frames == 16
assert sum((gif.seek(i) or gif.info['duration']) for i in range(gif.n_frames)) == sum(durations)
assert atlas.getchannel('A').getextrema() == (0, 255)
print(f'16 frames, {sum(durations)} ms loop, 1024x1024 RGBA atlas; GIF verified')
