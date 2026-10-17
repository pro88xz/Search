package com.search.browser

import android.content.Context
import android.content.Intent

/**
 * Whether Search opens the links tapped in other apps, and the way to make it
 * so: Android's own "default browser app" request on Android 10 and up, its
 * Default apps screen before that.
 */
object DefaultBrowser {

    private const val KEY_ASKED = "default_browser_asked_at"
    private const val KEY_LAUNCHES = "review_launches"

    fun isDefault(c: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val rm = c.getSystemService(android.app.role.RoleManager::class.java)
            rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER) &&
                rm.isRoleHeld(android.app.role.RoleManager.ROLE_BROWSER)
        } else {
            val probe = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            val res = c.packageManager.resolveActivity(probe,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            res?.activityInfo?.packageName == c.packageName
        }
    } catch (e: Exception) { false }

    /**
     * The system's own request for the role (Android 10+), or null when only
     * the Default apps screen can do it.
     */
    fun requestIntent(c: Context): Intent? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        return try {
            val rm = c.getSystemService(android.app.role.RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(android.app.role.RoleManager.ROLE_BROWSER)) null
            else rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER)
        } catch (e: Exception) { null }
    }

    /** Android's Default apps screen, where the browser is picked by hand. */
    fun settingsIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

    /**
     * Once, and only for someone who has come back to Search a few times on
     * their own: the fourth launch or later, never on a first run, never twice.
     */
    fun shouldOffer(c: Context): Boolean =
        Settings.getLong(c, KEY_ASKED, 0L) == 0L &&
            Settings.getLong(c, KEY_LAUNCHES, 0L) >= 4L && !isDefault(c)

    fun noteOffered(c: Context) = Settings.setLong(c, KEY_ASKED, System.currentTimeMillis())
}

/**
 * Translating a page through Google Translate's page proxy, which serves the
 * site itself, translated, as a working page (links, forms and all) under
 * <host>.translate.goog - and finding the way back to the original.
 */
object PageTranslate {

    fun isTranslated(url: String?): Boolean = hostOf(url)?.endsWith(".translate.goog") == true

    /** [url] translated into [lang] (the phone's language). */
    fun translatedUrl(url: String, lang: String): String =
        "https://translate.google.com/translate?sl=auto&tl=" + android.net.Uri.encode(lang) +
            "&hl=" + android.net.Uri.encode(lang) + "&u=" + android.net.Uri.encode(url)

    /**
     * The page a translate.goog address stands for. The proxy writes the
     * site's host with its dots as dashes and its dashes doubled
     * (www-example--site-com for www.example-site.com) and adds _x_tr_
     * parameters, which are dropped.
     */
    fun originalUrl(url: String): String? = try {
        val u = android.net.Uri.parse(url)
        val host = u.host ?: return null
        val coded = host.removeSuffix(".translate.goog")
        val real = coded.replace("--", "\u0000").replace('-', '.').replace('\u0000', '-')
        val b = u.buildUpon().authority(real).clearQuery()
        u.queryParameterNames.filterNot { it.startsWith("_x_tr_") }.forEach { k ->
            u.getQueryParameters(k).forEach { v -> b.appendQueryParameter(k, v) }
        }
        b.build().toString()
    } catch (e: Exception) { null }

    private fun hostOf(url: String?): String? = try {
        android.net.Uri.parse(url ?: return null).host?.lowercase()
    } catch (e: Exception) { null }
}

/**
 * Reader view: the article on the page on its own, in clean type, with the
 * menus, ads, share bars and comments left behind. Done in the page itself -
 * the largest block of real paragraphs is kept, everything around it goes -
 * so it works offline, on any site, and asks nobody else for the page. Run
 * again (or reloading) brings the site back.
 *
 * The script answers "on", "off" or "none" (no article found).
 */
object ReaderView {

