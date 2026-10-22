# Home wallpapers

The eight scenes in Settings > Customize your Search > Wallpaper, rendered from code: no AI, no
stock. Light is mixed in linear sRGB, gradients run through OKLab, land, sea, cloud and nebula come
from fractal gradient noise, and every edge is anti-aliased at 4K. Depth comes from layers fading
into haze (atmospheric perspective), perspective on water, 3-D shading and depth of field.

| id | name | tone |
|---|---|---|
| owl_night | Owl Night | dark |
| dawn_hills | Dawn Hills | light |
| cloud_sea | Above the Clouds | light |
| silk | Silk | light |
| ocean_dusk | Ocean Dusk | dark |
| aurora | Aurora | dark |
| bokeh | City Lights | dark |
| nebula | Nebula | dark |

Rebuild (Python 3 with numpy and Pillow):

```
python3 -I render.py owl_night 2160 3840 masters/owl_night.png    # one per id; writes <png>.json too
python3 -I encode.py masters ../../app/src/main/assets/wallpapers
```

`render.py` prints each scene's tone and its flat top and bottom colours. Those must match
`HomeWallpapers.kt`, which hands them to the bars over home. Each scene keeps the area behind
home's words calm: text on every wallpaper was measured at 3:1 contrast or better against the
pixels behind it (tile labels, Blocked this week, Top Stories, See all; 360 and 412 dp wide).
