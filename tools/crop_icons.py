import os
from PIL import Image, ImageDraw
import numpy as np

src_path = r'C:/Users/Ben/.gemini/antigravity/brain/0b7e2fe8-c0d5-488e-ab51-4af116f1b4a3/.user_uploaded/media_1790610960734.jpg'
img = Image.open(src_path)
arr = np.array(img)

cols = [(10, 255), (265, 510), (515, 765), (770, 1015)]
rows = [(0, 225), (225, 450), (445, 670)]

densities = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192
}

def create_rounded_mask(size, radius_ratio=0.17, supersample=4):
    w, h = size
    sw, sh = w * supersample, h * supersample
    rad = int(min(sw, sh) * radius_ratio)
    mask = Image.new('L', (sw, sh), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([0, 0, sw - 1, sh - 1], radius=rad, fill=255)
    return mask.resize((w, h), Image.Resampling.LANCZOS)

for dir_name in densities.keys():
    os.makedirs(f'app/src/main/res/{dir_name}', exist_ok=True)

for r in range(3):
    for c in range(4):
        idx = r * 4 + c
        y0, y1 = rows[r]
        x0, x1 = cols[c]
        sub = arr[y0:y1, x0:x1]
        mask = np.any(sub > 15, axis=2)
        ys, xs = np.where(mask)
        ay0, ay1 = ys.min() + y0, ys.max() + y0
        ax0, ax1 = xs.min() + x0, xs.max() + x0
        
        # Crop to bounding box
        crop = img.crop((ax0, ay0, ax1 + 1, ay1 + 1))
        
        for dir_name, size in densities.items():
            resized = crop.resize((size, size), Image.Resampling.LANCZOS).convert('RGBA')
            alpha_mask = create_rounded_mask((size, size))
            resized.putalpha(alpha_mask)
            out_file = f'app/src/main/res/{dir_name}/ic_launcher_color_{idx}.png'
            resized.save(out_file)

print('Generated all mipmaps for 12 icons.')
