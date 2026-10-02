package com.search.browser

/**
 * JavaScript injected into every page to detect HTML5 media playback and
 * report state changes to the app via SearchApp.mediaState(...).
 * Also exposes window.__searchMediaControl(action) so the notification's
 * play/pause can drive the page's media element.
 */
object MediaDetect {
    fun js(): String = """
(function(){
  if (window.__searchMediaInit) return;
  window.__searchMediaInit = true;

  function pickMedia(){
    var list = document.querySelectorAll('video,audio');
    for (var i=0;i<list.length;i++){
      var m = list[i];
      if (!m.paused && !m.ended && m.currentTime > 0) return m;
    }
    return list.length ? list[0] : null;
  }

  function report(){
    try {
      var m = pickMedia();
      if (!m){ SearchApp.mediaState('none','',''); return; }
      var playing = (!m.paused && !m.ended);
      var title = document.title || '';
      SearchApp.mediaState(playing ? 'playing' : 'paused', title, location.host);
    } catch(e){}
  }

  // One set of listeners on the document, in the capture phase, rather than
  // one per player plus a MutationObserver over the whole page to find new
  // players. Media events do not bubble, but they are captured on the way
  // down, so every <video> and <audio> - however late it was added - is heard
  // here. The observer this replaces ran on every change to the page's DOM,
  // which on a heavy single-page site is continuously.
  ['play','pause','ended','emptied'].forEach(function(ev){
    document.addEventListener(ev, function(e){
      var t = e.target;
      if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) report();
    }, true);
  });

  window.__searchMediaControl = function(action){
    var m = pickMedia();
    if (!m) return;
    if (action === 'pause') m.pause();
    else if (action === 'play') m.play();
  };

  report();
})();
""".trim()
}
