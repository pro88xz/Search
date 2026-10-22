import numpy as np, math
from wplib import *

def mountains(c, img, layers, seed, haze, near, fog, v0, dv, amp0, damp, f0, df, fog_k=0.8, rim=None, rim_k=0.0, sharp=1.0):
    """Layers of ridges, far to near: each darker and clearer than the one behind, with mist lying in
    the valley in front of it - atmospheric perspective, where the depth comes from. Mist depends on
    height alone, so it lies in level bands, never in streaks."""
    hz, nr, fg = lin2lab(hexlin(haze)), lin2lab(hexlin(near)), hexlin(fog)
    for i in range(layers):
        t = (i / (layers - 1)) ** 0.9
        col = lab2lin(hz + (nr - hz) * t)
        x = c.u[0] * (f0 + i * df) + i * 3.7
        macro = fbm1(seed + i, x * 0.35, octaves=3, gain=0.5) * 0.5 + 0.5
        detail = 1 - np.abs(fbm1(seed + 50 + i, x, octaves=5, gain=0.48))
        h = 0.62 * macro + 0.38 * detail ** (1.6 * sharp)
        ridge = v0 + i * dv - (amp0 + i * damp) * h
        m = ridge_mask(c, ridge)
        base = v0 + i * dv
        mist = smooth(base - 0.5 * (amp0 + i * damp), base + 0.04, c.v) * fog_k * (1 - 0.75 * t)
        if i == layers - 1: mist = mist * (1 - smooth(base, base + 0.12, c.v))   # the nearest ground clears and darkens
        layer = over(np.broadcast_to(col, img.shape).copy(), fg, mist)
        if rim is not None:
            depth = np.clip(c.v - ridge[None, :], 0, None)
            layer = layer + np.asarray(hexlin(rim)) * (np.exp(-depth / (0.0012 + 0.0003 * i)) * rim_k * (1 - t * 0.6))[..., None]
        img = img * (1 - m[..., None]) + layer * m[..., None]
    return img

def owl_night(c):
    img = ramp(c.v, [(0, '#0E0A26'), (0.3, '#221650'), (0.5, '#3E2A78'), (0.62, '#7456A8'), (0.7, '#A487CC')])
    mx, my, mr = 0.83, 0.088, 0.042
    img = img + hexlin('#B9A2F0') * ((0.32 * glow(c, mx, my, 0.20) + 0.32 * glow(c, mx, my, 0.07)) * smooth(0.0, 0.11, c.v))[..., None]
    sky_mask = 1 - smooth(0.42, 0.64, c.v)
    d = np.sqrt(((c.u - mx)) ** 2 + ((c.v - my) * c.aspect) ** 2)
    img = stars(c, img, int(2600 * c.s * c.s), 0.62, 11, mag=(0.04, 1.1), mask=sky_mask * smooth(mr * 1.6, mr * 3.5, d) * smooth(0.02, 0.07, c.v))
    img = stars(c, img, int(40 * c.s * c.s), 0.5, 12, mag=(0.6, 1.4), size=(1.6, 2.6), mask=sky_mask * smooth(mr * 2, mr * 4, d) * smooth(0.03, 0.08, c.v))
    # The moon: a soft-edged disc, limb-darkened, with faint maria.
    edge = np.clip((mr - d) * c.W + 0.5, 0, 1)
    n = fbm(Perlin(5), c.u * 40, c.v * 40 * c.aspect, 4)
    limb = np.sqrt(np.clip(1 - (d / mr) ** 2, 0, 1))
    moon = hexlin('#F6F0E2') * (0.78 + 0.22 * limb)[..., None] * (1 - 0.10 * np.clip(n + 0.2, 0, 1))[..., None]
    img = img * (1 - edge[..., None]) + moon * edge[..., None]
    img = mountains(c, img, 6, 30, haze='#8F75C4', near='#0D071C', fog='#B79FE0', v0=0.64, dv=0.056, amp0=0.10, damp=0.016,
                    f0=1.5, df=0.65, rim='#D9C8FF', rim_k=0.30, sharp=1.3)
    img = img * (1 - 0.55 * smooth(0.82, 1.0, c.v))[..., None]
    return img, 'dark'

def blur1(a, sigma):
    """Gaussian blur of a 1-D array (in samples)."""
    r = int(sigma * 3) + 1; k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2); k /= k.sum()
    return np.convolve(np.pad(a, r, mode='edge'), k, mode='valid')

