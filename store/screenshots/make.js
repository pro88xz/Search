// Google Play phone screenshots (1080 x 1920) for Search.
//
// Each frame: the brand's purple, the owl, a headline with its search term
// in a pill, a line under it, and a real screenshot from the phone in a
// device frame that runs off the bottom edge. The screenshots go in
// captures/ as 01.png ... 08.png (see README.md for what each one shows);
// the frames come out in out/.
//
//   node store/screenshots/make.js            all of them, and the intro
//   node store/screenshots/make.js 2 5        just those
//   node store/screenshots/make.js intro      just the landscape intro
//   node store/screenshots/make.js feature    just the feature graphic
//
// The feature graphic (1024 x 500, the banner at the top of the listing):
// the name, "Web Browser", one line and three things Search does, beside the
// real owl, large, in rings of light. No screenshots, so nothing in it can
// show a face or another company's logo; key content stays clear of the
// edges.
//
// The intro is landscape (1920 x 1080): it opens the listing for the video
// downloader and offline reading with both phones side by side, and leads
// on into the upright frames that show each one (09, 10). Upload it first.
// CAPTURES=<folder> takes the screenshots from somewhere else (a preview).
//
// Needs Playwright (npm i playwright) and the Inter Display font.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const dir = __dirname;
const captures = process.env.CAPTURES || path.join(dir, 'captures');
const FRAMES = [
  { head: 'Private browser with [ad blocker]', sub: 'Fewer ads and trackers on the sites you visit.' },
  { head: '[Top stories] on your home page', sub: 'Tech, world news and more from named publishers.' },
  { head: 'Smart [web search]', sub: 'Suggestions as you type, voice search and QR scanner.' },
  { head: '[Night Owl] private browsing', sub: 'No history, cookies or form data saved.' },
  { head: 'Three ways to [browse]', sub: 'Explore, Focus or Zen. Switch any time.' },
  { head: 'Every tool [one tap] away', sub: 'Tabs, Night Owl, page tools and more in one short menu.' },
  { head: 'Switch between [tabs] in a tap', sub: 'Every open page at a glance.' },
  { head: 'Search [widget] for your home screen', sub: 'Search, voice and QR scan in one tap.' },
  { head: 'Built-in [video downloader]', sub: 'Save video files from websites to your phone.' },
  { head: 'Read [offline]', sub: 'Save any page with its pictures. No connection needed.' },
];

// The landscape intro: its headline and line, and the captures it shows.
const INTRO = {
  head: 'Save [videos]<br>Read [offline]',
  sub: 'A video downloader and offline reading, built into a private browser.',
  next: 'See how',
  shots: ['09', '10'],
};

// The feature graphic: the name, what it is, one line, and three things it
// does, beside the owl itself (owl-hero.png, cut from design/owl.png).
const FEATURE = {
  name: 'Search',
  kind: 'Web Browser',
  line: 'Fast. Private. Secure.',
  points: [
    { icon: 'shield', title: 'Ad blocker', sub: 'Fewer ads and<br>trackers' },
    { icon: 'moon', title: 'Night Owl', sub: 'Private tabs,<br>nothing saved' },
    { icon: 'download', title: 'Save videos', sub: 'And read pages<br>offline' },
  ],
};

// Line icons for the feature graphic's points, drawn on a 24 x 24 grid.
const ICONS = {
  shield: '<path d="M12 3l7 3v5c0 4.6-3 8.4-7 10-4-1.6-7-5.4-7-10V6l7-3z"/><path d="M8.6 12.1l2.3 2.3 4.5-4.7"/>',
  moon: '<path d="M19.5 14.6A7.6 7.6 0 0 1 9.4 4.5a7.6 7.6 0 1 0 10.1 10.1z"/><path d="M16.5 3.5v3M15 5h3"/>',
  download: '<path d="M12 3.5v11M7.5 10L12 14.5 16.5 10"/><path d="M4.5 15.5v3a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2v-3"/>',
};

