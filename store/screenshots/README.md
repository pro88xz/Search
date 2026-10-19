# Google Play screenshots

`make.js` puts a real phone screenshot into each store frame: the brand's
purple, the owl, a headline with its search term in a white pill, a line
under it, and the phone running off the bottom edge. Output is 1080 x 1920
(9:16, eligible for Play's promoted placements), up to eight frames.

Every frame must show the real app (Google Play policy), so the screens come
from a phone, not from a mock-up.

## Taking the screenshots

On the phone: light theme, the default purple accent, Explore mode, a full
battery and no notifications if possible. Nothing private on screen, and no
ad in view (a store screenshot showing an ad looks like a test). Power +
Volume down takes each one. Save them as `captures/01.png` ... `10.png`:

| # | Headline | What to capture |
|---|----------|-----------------|
| 01 | Private browser with **ad blocker** | A busy news or recipe site, open and clean with ad blocking on |
| 02 | **Top stories** on your home page | Home scrolled into Top Stories, no close-up faces in view |
| 03 | Smart **web search** | Search open, typing a word (e.g. "weather"), suggestions showing |
| 04 | **Night Owl** private browsing | Night Owl on, its home page |
| 05 | Three ways to **browse** | Settings > Browsing mode, the three mode cards |
| 06 | Every tool **one tap** away | The menu open on the home page |
| 07 | Switch between **tabs** in a tap | The tab switcher with three or four neutral tabs open (no well-known people, adult topics, artists' videos or cookie pop-ups) |
| 08 | Search **widget** for your home screen | The phone's home screen with the Search widget on it |
| 09 | Built-in **video downloader** | A free stock-video page (landscape or nature footage, no people, never YouTube) with the Download video button showing above the bar, still in its long form |
| 10 | Read **offline** | An article saved with Save offline, opened from Offline pages in flight mode, title and first picture in view |

## The feature graphic

`out/feature-graphic.jpg` (1024 x 500, JPEG so there is no transparency)
is the banner at the top of the listing: "Search", "Web Browser", "Fast.
Private. Secure." and three things it does (Ad blocker, Night Owl, Save
videos), beside the real owl, large, in rings of light. It needs no
captures: the owl is `owl-hero.png`, cut from `design/owl.png`, and the
icons are drawn in make.js. Upload it in Play Console > Main store listing >
Graphics > Feature graphic, replacing the old one.

    node store/screenshots/make.js feature   # only the feature graphic

## The landscape intro

`out/00-intro.png` (1920 x 1080) opens the listing for the video downloader
and offline reading: "Save videos / Read offline" with captures 09 and 10
side by side and "See how ->" leading on to the upright frames. In Play
Console put it first, then 09 and 10, then 01 to 08. Play takes landscape
and portrait phone screenshots in the same listing.

    node store/screenshots/make.js intro   # only the intro (needs 09 and 10)

## Making the frames

    npm i playwright
    node store/screenshots/make.js        # all of them
    node store/screenshots/make.js 2 4    # only some

The frames are written to `out/`. The headlines and lines under them are in
`FRAMES` at the top of `make.js`; a word in [brackets] gets the pill.