def hills(c, img, layers, seed, far, near, haze, v0, dv, amp, f0, sun_x, light=0.3, haze_k=0.45):
    """Rolling hills, far to near. Each face is lit by how it turns toward the sun - the slope of a
    smoothed crest, so the light changes across a hill, never in stripes."""
    fl, nl = lin2lab(hexlin(far)), lin2lab(hexlin(near))
    for i in range(layers):
        t = (i / (layers - 1)) ** 0.85
        x = c.u[0] * (f0 + 0.35 * i) + i * 4.1
        crest = v0 + i * dv - (amp + 0.012 * i) * (fbm1(seed + i, x, octaves=3, gain=0.42) * 0.5 + 0.5)
        m = ridge_mask(c, crest)
        slope = blur1(np.gradient(crest) * c.W, c.W * 0.03)
        toward = np.tanh((sun_x - c.u[0]) * 6)                # which way the sun is, turning smoothly beneath it
        lit = np.clip(0.5 + slope * toward * -8, 0, 1)
        depth = np.clip(c.v - crest[None, :], 0, None)
        col = lab2lin(fl + (nl - fl) * t)
        shade = 1 + light * (lit[None, :] - 0.5) * np.exp(-depth / 0.06)
        mist = smooth(v0 + i * dv - amp, v0 + i * dv + 0.05, c.v) * haze_k * (1 - 0.8 * t)
        layer = over(col * shade[..., None], hexlin(haze), mist)
        img = img * (1 - m[..., None]) + layer * m[..., None]
    return img

def dawn_hills(c):
    """Dawn over rolling hills: lavender overhead, apricot at the horizon, a low sun behind haze,
    and hills stepping away into it."""
    img = ramp(c.v, [(0, '#BBA8EA'), (0.22, '#D2BEF0'), (0.42, '#EECBDD'), (0.56, '#FAD6C3'), (0.62, '#FFE4C6')])
    sx, sy = 0.68, 0.585
    img = img + hexlin('#FFE6C0') * ((0.85 * glow(c, sx, sy, 0.18) + 0.6 * glow(c, sx, sy, 0.045)) * smooth(0.0, 0.25, c.v))[..., None]
    d = np.sqrt((c.u - sx) ** 2 + ((c.v - sy) * c.aspect) ** 2)
    img = over(img, hexlin('#FFF6E8'), np.clip((0.030 - d) * c.W + 0.5, 0, 1) * 0.85)
    img = hills(c, img, 6, 120, far='#E7C3CF', near='#5F4A86', haze='#FBE2D6', v0=0.60, dv=0.068, amp=0.05, f0=1.3, sun_x=sx, light=0.35)
    img = img * (1 - 0.30 * smooth(0.84, 1.0, c.v))[..., None]
    return img, 'light'

def aurora(c):
    """Northern lights over a still lake: folded curtains of green fading to violet at their tops,
    stars, the far shore, and the sky again in the water."""
    horizon = 0.64
    img = ramp(c.v, [(0, '#040916'), (0.3, '#081630'), (0.55, '#0E2A48'), (horizon, '#17405C')])
    p = Perlin(41)
    for k, (base, amp, w, k_int) in enumerate([(0.36, 0.07, 0.05, 1.0), (0.27, 0.05, 0.04, 0.7), (0.45, 0.04, 0.03, 0.55)]):
        curve = base + amp * fbm1(90 + k, c.u[0] * 1.8 + k, 4) + 0.025 * np.sin(c.u[0] * 4.2 + 1.7 * k)
        dy = c.v - curve[None, :]
        # rays: bright streaks rising from the fold, varying slowly along it
        env = (fbm1(150 + k, c.u[0] * 1.6, 3) * 0.5 + 0.5)[None, :] ** 2.2
        rays = (fbm(p, c.u * 22 + 7 * k, np.full_like(c.u, 3.3 * k) + c.v * 0.6, 4) * 0.5 + 0.5) ** 1.8 * (0.25 + 1.5 * env)
        body = np.where(dy > 0, np.exp(-dy / 0.012), np.exp(dy / (w * (1.6 + 2.5 * rays))))
        hue = smooth(-0.02, -0.22, dy)                               # green at the fold, violet higher up
        col = hexlin('#33F0A6')[None, None] * (1 - hue[..., None]) + hexlin('#9A6BFF')[None, None] * hue[..., None]
        img = img + col * (body * (0.25 + 1.1 * rays) * 0.5 * k_int * smooth(0.02, 0.14, c.v))[..., None]
    sky_only = (1 - smooth(horizon - 0.08, horizon, c.v))
    img = stars(c, img, int(2400 * c.s * c.s), horizon, 42, mag=(0.03, 0.9), mask=smooth(0.03, 0.08, c.v) * sky_only)
    img = mountains(c, img, 3, 44, haze='#21465E', near='#050D17', fog='#2E5D74', v0=horizon - 0.002, dv=0.004, amp0=0.06, damp=0.015, f0=1.8, df=0.8, fog_k=0.25, sharp=1.2)
    rows = np.arange(c.H); hz = int(horizon * c.H)
    src = np.clip(2 * hz - rows, 0, c.H - 1)
    rip = Perlin(47)
    wob = fbm(rip, c.u * 25, c.v * 260, 3) * 7 * c.s
    xs = np.clip(np.arange(c.W)[None, :] + wob.astype(int), 0, c.W - 1)
    refl = img[src[:, None], xs]
    fade = (0.78 * np.exp(-(c.v - horizon) / 0.30))[..., None]
    img = np.where((rows >= hz)[:, None, None], refl * fade + hexlin('#030810') * (1 - fade), img)
    img = img * (1 - 0.45 * smooth(0.86, 1.0, c.v))[..., None]
    return img, 'dark'

