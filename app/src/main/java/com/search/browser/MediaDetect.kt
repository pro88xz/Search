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

  function hook(m){
    if (m.__searchHooked) return;
    m.__searchHooked = true;
    ['play','pause','ended','emptied'].forEach(function(ev){
      m.addEventListener(ev, report);
    });
  }

  function scan(){
    document.querySelectorAll('video,audio').forEach(hook);
  }

  window.__searchMediaControl = function(action){
    var m = pickMedia();
    if (!m) return;
    if (action === 'pause') m.pause();
    else if (action === 'play') m.play();
  };

  scan();
  report();
  // Catch dynamically added players.
  //
  // Throttled, because this observes childList over the whole subtree: on a
  // feed or any React-shaped page it fires continuously, and it used to run a
  // querySelectorAll across the entire document every single time. That is a
  // page-wide scan several times a second, on every page the browser has open,
  // for the sake of noticing a <video> that might appear - paid for in battery
  // and in scroll smoothness.
  var scanQueued = false;
  var mo = new MutationObserver(function(){
    if (scanQueued) return;
    scanQueued = true;
    setTimeout(function(){ scanQueued = false; scan(); }, 500);
  });
  try { mo.observe(document.documentElement, {childList:true, subtree:true}); } catch(e){}
})();
""".trim()
}