    val JS: String = """
(function(){
var R='data-search-reader';
if(document.documentElement.getAttribute(R)){location.reload();return 'off';}
var best=null,score=0,cs=document.querySelectorAll('article,main,[role=main],[itemprop=articleBody],section,div');
for(var i=0;i<cs.length&&i<4000;i++){var e=cs[i],ps=e.querySelectorAll('p');if(ps.length<3)continue;
var len=0;for(var j=0;j<ps.length;j++){var t=ps[j].textContent.length;if(t>40)len+=t;}
var ln=0,as=e.querySelectorAll('a');for(var k=0;k<as.length;k++)ln+=as[k].textContent.length;
var s=len-ln*1.5;if(e.tagName==='ARTICLE'||e.getAttribute('itemprop')==='articleBody')s*=1.25;
if(s>score*1.1||(s>score&&best&&best.contains(e))){score=s;best=e;}}
if(!best||score<600)return 'none';
var h=document.querySelector('h1');var title=(h&&h.textContent.trim())||document.title||'';
var site=location.hostname.replace(/^www\./,'');
var c=best.cloneNode(true);
var junk='script,style,noscript,iframe,object,embed,form,button,input,select,textarea,nav,aside,footer,header,svg,canvas,dialog,[hidden],[aria-hidden=true],[role=navigation],[role=complementary],[class*=share],[class*=social],[class*=related],[class*=newsletter],[class*=comment],[class*=promo],[class*=advert],[id*=comment],[id*=related]';
c.querySelectorAll(junk).forEach(function(x){x.remove();});
c.querySelectorAll('*').forEach(function(x){
for(var a=x.attributes.length-1;a>=0;a--){var n=x.attributes[a].name;
if(n!=='src'&&n!=='href'&&n!=='alt'&&n!=='srcset'&&n!=='data-src'&&n!=='colspan'&&n!=='rowspan')x.removeAttribute(n);}});
c.querySelectorAll('img').forEach(function(im){var d=im.getAttribute('data-src');if(d&&!im.getAttribute('src'))im.setAttribute('src',d);im.setAttribute('loading','lazy');});
c.querySelectorAll('h1').forEach(function(x){if(x.textContent.trim()===title)x.remove();});
try{window.stop();}catch(e){}
var dark=matchMedia('(prefers-color-scheme: dark)').matches;
var bg=dark?'#121214':'#FAFAF7',fg=dark?'#E6E6E8':'#1C1C1F';
var css='html,body{margin:0;background:'+bg+';color:'+fg+'}'+
'body{font:19px/1.7 Georgia,"Noto Serif",serif;padding:28px 22px 80px;max-width:680px;margin:0 auto;-webkit-text-size-adjust:100%;overflow-wrap:break-word}'+
'h1.t{font:700 28px/1.25 Roboto,"Google Sans",sans-serif;margin:0 0 8px}.s{font:14px Roboto,sans-serif;opacity:.6;margin:0 0 28px}'+
'h2,h3,h4{font-family:Roboto,sans-serif;line-height:1.3;margin:28px 0 8px}p{margin:0 0 18px}'+
'img,video,figure,picture{max-width:100%;height:auto;border-radius:12px}figure{margin:24px 0}figcaption{font:14px/1.5 Roboto,sans-serif;opacity:.7;margin-top:6px}'+
'a{color:inherit;text-decoration-color:rgba(127,127,127,.6)}blockquote{margin:20px 0;padding-left:16px;border-left:3px solid rgba(127,127,127,.45)}'+
'pre,code{white-space:pre-wrap;font-size:15px}table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto}td,th{border:1px solid rgba(127,127,127,.3);padding:6px}';
document.head.innerHTML='';
var m=document.createElement('meta');m.name='viewport';m.content='width=device-width,initial-scale=1';document.head.appendChild(m);
var st=document.createElement('style');st.textContent=css;document.head.appendChild(st);
document.title=title;
document.body.innerHTML='';document.body.removeAttribute('class');document.body.removeAttribute('style');
document.documentElement.removeAttribute('class');document.documentElement.removeAttribute('style');
var t=document.createElement('h1');t.className='t';t.textContent=title;
var s=document.createElement('div');s.className='s';s.textContent=site;
document.body.appendChild(t);document.body.appendChild(s);document.body.appendChild(c);
document.documentElement.setAttribute(R,'1');scrollTo(0,0);return 'on';
})()
""".trimIndent()
}

/**
 * A page pinned to the home screen as its own icon, as Chrome's "Add to Home
 * screen" does: the site's icon on a white tile, opening the page in Search.
 */
object HomeShortcut {

    fun pin(c: Context, url: String, title: String, icon: android.graphics.Bitmap?): Boolean {
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(c)) return false
        val label = title.trim().ifBlank { url }
        val open = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url), c, MainActivity::class.java)
        val art = icon?.let { tile(it) }
        val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(c, "page-" + url.hashCode())
            .setShortLabel(label.take(24))
            .setLongLabel(label.take(60))
            .setIcon(if (art != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap(art)
                else androidx.core.graphics.drawable.IconCompat.createWithBitmap(art)
            } else androidx.core.graphics.drawable.IconCompat.createWithResource(c, R.mipmap.ic_launcher))
            .setIntent(open)
            .build()
        return try {
            androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(c, info, null)
        } catch (e: Exception) { false }
    }

    /**
     * The site's icon centred on a white square, inside the middle two
     * thirds an adaptive icon keeps whatever shape the launcher cuts it to.
     */
    private fun tile(icon: android.graphics.Bitmap): android.graphics.Bitmap? = try {
        val size = 216
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val inner = (size * 0.42f).toInt()
        val left = (size - inner) / 2
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(icon, null, android.graphics.Rect(left, left, left + inner, left + inner), paint)
        out
    } catch (e: Throwable) { null }
}