def ocean_dusk(c):
    """Dusk at sea: the sun just above the horizon, rose and violet sky, and water whose waves
    shrink with distance - perspective - with a path of light coming toward you."""
    hz = 0.74
    img = ramp(c.v, [(0, '#1A1238'), (0.32, '#33205E'), (0.55, '#6E3E7E'), (0.67, '#E2848A'), (hz, '#FFB98C')])
    sx, sy = 0.5, hz - 0.03
    img = img + hexlin('#FFC9A0') * ((0.8 * glow(c, sx, sy, 0.16) + 0.8 * glow(c, sx, sy, 0.05)) * smooth(0.0, 0.25, c.v))[..., None]
    d = np.sqrt((c.u - sx) ** 2 + ((c.v - sy) * c.aspect) ** 2)
    img = over(img, hexlin('#FFF1DC'), np.clip((0.04 - d) * c.W + 0.5, 0, 1))
    # a few long clouds lit from below
    cl = fbm(Perlin(61), c.u * 3, c.v * 22, 5) * 0.5 + 0.5
    cloud = smooth(0.55, 0.75, cl) * smooth(0.52, 0.6, c.v) * (1 - smooth(0.64, 0.7, c.v))
    img = over(img, hexlin('#F2A08E') * 0.9, cloud * 0.55)
    # the sea, in perspective: z grows toward the horizon
    below = c.v > hz
    z = 1.0 / np.maximum(c.v - hz, 1e-4)
    wx, wz = (c.u - 0.5) * z * 0.9, z * 0.9
    wave = fbm(Perlin(63), wx * 3.0, wz * 14.0, 5)
    sea = ramp(c.v, [(hz, '#7A4A7E'), (hz + 0.04, '#3E2A5C'), (0.88, '#22183C'), (1.0, '#120C22')])
    sea = sea * (1 + 0.18 * wave)[..., None]
    path = np.exp(-0.5 * ((c.u - sx) / (0.02 + 0.5 * (c.v - hz))) ** 2)
    glint = np.clip(wave * 3.0 - 0.9, 0, 1) ** 1.5 * path
    sea = sea + hexlin('#FFD2A6') * (glint * 1.6 + path * 0.18 * np.exp(-(c.v - hz) / 0.15))[..., None]
    img = np.where(below[..., None], sea, img)
    img = over(img, hexlin('#FFC8A0'), np.exp(-np.abs(c.v - hz) * c.H / 3) * 0.6)
    return img, 'dark'

def silk(c):
    """Satin in the owl's lavender: a few broad folds of cloth, lit from the upper left, deep violet in
    the hollows and a bright sheen running along each crest."""
    x, y = c.u, c.v * c.aspect
    p = Perlin(77)
    wx = x + 0.05 * fbm(p, x * 1.2, y * 1.2, 3)
    wy = y + 0.05 * fbm(p, x * 1.2 + 5, y * 1.2 + 5, 3)
    along = wx * 0.85 + wy * 0.53                               # folds run diagonally...
    across = -wx * 0.53 + wy * 0.85                             # ...and bend as they go
    h = 0.05 * np.sin(along * 11.0 + 1.6 * np.sin(across * 2.6 + 0.6)) + 0.02 * np.sin(along * 19.0 + 2.4 * np.sin(across * 1.9 + 2.0) + 1.0)
    gy, gx = np.gradient(h)
    du, dv = gx * c.W, gy * c.H / c.aspect                      # d/dx, d/dy in the same units
    nx, ny, nz = -du, -dv, np.ones_like(h)
    norm = np.sqrt(nx * nx + ny * ny + nz * nz); nx, ny, nz = nx / norm, ny / norm, nz / norm
    L = np.array([-0.5, -0.6, 0.62]); L = L / np.linalg.norm(L)
    diff = np.clip(nx * L[0] + ny * L[1] + nz * L[2], 0, 1)
    Hh = L + np.array([0, 0, 1.0]); Hh = Hh / np.linalg.norm(Hh)
    spec = np.clip(nx * Hh[0] + ny * Hh[1] + nz * Hh[2], 0, 1) ** 24
    base = ramp(diff, [(0, '#A592DD'), (0.5, '#C4B5EE'), (0.82, '#E2D9F8'), (1, '#F7F3FE')])
    img = base + hexlin('#FFF6EE') * (spec * 0.45)[..., None]
    img = img + hexlin('#F7C9A8') * (glow(c, 0.9, 0.85, 0.3) * 0.12)[..., None]
    img = over(img, hexlin('#D9CFF4'), (1 - smooth(0.0, 0.2, c.v)) * 0.55)   # the top settles under the bar
    return img, 'light'