function feature(f) {
  const owl = dataUrl(path.join(dir, 'owl-hero.png'));
  const points = f.points.map(p => `<div class="pt"><span class="ic"><svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">${ICONS[p.icon]}</svg></span><b>${p.title}</b><small>${p.sub}</small></div>`).join('');
  const rings = [150, 205, 262, 322, 386].map((r, i) => `<circle cx="790" cy="262" r="${r}" fill="none" stroke="#C4AEFF" stroke-opacity="${[0.22, 0.17, 0.12, 0.08, 0.05][i]}" stroke-width="1.5"/>`).join('');
  const stars = [[512, 64, 2], [596, 112, 1.5], [470, 196, 1.2], [968, 74, 1.8], [944, 428, 1.4], [612, 452, 1.6], [372, 40, 1.2], [1000, 250, 1.2]]
    .map(([x, y, r]) => `<circle cx="${x}" cy="${y}" r="${r}" fill="#fff" fill-opacity=".55"/>`).join('');
  return `<!doctype html><meta charset="utf-8"><style>
  html,body{margin:0;width:1024px;height:500px;overflow:hidden}
  body{position:relative;font-family:'Inter Display','Inter',sans-serif;color:#fff;
    background:radial-gradient(330px 330px at 790px 262px,rgba(120,70,245,.55) 0%,rgba(104,58,232,.26) 50%,rgba(100,50,230,0) 100%),
               radial-gradient(60% 80% at 0% 0%,rgba(110,60,230,.45) 0%,rgba(110,60,230,0) 70%),
               linear-gradient(115deg,#13073D 0%,#1D0A58 45%,#2A1080 100%)}
  svg.sky{position:absolute;inset:0}
  .owl{position:absolute;left:628px;top:66px;height:396px;
    filter:drop-shadow(0 0 2px rgba(222,206,255,.7)) drop-shadow(0 0 26px rgba(170,132,255,.55)) drop-shadow(0 26px 34px rgba(8,0,36,.55))}
  .copy{position:absolute;left:62px;top:52px;width:560px}
  h1{margin:0;font-weight:800;font-size:108px;line-height:.98;letter-spacing:-0.04em}
  h2{margin:4px 0 0;font-weight:800;font-size:50px;line-height:1.05;letter-spacing:-0.025em;color:#B9A0FF}
  p{margin:18px 0 0;font-size:25px;font-weight:600;letter-spacing:-0.005em;color:rgba(255,255,255,.94)}
  .pts{position:absolute;left:62px;top:332px;display:flex}
  .pt{width:170px;display:flex;flex-direction:column;align-items:center;text-align:center;padding:0 6px}
  .pt + .pt{border-left:1px solid rgba(196,174,255,.24)}
  .ic{width:52px;height:52px;border-radius:50%;display:flex;align-items:center;justify-content:center;
    background:linear-gradient(160deg,#7C4DFF,#5426D6);box-shadow:0 0 0 1.5px rgba(205,186,255,.38),0 10px 22px -8px rgba(124,77,255,.8)}
  .ic svg{width:26px;height:26px}
  b{margin-top:11px;font-size:17.5px;font-weight:700;letter-spacing:-0.01em}
  small{margin-top:4px;font-size:13.5px;font-weight:500;line-height:1.3;color:rgba(222,212,255,.8)}
  </style>
  <svg class="sky" width="1024" height="500">${rings}${stars}</svg>
  <img class="owl" src="${owl}">
  <div class="copy"><h1>${f.name}</h1><h2>${f.kind}</h2><p>${f.line}</p></div>
  <div class="pts">${points}</div>`;
}

function findCapture(n) {
  return ['png', 'jpg', 'jpeg'].map(e => path.join(captures, n + '.' + e)).find(fs.existsSync);
}

function dataUrl(file) {
  const mime = /\.jpe?g$/i.test(file) ? 'image/jpeg' : (/\.webp$/i.test(file) ? 'image/webp' : 'image/png');
  return 'data:' + mime + ';base64,' + fs.readFileSync(file).toString('base64');
}

