// Google Play phone screenshots (1080 x 1920) for Search.
//
// Each frame: the brand's purple, the owl, a headline with its search term
// in a pill, a line under it, and a real screenshot from the phone in a
// device frame that runs off the bottom edge. The screenshots go in
// captures/ as 01.png ... 08.png (see README.md for what each one shows);
// the frames come out in out/.
//
//   node store/screenshots/make.js            all eight
//   node store/screenshots/make.js 2 5        just those
//
// Needs Playwright (npm i playwright) and the Inter Display font.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const FRAMES = [
  { head: 'Private browser with [ad blocker]', sub: 'Fewer ads and trackers on the sites you visit.' },
  { head: 'Top stories and [trending videos]', sub: 'News and YouTube videos on your home page.' },
  { head: 'Smart [web search]', sub: 'Suggestions as you type, voice search and QR scanner.' },
  { head: '[Night Owl] private browsing', sub: 'No history, cookies or form data saved.' },
  { head: 'Three ways to [browse]', sub: 'Explore, Focus or Zen. Switch any time.' },
  { head: 'Every tool [one tap] away', sub: 'Find, translate, reader mode and more.' },
  { head: 'Tabs, bookmarks and [downloads]', sub: 'Switch tabs, save pages and get files.' },
  { head: 'Search [widget] for your home screen', sub: 'Search, voice and QR scan in one tap.' },
];

function page(f, capture) {
  const head = f.head.replace(/\[(.+?)\]/, '<span class="pill">$1</span>');
  const owl = 'data:image/webp;base64,' + fs.readFileSync(path.join(dir, 'owl.webp')).toString('base64');
  const shot = 'data:image/png;base64,' + fs.readFileSync(capture).toString('base64');
  return `<!doctype html><meta charset="utf-8"><style>
  html,body{margin:0;width:540px;height:960px;overflow:hidden}
  body{position:relative;font-family:'Inter Display','Inter',sans-serif;text-align:center;color:#fff;
    background:radial-gradient(120% 60% at 50% 0%,#8E5CF7 0%,rgba(142,92,247,0) 60%),
               linear-gradient(165deg,#3B138C 0%,#5B21C9 48%,#7A3FE6 100%)}
  .owl{width:58px;margin-top:46px}
  h1{margin:16px auto 0;width:480px;font-weight:800;font-size:43px;line-height:1.12;letter-spacing:-0.02em}
  .pill{display:inline-block;background:#fff;color:#5B21C9;border-radius:999px;padding:0 16px 3px;margin:2px 0;
    box-shadow:0 8px 24px -10px rgba(20,0,60,.5)}
  p{margin:14px auto 0;width:470px;font-size:18.5px;font-weight:500;line-height:1.35;color:rgba(255,255,255,.88)}
  .phone{position:absolute;left:50%;top:316px;width:392px;margin-left:-208px;padding:12px;border-radius:52px;
    background:#0E0E12;box-shadow:0 30px 60px -20px rgba(10,0,40,.65),inset 0 0 0 2px #2A2A31}
  .screen{position:relative;height:720px;border-radius:40px;overflow:hidden;background:#fff}
  .screen img{display:block;width:100%}
  .cam{position:absolute;top:12px;left:50%;width:14px;height:14px;margin-left:-7px;border-radius:50%;background:#0E0E12}
  </style>
  <img class="owl" src="${owl}"><h1>${head}</h1><p>${f.sub}</p>
  <div class="phone"><div class="screen"><img src="${shot}"><span class="cam"></span></div></div>`;
}

(async () => {
  const want = process.argv.slice(2).map(Number).filter(Boolean);
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 540, height: 960 }, deviceScaleFactor: 2 });
  const tab = await ctx.newPage();
  for (let i = 0; i < FRAMES.length; i++) {
    const n = String(i + 1).padStart(2, '0');
    if (want.length && !want.includes(i + 1)) continue;
    const capture = ['png', 'jpg', 'jpeg'].map(e => path.join(dir, 'captures', n + '.' + e)).find(fs.existsSync);
    if (!capture) { console.log(n + ': no capture, skipped'); continue; }
    await tab.setContent(page(FRAMES[i], capture));
    await tab.waitForTimeout(150);
    await tab.screenshot({ path: path.join(dir, 'out', n + '.png') });
    console.log(n + ': out/' + n + '.png');
  }
  await browser.close();
})();
