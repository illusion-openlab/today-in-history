#!/usr/bin/env python3
"""Regenerate the PICO layered app-icon resources from the master cutout.

Input : docs/logo-source-cutout.png  (1536x1536 RGBA, background already matted
        out, ground shadow removed, glyph corrected)
Output: app/src/main/res/drawable/icon_3d_layer_{0,1}.png
        app/src/main/res/drawable/icon_3d_sdf_{0,1}.png
        app/src/main/res/mipmap-xxxhdpi/ic_spatial_launcher.png

Constraints come from the PICO layered-icon spec (spatial-design_foundation_
icon_app-icon-and-layered-design):
  - content must sit inside the inscribed circle of the 1024x1024 canvas;
  - the background layer carries no transparent pixels inside that circle;
  - highlights and shadows are added by the system, never baked into a layer.

SDF encoding was measured from the shipped template assets: RGB == alpha ==
clamp(8 * distance-inside-the-shape-in-px, 0, 255).

Requires Pillow and numpy. Run from the repository root.
"""
import numpy as np
from PIL import Image

N = 1024
SDF_SLOPE = 8.0
MOTIF_HEIGHT = 580                      # keeps the buffer area the spec asks for
STORE_ICON_SIZE = 720                   # 后台硬限制：380 <= 宽 <= 760，超出会被静默丢弃
# 取自设计稿本身的背景渐变，保证图标与商店素材同色系。
BG_TOP_LEFT = (51, 59, 82)
BG_BOTTOM_RIGHT = (25, 30, 49)

SRC = 'docs/logo-source-cutout.png'
DRAWABLE = 'app/src/main/res/drawable/'
MIPMAP = 'app/src/main/res/mipmap-xxxhdpi/'


def inside_distance(mask):
    """Chamfer distance from every True pixel to the nearest False pixel."""
    INF = 1e9
    d = np.where(mask, INF, 0.0)
    idx = np.arange(d.shape[1], dtype=float)

    def sweep_row(row):
        row = np.minimum(row, np.minimum.accumulate(row - idx) + idx)
        rev = (row + idx)[::-1]
        return np.minimum(row, np.minimum.accumulate(rev)[::-1] - idx)

    d = np.minimum(d, np.minimum.accumulate(d - idx, axis=1) + idx)
    rev = (d + idx)[:, ::-1]
    d = np.minimum(d, np.minimum.accumulate(rev, axis=1)[:, ::-1] - idx)
    for rows in (range(d.shape[0]), range(d.shape[0] - 1, -1, -1)):
        for y in rows:
            for nb in (d[y - 1] if y > 0 else None,
                       d[y + 1] if y + 1 < d.shape[0] else None):
                if nb is None:
                    continue
                cand = np.minimum(nb + 1.0, np.concatenate([[INF], nb[:-1]]) + 1.41421356)
                cand = np.minimum(cand, np.concatenate([nb[1:], [INF]]) + 1.41421356)
                d[y] = np.minimum(d[y], cand)
            d[y] = sweep_row(d[y])
    return d


def sdf_from_alpha(alpha):
    v = np.clip(inside_distance(alpha > 127) * SDF_SLOPE, 0, 255).astype(np.uint8)
    return Image.fromarray(np.dstack([v, v, v, v]), 'RGBA')


def circle_alpha(n=N, ss=4):
    yy, xx = np.mgrid[0:n * ss, 0:n * ss]
    r = np.hypot((xx + 0.5) / ss - n / 2, (yy + 0.5) / ss - n / 2)
    cov = (r <= n / 2).astype(np.float32).reshape(n, ss, n, ss).mean((1, 3))
    return (cov * 255).astype(np.uint8)


def main():
    t = (np.mgrid[0:N, 0:N].sum(0) / (2.0 * (N - 1)))[..., None]
    bg = (np.array(BG_TOP_LEFT, float) * (1 - t)
          + np.array(BG_BOTTOM_RIGHT, float) * t).astype(np.uint8)
    layer0 = Image.fromarray(np.dstack([bg, circle_alpha()]), 'RGBA')

    src = Image.open(SRC).convert('RGBA')
    src = src.crop(src.getbbox())
    w = max(1, int(round(src.size[0] * MOTIF_HEIGHT / src.size[1])))
    motif = src.resize((w, MOTIF_HEIGHT), Image.LANCZOS)
    layer1 = Image.new('RGBA', (N, N), (0, 0, 0, 0))
    layer1.paste(motif, ((N - w) // 2, (N - MOTIF_HEIGHT) // 2), motif)

    launcher = layer0.copy()
    launcher.alpha_composite(layer1)

    for path, im in [(DRAWABLE + 'icon_3d_layer_0.png', layer0),
                     (DRAWABLE + 'icon_3d_layer_1.png', layer1),
                     (DRAWABLE + 'icon_3d_sdf_0.png', sdf_from_alpha(np.array(layer0.split()[-1]))),
                     (DRAWABLE + 'icon_3d_sdf_1.png', sdf_from_alpha(np.array(layer1.split()[-1]))),
                     (MIPMAP + 'ic_spatial_launcher.png', launcher)]:
        im.save(path)
        print('wrote', path)

    # 商店用图标：方形、不透明，宽度必须落在 380~760 之间。
    store = launcher.convert('RGB').resize((STORE_ICON_SIZE, STORE_ICON_SIZE), Image.LANCZOS)
    store.save('docs/store/icon_store_720.png')
    print('wrote docs/store/icon_store_720.png', store.size)


if __name__ == '__main__':
    main()
