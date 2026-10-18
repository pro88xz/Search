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
Volume down takes each one. Save them as `captures/01.png` ... `08.png`:

| # | Headline | What to capture |
|---|----------|-----------------|
| 01 | Private browser with **ad blocker** | A busy news or recipe site, open and clean with ad blocking on |
| 02 | **Top stories** on your home page | Home scrolled into Top Stories, no close-up faces in view |
| 03 | Smart **web search** | Search open, typing a word (e.g. "weather"), suggestions showing |
| 04 | **Night Owl** private browsing | Night Owl on, its home page |
| 05 | Three ways to **browse** | Settings > Browsing mode, the three mode cards |
| 06 | Every tool **one tap** away | The menu open with the Page tools strip showing |
| 07 | Tabs, bookmarks and **downloads** | The tab switcher with three or four tabs open |
| 08 | Search **widget** for your home screen | The phone's home screen with the Search widget on it |

## Making the frames

    npm i playwright
    node store/screenshots/make.js        # all of them
    node store/screenshots/make.js 2 4    # only some

The frames are written to `out/`. The headlines and lines under them are in
`FRAMES` at the top of `make.js`; a word in [brackets] gets the pill.
