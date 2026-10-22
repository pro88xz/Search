"""Turns the 4K masters into what the app ships (app/src/main/assets/wallpapers):
<id>.webp (2160x3840), <id>-1440.webp (1440x2560) and <id>-thumb.webp (270x480).

WebP's 8-bit encoding bands smooth gradients, so each file gets a soft grain first
(sigma 1.3 levels, blurred 1px) - strong enough to survive the encoder and dither its
steps - except in the flat top and bottom bands, which must equal the bars' colours.
usage: python3 -I encode.py <masters dir> <assets/wallpapers dir>"""
import sys, os
from PIL import Image, ImageFilter
import numpy as np

ORDER = ['owl_night', 'dawn_hills', 'cloud_sea', 'silk', 'ocean_dusk', 'aurora', 'bokeh', 'nebula']

def grainy(im, sigma=1.3, blur=1.0, seed=0):
    n = np.random.default_rng(seed).normal(0, 1, (im.height, im.width)).astype(np.float32)
    n = np.asarray(Image.fromarray(((n / 8 + 0.5) * 255).clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(blur))).astype(np.float32)
    n = (n - n.mean()) / (n.std() + 1e-6) * sigma
    v = (np.arange(im.height) + 0.5) / im.height
    n *= (np.clip((v - 0.02) / 0.03, 0, 1) * np.clip((0.97 - v) / 0.03, 0, 1))[:, None]
    return Image.fromarray(np.clip(np.asarray(im).astype(np.float32) + n[..., None], 0, 255).round().astype(np.uint8))

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    for i, n in enumerate(ORDER):
        m = Image.open(os.path.join(src, n + '.png')).convert('RGB')
        for suffix, size in [('', (2160, 3840)), ('-1440', (1440, 2560))]:
            im = m if size == m.size else m.resize(size, Image.LANCZOS)
            grainy(im, seed=i * 2 + (suffix == '')).save(os.path.join(dst, n + suffix + '.webp'), 'WEBP', quality=92, method=6)
        m.resize((270, 480), Image.LANCZOS).save(os.path.join(dst, n + '-thumb.webp'), 'WEBP', quality=86, method=6)
        print(n)