function intro(f, shots) {
  const head = f.head.replace(/\[(.+?)\]/g, '<span class="pill">$1</span>');
  const owl = dataUrl(path.join(dir, 'owl.webp'));
  const phones = shots.map((s, i) => `<div class="phone p${i}"><div class="screen"><img src="${dataUrl(s)}"><span class="cam"></span></div></div>`).join('');
  return `<!doctype html><meta charset="utf-8"><style>
  html,body{margin:0;width:960px;height:540px;overflow:hidden}
  body{position:relative;font-family:'Inter Display','Inter',sans-serif;color:#fff;
    background:radial-gradient(70% 90% at 0% 0%,#8E5CF7 0%,rgba(142,92,247,0) 60%),
               radial-gradient(60% 80% at 100% 100%,#9A63F2 0%,rgba(154,99,242,0) 55%),
               linear-gradient(120deg,#3B138C 0%,#5B21C9 50%,#7A3FE6 100%)}
  .copy{position:absolute;left:64px;top:0;bottom:0;width:430px;display:flex;flex-direction:column;justify-content:center}
  .owl{width:58px;margin-bottom:22px}
  h1{margin:0;font-weight:800;font-size:50px;line-height:1.12;letter-spacing:-0.02em}
  .pill{display:inline-block;background:#fff;color:#5B21C9;border-radius:999px;padding:0 16px 3px;margin:3px 0;
    box-shadow:0 8px 24px -10px rgba(20,0,60,.5)}
  p{margin:18px 0 0;font-size:20px;font-weight:500;line-height:1.38;color:rgba(255,255,255,.88);max-width:400px}
  .next{margin-top:30px;display:inline-flex;align-items:center;gap:10px;font-size:17px;font-weight:700;
    color:#fff;opacity:.92}
  .next svg{width:26px;height:26px}
  .phone{position:absolute;width:236px;padding:9px;border-radius:40px;background:#0E0E12;
    box-shadow:0 30px 60px -20px rgba(10,0,40,.7),inset 0 0 0 2px #2A2A31}
  .screen{position:relative;height:500px;border-radius:31px;overflow:hidden;background:#fff}
  .screen img{display:block;width:100%}
  .cam{position:absolute;top:9px;left:50%;width:11px;height:11px;margin-left:-5.5px;border-radius:50%;background:#0E0E12}
  .p0{left:466px;top:72px;transform:rotate(-4deg)}
  .p1{left:672px;top:42px;transform:rotate(3deg)}
  </style>
  <div class="copy"><img class="owl" src="${owl}"><h1>${head}</h1><p>${f.sub}</p>
    <div class="next">${f.next}<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg></div></div>
  ${phones}`;
}

function page(f, capture) {
  const head = f.head.replace(/\[(.+?)\]/, '<span class="pill">$1</span>');
  const owl = 'data:image/webp;base64,' + fs.readFileSync(path.join(dir, 'owl.webp')).toString('base64');
  const mime = /\.jpe?g$/i.test(capture) ? 'image/jpeg' : 'image/png';
  const shot = 'data:' + mime + ';base64,' + fs.readFileSync(capture).toString('base64');
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
  const args = process.argv.slice(2);
  const want = args.map(Number).filter(Boolean);
  const onlyIntro = args.includes('intro');
  const onlyFeature = args.includes('feature');
  const browser = await chromium.launch();
  fs.mkdirSync(path.join(dir, 'out'), { recursive: true });
  if (onlyFeature || (want.length === 0 && !onlyIntro)) {
    const banner = await browser.newContext({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 });
    const t = await banner.newPage();
    await t.setContent(feature(FEATURE));
    await t.waitForTimeout(150);
    // JPEG: Play wants the feature graphic without transparency.
    await t.screenshot({ path: path.join(dir, 'out', 'feature-graphic.jpg'), type: 'jpeg', quality: 95 });
    console.log('feature: out/feature-graphic.jpg (1024 x 500)');
    await banner.close();
    if (onlyFeature) { await browser.close(); return; }
  }
  if (onlyIntro || want.length === 0) {
    const shots = INTRO.shots.map(findCapture);
    if (shots.some(s => !s)) console.log('intro: needs captures ' + INTRO.shots.join(' and ') + ', skipped');
    else {
      const wide = await browser.newContext({ viewport: { width: 960, height: 540 }, deviceScaleFactor: 2 });
      const t = await wide.newPage();
      await t.setContent(intro(INTRO, shots));
      await t.waitForTimeout(150);
      await t.screenshot({ path: path.join(dir, 'out', '00-intro.png') });
      console.log('intro: out/00-intro.png (1920 x 1080)');
      await wide.close();
    }
    if (onlyIntro) { await browser.close(); return; }
  }
  const ctx = await browser.newContext({ viewport: { width: 540, height: 960 }, deviceScaleFactor: 2 });
  const tab = await ctx.newPage();
  for (let i = 0; i < FRAMES.length; i++) {
    const n = String(i + 1).padStart(2, '0');
    if (want.length && !want.includes(i + 1)) continue;
    const capture = findCapture(n);
    if (!capture) { console.log(n + ': no capture, skipped'); continue; }
    await tab.setContent(page(FRAMES[i], capture));
    await tab.waitForTimeout(150);
    await tab.screenshot({ path: path.join(dir, 'out', n + '.png') });
    console.log(n + ': out/' + n + '.png');
  }
  await browser.close();
})();
