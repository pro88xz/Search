import sys, os, json, importlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scenes; from wplib import *
from PIL import Image
name, W, H, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
c = Canvas(W, H, seed=3)
img, tone = getattr(scenes, name)(c)
img, top, bot = edges(c, img)
Image.fromarray(finish(c, img)).save(out)
json.dump({'id': name, 'tone': tone, 'top': top, 'bottom': bot}, open(out + '.json', 'w'))
print(name, tone, top, bot)
