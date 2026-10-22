"""Procedural wallpaper toolkit. Light is mixed in linear sRGB (as light adds), gradients run
through OKLab (as the eye sees), noise is gradient noise in fractal sums, and the result is
dithered to 8 bits - no banding, every edge anti-aliased at the output resolution."""
import numpy as np, math

class Canvas:
    def __init__(self, W, H, seed=1):
        self.W, self.H = W, H
        self.s = W / 2160.0                      # pixel sizes are authored for 4K and scale with it
        y, x = np.mgrid[0:H, 0:W].astype(np.float32)
        self.u, self.v = (x + 0.5) / W, (y + 0.5) / H
        self.aspect = H / W
        self.rng = np.random.default_rng(seed)

# ---------- colour ----------
def s2l(c): c = np.asarray(c, np.float64); return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def l2s(c): c = np.clip(c, 0, 1); return np.where(c <= 0.0031308, 12.92 * c, 1.055 * np.power(c, 1 / 2.4) - 0.055)
def hexlin(h): h = h.lstrip('#'); return s2l(np.array([int(h[i:i+2], 16) for i in (0, 2, 4)]) / 255.0).astype(np.float32)
M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566], [0.0883024619, 0.2817188376, 0.6299787005]], np.float32)
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099], [0.0259040371, 0.7827717662, -0.8086757660]], np.float32)
M3 = np.linalg.inv(M2).astype(np.float32); M4 = np.linalg.inv(M1).astype(np.float32)
def lin2lab(c): return np.cbrt(c @ M1.T) @ M2.T
def lab2lin(c): return np.maximum((c @ M3.T) ** 3 @ M4.T, 0)
def ramp(t, stops):
    """t (any shape) through [(pos, '#hex'), ...], interpolated in OKLab; returns linear RGB."""
    pos = np.array([p for p, _ in stops], np.float32); labs = np.stack([lin2lab(hexlin(c)) for _, c in stops])
    t = np.clip(t, pos[0], pos[-1]); out = np.empty(t.shape + (3,), np.float32)
    for ch in range(3): out[..., ch] = np.interp(t, pos, labs[:, ch])
    return lab2lin(out)

def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1); return t * t * (3 - 2 * t)
def over(dst, col, a):
    a = np.clip(a, 0, 1)[..., None]; return dst * (1 - a) + np.asarray(col, np.float32) * a

# ---------- noise ----------
class Perlin:
    def __init__(self, seed):
        r = np.random.default_rng(seed); p = r.permutation(256); self.p = np.concatenate([p, p])
        ang = r.random(256) * 2 * np.pi; self.g = np.stack([np.cos(ang), np.sin(ang)], 1).astype(np.float32)
    def __call__(self, x, y):
        xi, yi = np.floor(x).astype(np.int64), np.floor(y).astype(np.int64)
        xf, yf = (x - xi).astype(np.float32), (y - yi).astype(np.float32)
        xi &= 255; yi &= 255
        def grad(ix, iy, dx, dy):
            g = self.g[self.p[self.p[ix] + iy] & 255]; return g[..., 0] * dx + g[..., 1] * dy
        fade = lambda t: t * t * t * (t * (t * 6 - 15) + 10)
        u_, v_ = fade(xf), fade(yf)
        n00 = grad(xi, yi, xf, yf); n10 = grad(xi + 1, yi, xf - 1, yf)
        n01 = grad(xi, yi + 1, xf, yf - 1); n11 = grad(xi + 1, yi + 1, xf - 1, yf - 1)
        a = n00 + u_ * (n10 - n00); b = n01 + u_ * (n11 - n01)
        return (a + v_ * (b - a)) * 1.414
def fbm(noise, x, y, octaves=5, lac=2.0, gain=0.5):
    s, a, f, norm = 0.0, 1.0, 1.0, 0.0
    for i in range(octaves):
        s = s + a * noise(x * f + i * 17.3, y * f - i * 9.1); norm += a; a *= gain; f *= lac
    return s / norm
def fbm1(seed, x, octaves=6, gain=0.5):
    """1-D fractal noise along x (a row of the 2-D field)."""
    n = Perlin(seed); return fbm(n, x, np.full_like(x, 0.37), octaves, gain=gain)

# ---------- shapes ----------
def ridge_mask(c, ridge_v):
    """Coverage of the area below a ridge line ridge_v(u) (in v units), anti-aliased over one pixel."""
    px = 1.0 / c.H
    return np.clip((c.v - ridge_v[None, :]) / px + 0.5, 0, 1)
def glow(c, cx, cy, r, aspect_correct=True, power=2.0):
    dx = (c.u - cx); dy = (c.v - cy) * (c.aspect if aspect_correct else 1)
    d2 = (dx * dx + dy * dy) / (r * r); return np.exp(-0.5 * d2) if power == 2 else np.exp(-0.5 * d2 ** (power / 2))
def stars(c, img, n, v_max, seed, mag=(0.2, 1.0), size=(0.7, 1.6), tint=None, mask=None):
    r = np.random.default_rng(seed)
    xs, ys = r.random(n) * c.W, (r.random(n) ** 1.3) * v_max * c.H
    b = mag[0] + (mag[1] - mag[0]) * r.random(n) ** 3
    sz = (size[0] + (size[1] - size[0]) * r.random(n) ** 2) * c.s
    temp = r.random(n)
    for x, y, br, sg, t in zip(xs, ys, b, sz, temp):
        R = int(math.ceil(sg * 3.5)); x0, x1 = max(0, int(x) - R), min(c.W, int(x) + R + 1); y0, y1 = max(0, int(y) - R), min(c.H, int(y) + R + 1)
        if x0 >= x1 or y0 >= y1: continue
        yy, xx = np.mgrid[y0:y1, x0:x1]
        k = np.exp(-0.5 * (((xx + 0.5 - x) ** 2 + (yy + 0.5 - y) ** 2) / (sg * sg))) * br
        if mask is not None: k = k * mask[y0:y1, x0:x1]
        col = np.array([0.85 + 0.15 * t, 0.9, 1.0 - 0.12 * t], np.float32) if tint is None else tint
        img[y0:y1, x0:x1] += k[..., None] * col
    return img

# ---------- finishing ----------
def edges(c, img, top_band=0.05, bottom_band=0.04):
    """Top and bottom rows become flat colours (the bars' colours), eased in, so the bars meet the page without a seam
    whatever the crop. Returns the image and the two colours as #hex."""
    ti = int(top_band * c.H); bi = int((1 - bottom_band) * c.H)
    top = img[:3].reshape(-1, 3).mean(0); bot = img[-3:].reshape(-1, 3).mean(0)
    wt = 1 - smooth(0.0, top_band, c.v); wb = smooth(1 - bottom_band, 1.0, c.v)
    img = over(img, top, wt); img = over(img, bot, wb)
    tohex = lambda l: '#%02X%02X%02X' % tuple(int(round(float(x) * 255)) for x in l2s(l))
    return img, tohex(top), tohex(bot)
def finish(c, img, grain=0.6, seed=9):
    rgb = l2s(img.astype(np.float64))
    r = np.random.default_rng(seed)
    noise = r.normal(0, grain / 255, (c.H, c.W, 1)) + (r.random((c.H, c.W, 3)) - r.random((c.H, c.W, 3))) / 255
    return np.clip(np.floor((rgb + noise) * 255 + 0.5), 0, 255).astype(np.uint8)
