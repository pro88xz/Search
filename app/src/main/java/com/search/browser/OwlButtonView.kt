package com.search.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sky maths for the owl button, kept free of Android types so it can be
 * tested on the JVM. Mirrors owl-sky.js, the design prototype.
 */
object OwlSky {
    const val SYNODIC = 29.530588853                  // days, new moon to new moon
    private const val NEW_MOON_REF = 947182440000L    // 2000-01-06 18:14 UTC, a known new moon

    // Local minutes since midnight. Dawn 05:45-06:45, dusk 18:15-19:15.
    private const val DAWN0 = 345f
    private const val DAWN1 = 405f
    private const val DUSK0 = 1095f
    private const val DUSK1 = 1155f

    /** Moon age in days for a wall-clock time: 0 new, ~14.77 full. */
    fun moonAge(epochMs: Long): Double {
        val days = (epochMs - NEW_MOON_REF) / 86_400_000.0
        return ((days % SYNODIC) + SYNODIC) % SYNODIC
    }

    /** Fraction of the moon's disc that is lit, 0..1. */
    fun moonLit(age: Double): Double = (1 - cos(2 * PI * age / SYNODIC)) / 2

    /**
     * How much it is day (0 night .. 1 day) and how strong the twilight glow is
     * (0 .. 1, peaking half-way through dawn and dusk) at [minutes] past midnight.
     */
    fun skyState(minutes: Float): FloatArray {
        fun ss(x: Float) = x * x * (3 - 2 * x)
        return when {
            minutes < DAWN0 || minutes >= DUSK1 -> floatArrayOf(0f, 0f)
            minutes < DAWN1 -> {
                val p = (minutes - DAWN0) / (DAWN1 - DAWN0)
                floatArrayOf(ss(p), sin(PI * p).toFloat())
            }
            minutes < DUSK0 -> floatArrayOf(1f, 0f)
            else -> {
                val p = (minutes - DUSK0) / (DUSK1 - DUSK0)
                floatArrayOf(1 - ss(p), sin(PI * p).toFloat())
            }
        }
    }

    /** Eyes open 0..1 for the animation clock in seconds: a 160ms blink every 4.6s. */
    fun eyeOpen(t: Float): Float {
        val p = (t % 4.6f) / 4.6f
        if (p < 0.9f) return 1f
        val q = (p - 0.9f) / 0.07f
        return if (q >= 1f) 1f else abs(1 - 2 * q) * 0.92f + 0.08f
    }
}

/**
 * The round button in the middle of the bottom bar: the Search owl peeking up
 * from the bottom of a window onto a sky that follows the phone's clock - a
 * blue sky with the sun and drifting clouds by day, an orange horizon with the
 * sun sinking behind the owl at dusk, and stars and the moon in tonight's real
 * phase at night. The owl blinks and looks about; [pop] bounces it for a tap.
 *
 * Everything is drawn in a 120x120 box scaled to the view. It animates at about
 * 30fps only while it is on screen, and holds a still frame when the phone's
 * animations are off. Night Owl tabs ([privateMode]) always show the night.
 */
class OwlButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Night Owl: always the night sky. */
    var privateMode = false
        set(value) {
            if (field == value) return
            field = value
            targetAt = 0L
            invalidate()
        }

    private var shownDay = -1f       // eased toward the clock's value, so a jump plays as a short transition
    private var shownTwilight = 0f
    private var pop = 0f
    private var popV = 0f
    private var lastFrame = 0L
    private val clock0 = SystemClock.uptimeMillis()
    private var running = false
    private var moonAge = OwlSky.moonAge(System.currentTimeMillis())
    private var moonAgeAt = 0L
    private var target = floatArrayOf(1f, 0f)
    private var targetAt = 0L
    private var frameMs = FRAME_MS
    private var powerAt = 0L
    /** Until when the owl moves at its full rate; after that it settles into a calmer one. */
    private var livelyUntil = 0L
    private var powerSave = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val oval = RectF()
    private val circleClip = Path()

    // Shaders that never change, made once.
    private val owlBody = LinearGradient(30f, 40f, 92f, 120f, intArrayOf(OWL_A, OWL_B, OWL_C), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val owlSheen = RadialGradient(44f, 62f, 50f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val scleraL = sclera(45f, 86f, 12.5f)
    private val scleraR = sclera(75f, 86f, 12.5f)
    private val iris = RadialGradient(0f, -12.5f * 0.66f * 0.25f, 12.5f * 0.66f, intArrayOf(IRIS0, IRIS1, IRIS2), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    private val beak = LinearGradient(0f, 98f, 0f, 108f, BEAK0, BEAK1, Shader.TileMode.CLAMP)
    private val moonGlow = RadialGradient(0f, 0f, MOON_R * 2.4f, intArrayOf(0x57D6DEFF, 0x00D6DEFF), floatArrayOf(0.25f, 1f), Shader.TileMode.CLAMP)
    private val moonLitShader = RadialGradient(-3f, -3f, MOON_R + 3f, MOON_LIT, MOON_LIT_EDGE, Shader.TileMode.CLAMP)
    private val raysPath = Path().apply {
        for (i in 0 until 12) {
            val a = i * PI / 6
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            // a thin triangle from 0.9r to 2r, half-width 1.1 at its base
            moveTo(SUN_R * 0.9f * c + 1.1f * s, SUN_R * 0.9f * s - 1.1f * c)
            lineTo(SUN_R * 2.0f * c, SUN_R * 2.0f * s)
            lineTo(SUN_R * 0.9f * c - 1.1f * s, SUN_R * 0.9f * s + 1.1f * c)
            close()
        }
    }
    private val tuftL = Path().apply { moveTo(29f, 84f); quadTo(23.5f, 64f, 23f, 47f); quadTo(36f, 54.5f, 45f, 69f); close() }
    private val tuftR = Path().apply { moveTo(91f, 84f); quadTo(96.5f, 64f, 97f, 47f); quadTo(84f, 54.5f, 75f, 69f); close() }
    private val beakPath = Path().apply {
        moveTo(55f, 98f); quadTo(60f, 96f, 65f, 98f); lineTo(61.1f, 106.6f)
        quadTo(60f, 108.6f, 58.9f, 106.6f); close()
    }

    // Shaders that depend on the time of day, remade only when their colours change.
    private var skyShader: Shader? = null
    private var skyKey = 0L
    private var sunShader: Shader? = null
    private var sunGlowShader: Shader? = null
    private var sunKey = -1

    init {
        // The circle is clipped by the outline, which is anti-aliased on the
        // GPU where clipPath is not.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
        // The button around it (navNewTab) takes the taps, the focus and the
        // ripple; this view only draws.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Bounce the owl, as for a tap. */
    fun pop() {
        if (!animationsOn()) return
        popV += 9f
        livelyUntil = SystemClock.uptimeMillis() + LIVELY_MS
        kick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kick()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) kick() else stop()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            if (animationsOn()) postOnAnimationDelayed(this, frameMs)
            else postDelayed(this, 60_000L)    // still frame: only follow the clock
        }
    }

    private fun kick() {
        if (running || !isAttachedToWindow || windowVisibility != VISIBLE) return
        livelyUntil = SystemClock.uptimeMillis() + LIVELY_MS
        running = true
        lastFrame = 0L
        postOnAnimation(tick)
    }

    private fun stop() {
        running = false
        removeCallbacks(tick)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) kick() else stop()
    }

    private fun animationsOn(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.areAnimatorsEnabled()
        else try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Exception) {
            true
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        val moving = animationsOn()
        val t = if (moving) (now - clock0) / 1000f else 3.3f

        if (now - moonAgeAt > 600_000L) {          // the moon's age, every ten minutes
            moonAge = OwlSky.moonAge(System.currentTimeMillis())
            moonAgeAt = now
        }
        if (now - targetAt > 1000L || targetAt == 0L) {  // the clock, once a second
            target = if (privateMode) floatArrayOf(0f, 0f) else {
                val c = Calendar.getInstance()
                OwlSky.skyState(c.get(Calendar.HOUR_OF_DAY) * 60f + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f)
            }
            targetAt = now
        }
        // Battery: full 30fps for half a minute after the owl appears or is
        // tapped, then a calm 15fps for as long as it simply sits there, and
        // 8fps in Battery Saver - the sky moves slowly, so it still looks
        // alive, at a fraction of the drawing.
        if (now - powerAt > 5000L || powerAt == 0L) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerSave = pm?.isPowerSaveMode == true
            powerAt = now
        }
        frameMs = when {
            powerSave -> 125L
            now < livelyUntil || pop > 0.01f || popV > 0.01f -> FRAME_MS
            else -> CALM_FRAME_MS
        }
        if (shownDay < 0f || !moving) {
            shownDay = target[0]; shownTwilight = target[1]
        } else {
            val k = min(1f, dt * 3f)
            shownDay += (target[0] - shownDay) * k
            shownTwilight += (target[1] - shownTwilight) * k
        }
        if (moving) {                                  // the tap's spring
            popV += (-pop * 180f - popV * 14f) * dt
            pop = (pop + popV * dt).coerceIn(-0.2f, 1.2f)
        } else {
            pop = 0f; popV = 0f
        }

        val k = min(width, height) / 120f
        canvas.save()
        canvas.translate((width - 120f * k) / 2f, (height - 120f * k) / 2f)
        canvas.scale(k, k)
        if (!canvas.isHardwareAccelerated) {
            circleClip.reset(); circleClip.addCircle(60f, 60f, 60f, Path.Direction.CW)
            canvas.clipPath(circleClip)
        }
        drawSky(canvas, shownDay, shownTwilight, t)
        drawOwl(canvas, shownDay, t, if (moving) OwlSky.eyeOpen(t) else 1f, max(0f, pop))
        canvas.restore()
    }

    private fun drawSky(c: Canvas, d: Float, w: Float, t: Float) {
        val n = 1 - d
        var top = mixColor(NIGHT_TOP, DAY_TOP, d)
        var bot = mixColor(NIGHT_BOT, DAY_BOT, d)
        top = mixColor(top, DUSK_TOP, w * 0.55f)
        bot = mixColor(bot, DUSK_BOT, w * 0.85f)
        val key = (top.toLong() shl 32) or (bot.toLong() and 0xFFFFFFFFL)
        if (skyShader == null || key != skyKey) {
            skyShader = LinearGradient(0f, 0f, 0f, 120f, top, bot, Shader.TileMode.CLAMP)
            skyKey = key
        }
        shaded.shader = skyShader; shaded.alpha = 255
        c.drawRect(0f, 0f, 120f, 120f, shaded)

        // stars, and a shooting star every 17 seconds
        val starA = (n * n * 1.1f - w * 0.3f).coerceIn(0f, 1f)
        if (starA > 0.01f) {
            fill.shader = null
            for (s in STARS) {
                val tw = 0.55f + 0.45f * sin(t * 2.1f + s[3] * 2.3f)
                fill.color = Color.WHITE
                fill.alpha = (255 * starA * tw).toInt()
                c.drawCircle(s[0], s[1], s[2] * (0.7f + 0.3f * tw), fill)
            }
            val sp = (t % 17f) / 0.9f
            if (sp < 1f && starA > 0.5f) {
                val x0 = 18 + sp * 46; val y0 = 12 + sp * 16
                stroke.shader = LinearGradient(x0, y0, x0 - 16, y0 - 5.5f,
                    Color.argb((230 * (1 - sp) * starA).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                stroke.strokeWidth = 1.2f; stroke.alpha = 255
                c.drawLine(x0, y0, x0 - 16, y0 - 5.5f, stroke)
                stroke.shader = null
            }
        }

        // the moon rises from behind the owl as night falls
        val moonA = (n * 1.4f - 0.2f).coerceIn(0f, 1f)
        if (moonA > 0.01f) {
            val mx = 82f; val my = 30f + d * 52f
            c.save(); c.translate(mx, my)
            shaded.shader = moonGlow; shaded.alpha = (255 * moonA).toInt()
            c.drawCircle(0f, 0f, MOON_R * 2.4f, shaded)
            fill.shader = null; fill.color = MOON_DARK; fill.alpha = (140 * moonA).toInt()   // earthshine
            c.drawCircle(0f, 0f, MOON_R, fill)
            moonLitPath(path, 0f, 0f, MOON_R, moonAge)
            c.save(); c.clipPath(path)
            shaded.shader = moonLitShader; shaded.alpha = (255 * moonA).toInt()
            c.drawRect(-MOON_R, -MOON_R, MOON_R, MOON_R, shaded)
            fill.color = MOON_MARE; fill.alpha = (128 * moonA).toInt()                      // the darker "seas"
            for (m in MARIA) c.drawCircle(m[0], m[1], m[2], fill)
            c.restore()
            c.restore()
        }

        // the sun: high by day, sinks behind the owl and reddens at dusk
        val sunA = (d * 1.3f).coerceIn(0f, 1f)
        if (sunA > 0.01f) {
            val sx = 80f; val sy = 28f + (1 - d) * 56f
            val red = (w * 1.2f + (1 - d) * 0.6f).coerceIn(0f, 1f)
            val rk = (red * 48).toInt()
            if (rk != sunKey) {
                val r = rk / 48f
                sunGlowShader = RadialGradient(0f, 0f, SUN_R * 2.7f,
                    intArrayOf(Color.argb(199, 255, (244 - r * 70).toInt(), (196 - r * 120).toInt()),
                        Color.argb(0, 255, (226 - r * 60).toInt(), (130 - r * 90).toInt())),
                    floatArrayOf(0.185f, 1f), Shader.TileMode.CLAMP)
                sunShader = RadialGradient(-2f, -2f, SUN_R + 2f,
                    intArrayOf(SUN_CORE, mixColor(SUN_MID, SUNSET_MID, r), mixColor(SUN_EDGE, SUNSET_EDGE, r)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                sunKey = rk
            }
            c.save(); c.translate(sx, sy)
            val pulse = 1 + 0.05f * sin(t * 1.3f)
            c.save(); c.scale(pulse, pulse)
            shaded.shader = sunGlowShader; shaded.alpha = (255 * sunA).toInt()
            c.drawCircle(0f, 0f, SUN_R * 2.7f, shaded)
            c.restore()
            c.save(); c.rotate((t * 0.12f * 180f / PI.toFloat()) % 360f)
            fill.shader = null; fill.color = RAYS; fill.alpha = (23 * (1 - red) * sunA).toInt()
            c.drawPath(raysPath, fill)
            c.restore()
            shaded.shader = sunShader; shaded.alpha = (255 * sunA).toInt()
            c.drawCircle(0f, 0f, SUN_R, shaded)
            c.restore()
        }

        // clouds drift by day and blush at twilight
        val cloudA = (d * 1.1f + w * 0.4f).coerceIn(0f, 1f)
        if (cloudA > 0.01f) {
            fill.shader = null
            val body = mixColor(CLOUD, CLOUD_DUSK, w * 0.8f)
            val shade = mixColor(CLOUD_SHADE, CLOUD_DUSK, w * 0.6f)
            for (cl in CLOUDS) {
                val x = ((cl[3] + t * cl[2]) % 170f) - 25f
                fill.color = shade; fill.alpha = (242 * cloudA).toInt()
                cloudPath(path, x, cl[0] + 1.2f * cl[1], cl[1]); c.drawPath(path, fill)
                fill.color = body; fill.alpha = (247 * cloudA).toInt()
                cloudPath(path, x, cl[0], cl[1]); c.drawPath(path, fill)
            }
        }
    }

    private fun drawOwl(c: Canvas, d: Float, t: Float, open: Float, pop: Float) {
        val bob = -1.2f * (0.5f + 0.5f * sin(t * PI.toFloat() / 2f)) - pop * 6f
        c.save(); c.translate(0f, bob)
        shaded.shader = owlBody; shaded.alpha = 255
        c.drawPath(tuftL, shaded); c.drawPath(tuftR, shaded)
        c.drawCircle(60f, 112f, 50f, shaded)
        shaded.shader = owlSheen; shaded.alpha = (255 * (0.2f + 0.1f * d)).toInt()
        c.drawCircle(60f, 112f, 50f, shaded)
        fill.shader = null; fill.color = Color.WHITE; fill.alpha = 31
        path.reset()
        path.addCircle(45f, 86f, 17f, Path.Direction.CW); path.addCircle(75f, 86f, 17f, Path.Direction.CW)
        c.drawPath(path, fill)                        // the facial discs, one shape so the overlap is not doubled
        // eyes wander, glancing up now and then
        val sx = sin(t * 0.55f)
        val lx = 2.2f * sx * min(1f, abs(sx) * 1.6f)
        val ly = -1.2f + 0.9f * sin(t * 0.31f)
        val wide = 1 + 0.15f * pop
        drawEye(c, 45f, 86f, 12.5f, lx, ly, open, wide, scleraL)
        drawEye(c, 75f, 86f, 12.5f, lx, ly, open, wide, scleraR)
        shaded.shader = beak; shaded.alpha = 255
        c.drawPath(beakPath, shaded)
        c.restore()
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, r: Float, lx: Float, ly: Float, open: Float, wide: Float, sclera: Shader) {
        c.save()
        c.scale(wide, wide * max(0.08f, open), cx, cy)
        fill.shader = null; fill.color = EYE_SHADOW; fill.alpha = 71
        c.drawCircle(cx, cy + r * 0.08f, r + 1f, fill)
        shaded.shader = sclera; shaded.alpha = 255
        c.drawCircle(cx, cy, r, shaded)
        val ix = cx + lx; val iy = cy + ly
        c.save(); c.translate(ix, iy)
        shaded.shader = iris
        c.drawCircle(0f, 0f, r * 0.66f, shaded)
        c.restore()
        fill.color = PUPIL; fill.alpha = 255
        c.drawCircle(cx + lx * 1.15f, cy + ly * 1.15f, r * 0.38f, fill)
        fill.color = Color.WHITE
        c.drawCircle(ix + r * 0.2f, iy - r * 0.22f, r * 0.17f, fill)
        fill.alpha = 217
        c.drawCircle(ix - r * 0.2f, iy + r * 0.2f, r * 0.075f, fill)
        c.restore()
    }

    private fun sclera(cx: Float, cy: Float, r: Float) =
        RadialGradient(cx - r * 0.1f, cy - r * 0.3f, r * 1.1f, intArrayOf(Color.WHITE, Color.WHITE, 0xFFE6DCFF.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)

    /** A fluffy cloud: one ellipse and three puffs, filled as one shape. */
    private fun cloudPath(p: Path, x: Float, y: Float, s: Float) {
        p.reset()
        oval.set(x - 15 * s, y + 3 * s - 6 * s, x + 15 * s, y + 3 * s + 6 * s)
        p.addOval(oval, Path.Direction.CW)
        p.addCircle(x - 4 * s, y - 1 * s, 8 * s, Path.Direction.CW)
        p.addCircle(x + 6 * s, y, 7 * s, Path.Direction.CW)
        p.addCircle(x - 11 * s, y + 3 * s, 5 * s, Path.Direction.CW)
    }

    /**
     * The lit part of the moon at [age] days, as seen from the northern
     * hemisphere (waxing lit on the right). Never thinner than a ~6% crescent,
     * so the moon does not vanish around new moon.
     */
    private fun moonLitPath(p: Path, cx: Float, cy: Float, r: Float, age: Double) {
        val minAge = 2.6
        val a = age.coerceIn(minAge, OwlSky.SYNODIC - minAge)
        val ang = 2 * PI * a / OwlSky.SYNODIC
        val e = (r * cos(ang)).toFloat()               // the terminator's half-width, signed
        val ex = max(0.01f, abs(e))
        val waxing = a < OwlSky.SYNODIC / 2
        p.reset()
        oval.set(cx - r, cy - r, cx + r, cy + r)
        p.arcTo(oval, -90f, if (waxing) 180f else -180f, true)   // the lit limb, top to bottom
        oval.set(cx - ex, cy - r, cx + ex, cy + r)
        val bulgesToLimb = e > 0                       // a crescent's terminator bows toward the lit limb
        val sweep = if (waxing) (if (bulgesToLimb) -180f else 180f) else (if (bulgesToLimb) 180f else -180f)
        p.arcTo(oval, 90f, sweep, false)              // the terminator, bottom back to top
        p.close()
    }

    private companion object {
        const val FRAME_MS = 33L
        const val CALM_FRAME_MS = 66L
        const val LIVELY_MS = 30_000L
        const val SUN_R = 10.5f
        const val MOON_R = 10f

        val DAY_TOP = 0xFF3B8EDF.toInt(); val DAY_BOT = 0xFFA8DAF7.toInt()
        val NIGHT_TOP = 0xFF0A0B2A.toInt(); val NIGHT_BOT = 0xFF2A1F68.toInt()
        val DUSK_TOP = 0xFF3D3A8E.toInt(); val DUSK_BOT = 0xFFFF9B66.toInt()
        val OWL_A = 0xFFA066FF.toInt(); val OWL_B = 0xFF7436F2.toInt(); val OWL_C = 0xFF4A1AB8.toInt()
        val SUN_CORE = 0xFFFFFFF6.toInt(); val SUN_MID = 0xFFFFF0A8.toInt(); val SUN_EDGE = 0xFFFFD45C.toInt()
        val SUNSET_MID = 0xFFFFB65A.toInt(); val SUNSET_EDGE = 0xFFFF7E3B.toInt(); val RAYS = 0xFFFFF8D6.toInt()
        val MOON_LIT = 0xFFF4F2EA.toInt(); val MOON_LIT_EDGE = 0xFFD9D5C8.toInt()
        val MOON_MARE = 0xFFBDB8AA.toInt(); val MOON_DARK = 0xFF2E3050.toInt()
        val CLOUD = 0xFFFFFFFF.toInt(); val CLOUD_SHADE = 0xFFD6E7F5.toInt(); val CLOUD_DUSK = 0xFFFFD3C4.toInt()
        val IRIS0 = 0xFFFFD15C.toInt(); val IRIS1 = 0xFFFFA51F.toInt(); val IRIS2 = 0xFFE97C00.toInt()
        val PUPIL = 0xFF1D0B3B.toInt(); val EYE_SHADOW = 0xFF2A0A6B.toInt()
        val BEAK0 = 0xFFFFB32E.toInt(); val BEAK1 = 0xFFEE7400.toInt()

        // x, y, radius, twinkle phase
        val STARS = arrayOf(
            floatArrayOf(30f, 33f, 1.9f, 0f), floatArrayOf(48f, 15f, 1.4f, 1.3f), floatArrayOf(22f, 58f, 1.1f, 2.1f),
            floatArrayOf(99f, 58f, 1.2f, 0.7f), floatArrayOf(64f, 26f, 1.0f, 2.7f), floatArrayOf(14f, 42f, 0.9f, 1.8f),
            floatArrayOf(40f, 44f, 0.8f, 0.4f), floatArrayOf(104f, 40f, 0.9f, 3.1f),
        )
        // y, scale, speed (units/s), starting x
        val CLOUDS = arrayOf(floatArrayOf(40f, 1.0f, 2.1f, 10f), floatArrayOf(22f, 0.72f, 1.3f, 80f))
        // offsets from the moon's centre and radius
        val MARIA = arrayOf(
            floatArrayOf(-3f, -3f, 3.2f), floatArrayOf(2.5f, -4.5f, 2.1f), floatArrayOf(3.5f, 1.5f, 2.6f),
            floatArrayOf(-1.5f, 4f, 1.8f), floatArrayOf(-5.5f, 1.5f, 1.4f),
        )

        fun mixColor(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * u).toInt()
            return Color.argb(ch(24), ch(16), ch(8), ch(0))
        }
    }
}