def bokeh(c):
    """Night lights out of focus, as through a fast lens: a river of amber and violet discs - small
    and crisp far away, large and soft close up - glowing where they overlap."""
    img = ramp(c.v, [(0, '#0A0716'), (0.4, '#171030'), (0.75, '#22133A'), (1, '#0E0819')])
    img = img + hexlin('#4E2F96') * (glow(c, 0.55, 0.62, 0.42) * 0.3)[..., None]
    r = np.random.default_rng(88)
    cols = [hexlin('#FFB24A'), hexlin('#FF9447'), hexlin('#FFC978'), hexlin('#A97BFF'), hexlin('#C29BFF')]
    for li, (n, rmin, rmax, soft, alpha) in enumerate([(170, 0.005, 0.011, 0.30, 0.42), (60, 0.016, 0.03, 0.14, 0.26), (14, 0.05, 0.085, 0.08, 0.12)]):
        for _ in range(n):
            t = r.random()
            cx = -0.05 + 1.1 * t + 0.08 * r.normal()
            cy = 0.35 + 0.45 * t + 0.10 * np.sin(t * 6.0) + 0.07 * r.normal()     # a band running down and across
            if not (0.1 < cy < 1.05): continue
            rad = rmin + (rmax - rmin) * r.random()
            col = (cols[3 + r.integers(2)] if li == 2 else cols[r.integers(len(cols))]) * (1.0 + 0.8 * r.random())   # the nearest are cool
            y0, y1 = max(0, int((cy - rad * 1.3 / c.aspect) * c.H)), min(c.H, int((cy + rad * 1.3 / c.aspect) * c.H) + 1)
            x0, x1 = max(0, int((cx - rad * 1.3) * c.W)), min(c.W, int((cx + rad * 1.3) * c.W) + 1)
            if x0 >= x1 or y0 >= y1: continue
            dd = np.sqrt((c.u[y0:y1, x0:x1] - cx) ** 2 + ((c.v[y0:y1, x0:x1] - cy) * c.aspect) ** 2) / rad
            disc = 1 - smooth(1 - soft, 1 + soft * 0.4, dd)
            body = disc * (0.7 + 0.3 * dd ** 2)                               # a touch brighter toward the rim, as real bokeh is
            img[y0:y1, x0:x1] += col * (body * alpha)[..., None]
    img = img * (1 - 0.35 * smooth(0.55, 1.1, np.sqrt((c.u - 0.5) ** 2 + (c.v - 0.55) ** 2)))[..., None]
    return img, 'dark'

