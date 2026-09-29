#!/usr/bin/env python3
"""
clean_umd_textures.py
Processes raw UMD source assets into production-grade 512x512 RGBA textures
for the PXP2 interactive 3D UMD viewer (Umd3DView.java).

Outputs:
- app/src/main/res/drawable/umd_disc_red.png (512x512 RGBA)
- app/src/main/res/drawable/umd_disc_default.png (512x512 RGBA)
- app/src/main/res/drawable/umd_caddy_front.png (512x512 RGBA)
- app/src/main/res/drawable/umd_caddy_back.png (512x512 RGBA)
"""

import os
import sys
from PIL import Image, ImageDraw, ImageFilter
import numpy as np
from scipy.ndimage import label, binary_fill_holes

DEFAULT_P1 = r"C:/Users/Ben/.gemini/antigravity/brain/0b7e2fe8-c0d5-488e-ab51-4af116f1b4a3/.user_uploaded/media_1790614287551.png"
DEFAULT_P2 = r"C:/Users/Ben/.gemini/antigravity/brain/0b7e2fe8-c0d5-488e-ab51-4af116f1b4a3/.user_uploaded/media_1790614301617.png"
DEFAULT_P3 = r"C:/Users/Ben/.gemini/antigravity/brain/0b7e2fe8-c0d5-488e-ab51-4af116f1b4a3/.user_uploaded/media_1790614348539.png"

TARGET = 512
DISC_RADIUS = 216

def process_textures(p_back=DEFAULT_P1, p_front=DEFAULT_P2, p_disc=DEFAULT_P3, out_dir=None):
    if out_dir is None:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        project_root = os.path.dirname(script_dir)
        out_dir = os.path.join(project_root, "app", "src", "main", "res", "drawable")
    
    os.makedirs(out_dir, exist_ok=True)
    print(f"[clean_umd_textures] Output directory: {out_dir}")

    # ==========================================
    # 1. Red Disc (media_1790614348539.png)
    # ==========================================
    print(f"[clean_umd_textures] Processing disc art from {p_disc}...")
    im3 = Image.open(p_disc).convert("RGBA")
    cx, cy = 223.0, 222.0
    r_out = 191.5
    r_in = 32.5
    
    crop_size = int(r_out * 2)
    left = int(cx - r_out)
    top = int(cy - r_out)
    cropped_disc = im3.crop((left, top, left + crop_size, top + crop_size))

    disc_diam_target = int(DISC_RADIUS * 2)
    resized_disc = cropped_disc.resize((disc_diam_target, disc_diam_target), Image.Resampling.LANCZOS)
    disc_canvas = Image.new("RGBA", (TARGET, TARGET), (0, 0, 0, 0))
    offset = (TARGET - disc_diam_target) // 2
    disc_canvas.paste(resized_disc, (offset, offset))

    # Anti-aliased circular alpha mask with inner spindle hole transparency
    mask_scale = 4
    mask = Image.new("L", (TARGET * mask_scale, TARGET * mask_scale), 0)
    draw = ImageDraw.Draw(mask)
    c_sub = (TARGET * mask_scale) / 2
    r_out_sub = DISC_RADIUS * mask_scale
    r_in_sub = (r_in * (DISC_RADIUS / r_out)) * mask_scale

    draw.ellipse([c_sub - r_out_sub, c_sub - r_out_sub, c_sub + r_out_sub, c_sub + r_out_sub], fill=255)
    draw.ellipse([c_sub - r_in_sub, c_sub - r_in_sub, c_sub + r_in_sub, c_sub + r_in_sub], fill=0)

    smooth_mask = mask.resize((TARGET, TARGET), Image.Resampling.LANCZOS)
    disc_canvas.putalpha(smooth_mask)

    disc_red_path = os.path.join(out_dir, "umd_disc_red.png")
    disc_def_path = os.path.join(out_dir, "umd_disc_default.png")
    disc_canvas.save(disc_red_path)
    disc_canvas.save(disc_def_path)
    print(f" -> Saved {disc_red_path} and {disc_def_path} (512x512 RGBA)")

    # ==========================================
    # 2. Front Caddy (media_1790614301617.png)
    # ==========================================
    print(f"[clean_umd_textures] Processing front caddy from {p_front}...")
    im2 = Image.open(p_front).convert("RGBA")
    arr2 = np.array(im2)

    black = (arr2[:, :, 0] < 20) & (arr2[:, :, 1] < 20) & (arr2[:, :, 2] < 20)
    labeled, num = label(black)
    c_out = labeled[0, 0]
    counts = [(labeled == i).sum() for i in range(1, num + 1)]
    c_win = [i for i in range(1, num + 1) if i != c_out and (labeled == i).sum() == max(counts)][0]

    is_transparent = (labeled == c_out) | (labeled == c_win)
    alpha = np.where(is_transparent, 0, 255).astype(np.uint8)
    gray = arr2[:, :, :3].mean(axis=-1)
    feather_mask = ~is_transparent & (gray < 160)
    alpha[feather_mask] = np.clip((gray[feather_mask] - 15) * (255.0 / (160 - 15)), 0, 255).astype(np.uint8)

    arr2[:, :, 3] = alpha
    caddy_front = Image.fromarray(arr2).resize((TARGET, TARGET), Image.Resampling.LANCZOS)

    front_path = os.path.join(out_dir, "umd_caddy_front.png")
    caddy_front.save(front_path)
    print(f" -> Saved {front_path} (512x512 RGBA)")

    # ==========================================
    # 3. Back Caddy (media_1790614287551.png)
    # ==========================================
    print(f"[clean_umd_textures] Processing back caddy/window from {p_back}...")
    im1 = Image.open(p_back).convert("RGBA")
    cx1, cy1, r1 = 121.0, 126.0, 101.0
    crop1 = im1.crop((int(cx1 - r1), int(cy1 - r1), int(cx1 + r1), int(cy1 + r1)))
    win_diam = int(DISC_RADIUS * 2)
    win_resized = crop1.resize((win_diam, win_diam), Image.Resampling.LANCZOS)

    win_mask = Image.new("L", (win_diam * 4, win_diam * 4), 0)
    d_w = ImageDraw.Draw(win_mask)
    d_w.ellipse([0, 0, win_diam * 4, win_diam * 4], fill=255)
    win_resized.putalpha(win_mask.resize((win_diam, win_diam), Image.Resampling.LANCZOS))

    outside = (labeled == c_out)
    body_mask = binary_fill_holes(~outside)
    body_mask_img = Image.fromarray((body_mask * 255).astype(np.uint8)).resize((TARGET, TARGET), Image.Resampling.LANCZOS)
    body_mask_back = body_mask_img.transpose(Image.Transpose.FLIP_LEFT_RIGHT)

    back_canvas = Image.new("RGBA", (TARGET, TARGET), (228, 228, 232, 255))
    back_canvas.paste(win_resized, (offset, offset), win_resized)
    back_canvas.putalpha(body_mask_back)

    back_path = os.path.join(out_dir, "umd_caddy_back.png")
    back_canvas.save(back_path)
    print(f" -> Saved {back_path} (512x512 RGBA)")

    print("[clean_umd_textures] Texture preparation complete!")

if __name__ == "__main__":
    process_textures()