def cloud_sea(c):
    """Above a sea of clouds at sunrise: banks of cloud stepping away to the horizon, lit warm on
    their tops, cool in their hollows."""
    hz = 0.52
    img = ramp(c.v, [(0, '#A9B8F0'), (0.25, '#C5C6F2'), (0.42, '#F1CFE0'), (hz, '#FFE0C2')])
    sx, sy = 0.25, hz - 0.02
    img = img + hexlin('#FFE9C8') * ((0.9 * glow(c, sx, sy, 0.2) + 0.6 * glow(c, sx, sy, 0.04)) * smooth(0.0, 0.25, c.v))[..., None]
    p = Perlin(91)
    layers = 7
    far, near = lin2lab(hexlin('#F6DCE4')), lin2lab(hexlin('#9AA0D8'))
    for i in range(layers):
        t = i / (layers - 1)
        x = c.u[0] * (2.0 + 1.1 * i) + i * 3.3
        bumps = fbm1(140 + i, x, 3, 0.45) * 0.5 + 0.5
        top = hz + 0.06 * i ** 1.25 - (0.025 + 0.014 * i) * bumps ** 0.7
        edge_n = fbm(p, c.u * (18 + 6 * i), c.v * (40 + 10 * i) + i, 3) * (0.004 + 0.0015 * i)
        m = smooth(-0.002, 0.004 + 0.001 * i, c.v - top[None, :] + edge_n)
        depth = np.clip(c.v - top[None, :], 0, None)
        billow = fbm(p, c.u * (6 + 4 * i) + i, c.v * (14 + 6 * i), 4) * 0.5 + 0.5
        col = lab2lin(far + (near - far) * t ** 0.9)
        lit = np.exp(-depth / (0.012 + 0.006 * i)) * (0.55 + 0.45 * billow)
        warm = hexlin('#FFE7CF') * (lit * (1 - 0.5 * t) * 0.9)[..., None]
        layer = col * (0.82 + 0.25 * billow)[..., None] + warm
        img = img * (1 - m[..., None]) + layer * m[..., None]
    img = img * (1 - 0.18 * smooth(0.8, 1.0, c.v))[..., None]
    return img, 'light'

def nebula(c):
    """Deep space: a violet and blue nebula over a field of stars at three depths, and the edge of a
    planet with the sun's light on its rim."""
    x, y = c.u, c.v * c.aspect
    p = Perlin(101)
    wx = x + 0.25 * fbm(p, x * 2, y * 2, 4); wy = y + 0.25 * fbm(p, x * 2 + 9, y * 2 + 9, 4)
    n1 = fbm(Perlin(102), wx * 2.5, wy * 2.5, 6) * 0.5 + 0.5
    n2 = fbm(Perlin(103), wx * 4.0 + 3, wy * 4.0, 5) * 0.5 + 0.5
    img = ramp(c.v, [(0, '#03030A'), (0.5, '#070514'), (1, '#04030C')])
    band = np.exp(-((c.u - 0.3 - 0.35 * c.v) ** 2) / 0.08)
    fil = (1 - np.abs(fbm(Perlin(108), wx * 6, wy * 6, 5))) ** 6
    img = img + hexlin('#6A3BD1') * (smooth(0.45, 0.85, n1) ** 1.5 * band * 1.1)[..., None]
    img = img + hexlin('#C77DFF') * (fil * smooth(0.4, 0.8, n1) * band * 0.9)[..., None]
    img = img + hexlin('#2E7BFF') * (smooth(0.55, 0.9, n2) ** 2 * band * 0.6)[..., None]
    img = img + hexlin('#FF7BC8') * (smooth(0.7, 0.95, n1 * n2 * 1.4) * band * 0.35)[..., None]
    img = img * (1 - 0.5 * smooth(0.5, 0.2, n1 * band))[..., None]   # dark lanes of dust
    img = stars(c, img, int(3500 * c.s * c.s), 1.0, 104, mag=(0.03, 0.6), size=(0.6, 1.0))
    img = stars(c, img, int(500 * c.s * c.s), 1.0, 105, mag=(0.2, 1.0), size=(1.0, 1.6))
    img = stars(c, img, int(25 * c.s * c.s), 1.0, 106, mag=(0.8, 1.6), size=(1.8, 2.8))
    # the planet, low left, lit from the upper right
    px_, py_, pr = 0.05, 1.02, 0.55
    dx, dy = (c.u - px_), (c.v - py_) * c.aspect
    d = np.sqrt(dx * dx + dy * dy)
    inside = np.clip((pr - d) * c.W + 0.5, 0, 1)
    nz = np.sqrt(np.clip(1 - (d / pr) ** 2, 0, 1))
    nxp, nyp = dx / pr, dy / pr
    L = np.array([0.6, -0.55, 0.58]); L = L / np.linalg.norm(L)
    lam = np.clip(nxp * L[0] + nyp * L[1] + nz * L[2], 0, 1)
    surf = fbm(Perlin(107), (c.u * 6), (c.v * 30), 4) * 0.5 + 0.5
    pcol = ramp(surf, [(0, '#2B2357'), (0.5, '#4A3A86'), (1, '#7B67B8')])
    planet = pcol * (0.03 + 1.1 * lam ** 1.2)[..., None]
    img = img * (1 - inside[..., None]) + planet * inside[..., None]
    rim = np.exp(-np.abs(d - pr) / 0.006) * np.clip(lam + 0.25 * (d >= pr), 0, 1)
    img = img + hexlin('#8FB4FF') * (rim * 0.9)[..., None]
    return img, 'dark'
