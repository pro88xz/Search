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
    /** Until when every display frame is drawn: a greeting, a tap, a blink or a glance in progress. */
    private var smoothUntil = 0L
    private var powerSave = false

    // ---- A living owl: blinks, glances, the greeting ----
    //
    // Real eyes do not drift on a sine wave: they hold still on something,
    // then jump to the next thing in a quick saccade, and blinks come at
    // uneven intervals, now and then two at once. All of that is scheduled
    // here from a seeded generator, so it never falls into a rhythm.
    private val rnd = java.util.Random(SystemClock.uptimeMillis())
    private var nextBlinkAt = 0L
    private var blinkAt = -1L
    private var blinkTwice = false
    private var nextGazeAt = 0L
    private var gazeAt = -1L
    private var gazeFromX = 0f
    private var gazeFromY = -1.2f
    private var gazeToX = 0f
    private var gazeToY = -1.2f
    private var greetAt = -1L
    private var squintAt = -1L
    private var busy = false

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

    /** Bounce the owl, as for a tap: a springy hop, a squash on landing, and a happy squint. */
    fun pop() {
        if (!animationsOn()) return
        val now = SystemClock.uptimeMillis()
        popV += 9f
        squintAt = now
        livelyUntil = now + LIVELY_MS
        smoothUntil = now + 900L
        kick()
    }

    /**
     * The owl says hello: it rises into its window with its eyes shut, wakes
     * with them wide, looks straight at you, tilts its head and winks. On a
     * launch and when the browser is come back to after a while
     * (MainActivity), never on every tab switch. Nothing when the phone's
     * animations are off.
     */
    fun greet() {
        if (!animationsOn()) return
        val now = SystemClock.uptimeMillis()
        greetAt = now
        // No blink or glance of its own in the middle of the greeting.
        nextBlinkAt = now + GREET_MS + 600L
        nextGazeAt = now + GREET_MS + 300L
        livelyUntil = now + LIVELY_MS
        smoothUntil = now + GREET_MS + 100L
        kick()
    }

    /**
     * A page is loading in the tab on screen: the owl watches the ring
     * filling round it (eyes up and to the side), and blinks less. Done, it
     * gives a contented blink.
     */
    fun setBusy(loading: Boolean) {
        if (busy == loading) return
        busy = loading
        val now = SystemClock.uptimeMillis()
        if (!loading && animationsOn() && greetAt < 0L) {
            nextBlinkAt = now + 140L
            nextGazeAt = now + 260L
        } else nextGazeAt = now + 60L
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
        val eventful = now < smoothUntil || greetAt >= 0L ||
            (blinkAt >= 0L && now - blinkAt < BLINK_SPAN_MS) || (gazeAt >= 0L && now - gazeAt < SACCADE_MS)
        frameMs = when {
            powerSave -> if (eventful) FRAME_MS else 125L
            // Every display frame while it matters: a blink of 160ms drawn at
            // 15fps is two frames and reads as a flicker, not a blink.
            eventful -> 0L
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
        if (moving) {
            val pose = pose(now)
            drawOwl(canvas, shownDay, t, pose, pop)
        } else {
            still.reset()
            drawOwl(canvas, shownDay, t, still, 0f)
        }
        canvas.restore()
    }

    /** Where everything about the owl is, for one frame. */
    private class Pose {
        var rise = 0f          // px down from its rest, for the greeting's climb
        var tilt = 0f          // degrees, about the bottom of the body
        var openL = 1f
        var openR = 1f
        var wide = 1f
        var lookX = 0f
        var lookY = -1.2f
        fun reset() { rise = 0f; tilt = 0f; openL = 1f; openR = 1f; wide = 1f; lookX = 0f; lookY = -1.2f }
    }
    private val livePose = Pose()
    private val still = Pose()

    private fun pose(now: Long): Pose {
        val p = livePose
        p.reset()
        // ---- gaze: hold, then a quick saccade to somewhere new ----
        if (nextGazeAt == 0L) nextGazeAt = now + 900L
        if (now >= nextGazeAt) {
            gazeFromX = currentGazeX(now); gazeFromY = currentGazeY(now)
            if (busy) {
                // Up at the loading ring, to one side or the other.
                gazeToX = if (rnd.nextBoolean()) 2.0f else -2.0f
                gazeToY = -2.4f
            } else if (rnd.nextFloat() < 0.25f) {
                gazeToX = 0f; gazeToY = -0.6f      // back to you
            } else {
                gazeToX = (rnd.nextFloat() * 2f - 1f) * 2.4f
                gazeToY = -2.2f + rnd.nextFloat() * 2.6f
            }
            gazeAt = now
            nextGazeAt = now + (if (busy) 900L else 1300L) + rnd.nextInt(if (busy) 900 else 2400)
        }
        p.lookX = currentGazeX(now)
        p.lookY = currentGazeY(now)
        // ---- blinks: uneven, sometimes two at once; fewer while watching a load ----
        if (nextBlinkAt == 0L) nextBlinkAt = now + 1800L + rnd.nextInt(2000)
        if (blinkAt < 0L && now >= nextBlinkAt) {
            blinkAt = now
            blinkTwice = rnd.nextFloat() < 0.18f
        }
        if (blinkAt >= 0L) {
            val e = now - blinkAt
            var open = blink(e)
            if (blinkTwice && e >= BLINK_GAP_MS) open = min(open, blink(e - BLINK_GAP_MS))
            p.openL = open; p.openR = open
            if (e >= (if (blinkTwice) BLINK_GAP_MS + BLINK_MS else BLINK_MS)) {
                blinkAt = -1L
                nextBlinkAt = now + (if (busy) 4200L else 2300L) + rnd.nextInt(if (busy) 3000 else 3800)
            }
        }
        // ---- a tap: a happy squint as it lands ----
        if (squintAt >= 0L) {
            val e = now - squintAt
            if (e >= SQUINT_MS) squintAt = -1L
            else {
                val q = e / SQUINT_MS.toFloat()
                val sq = 1f - 0.62f * sin(PI.toFloat() * q)       // shut part-way, and open again
                p.openL = min(p.openL, sq); p.openR = min(p.openR, sq)
            }
        }
        // ---- the greeting ----
        if (greetAt >= 0L) {
            val e = (now - greetAt).toFloat()
            if (e >= GREET_MS) greetAt = -1L
            else greetPose(e, p)
        }
        return p
    }

    private fun currentGazeX(now: Long): Float {
        if (gazeAt < 0L) return gazeToX
        val q = ((now - gazeAt) / SACCADE_MS.toFloat()).coerceIn(0f, 1f)
        return gazeFromX + (gazeToX - gazeFromX) * easeOutCubic(q)
    }

    private fun currentGazeY(now: Long): Float {
        if (gazeAt < 0L) return gazeToY
        val q = ((now - gazeAt) / SACCADE_MS.toFloat()).coerceIn(0f, 1f)
        return gazeFromY + (gazeToY - gazeFromY) * easeOutCubic(q)
    }

    /** One blink [e] ms in: shut quickly, open a little slower, as lids do. */
    private fun blink(e: Long): Float = when {
        e < 0L -> 1f
        e < BLINK_CLOSE_MS -> 1f - 0.92f * easeInQuad(e / BLINK_CLOSE_MS.toFloat())
        e < BLINK_MS -> 0.08f + 0.92f * easeOutCubic((e - BLINK_CLOSE_MS) / (BLINK_MS - BLINK_CLOSE_MS).toFloat())
        else -> 1f
    }

    /**
     * The greeting, [e] ms in (GREET_MS long): rises with its eyes shut
     * (0-420), wakes wide-eyed (380-600), looks at you, tilts its head in a
     * hello (980-1420) and winks (1150-1350).
     */
    private fun greetPose(e: Float, p: Pose) {
        // The climb: from 26 below with a little overshoot above its rest.
        p.rise = 26f * (1f - easeOutBack((e / 420f).coerceIn(0f, 1f)))
        // Eyes: shut, then open wide, the wideness easing away.
        val wake = ((e - 380f) / 220f).coerceIn(0f, 1f)
        val eyes = 0.08f + 0.92f * easeOutCubic(wake)
        p.openL = eyes; p.openR = eyes
        p.wide = 1f + 0.2f * easeOutCubic(wake) * (1f - ((e - 600f) / 700f).coerceIn(0f, 1f))
        // Looking at you from the moment it wakes.
        if (e > 420f) { p.lookX = 0f; p.lookY = -0.2f }
        // The hello: a head tilt there and back.
        val tq = ((e - 980f) / 440f).coerceIn(0f, 1f)
        p.tilt = 8f * sin(PI.toFloat() * tq) * (1f - 0.3f * tq)
        // The wink, the right eye only.
        val wq = ((e - 1150f) / 200f).coerceIn(0f, 1f)
        if (wq > 0f && wq < 1f) p.openR = min(p.openR, 1f - 0.9f * sin(PI.toFloat() * wq))
    }

    private fun easeOutCubic(x: Float): Float { val u = 1f - x; return 1f - u * u * u }
    private fun easeInQuad(x: Float): Float = x * x
    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1f
        val u = x - 1f
        return 1f + c3 * u * u * u + c1 * u * u
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

    private fun drawOwl(c: Canvas, d: Float, t: Float, pose: Pose, popRaw: Float) {
        val pop = max(0f, popRaw)
        // A slow breath, the tap's hop, and the greeting's climb.
        val bob = -1.2f * (0.5f + 0.5f * sin(t * PI.toFloat() / 2f)) - pop * 6f + pose.rise
        c.save(); c.translate(0f, bob)
        // Squash and stretch from the tap's spring - taller on the way up,
        // wider for a moment as it lands - about the bottom of the body,
        // and the greeting's head tilt about the same point.
        val stretch = popRaw * 0.07f
        c.scale(1f - stretch * 0.6f, 1f + stretch, 60f, 120f)
        val breath = 1f + 0.012f * sin(t * 2f * PI.toFloat() / 3.8f)
        c.scale(1f, breath, 60f, 120f)
        if (pose.tilt != 0f) c.rotate(pose.tilt, 60f, 118f)
        shaded.shader = owlBody; shaded.alpha = 255
        c.drawPath(tuftL, shaded); c.drawPath(tuftR, shaded)
        c.drawCircle(60f, 112f, 50f, shaded)
        shaded.shader = owlSheen; shaded.alpha = (255 * (0.2f + 0.1f * d)).toInt()
        c.drawCircle(60f, 112f, 50f, shaded)
        fill.shader = null; fill.color = Color.WHITE; fill.alpha = 31
        path.reset()
        path.addCircle(45f, 86f, 17f, Path.Direction.CW); path.addCircle(75f, 86f, 17f, Path.Direction.CW)
        c.drawPath(path, fill)                        // the facial discs, one shape so the overlap is not doubled
        // Where it is looking (pose: held, then a quick saccade), with the
        // faint tremor living eyes have even when they hold still.
        val lx = pose.lookX + 0.12f * sin(t * 1.7f)
        val ly = pose.lookY + 0.1f * sin(t * 1.3f + 1f)
        val wide = (1 + 0.15f * pop) * pose.wide
        drawEye(c, 45f, 86f, 12.5f, lx, ly, pose.openL, wide, scleraL)
        drawEye(c, 75f, 86f, 12.5f, lx, ly, pose.openR, wide, scleraR)
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
        const val GREET_MS = 1700L
        const val BLINK_CLOSE_MS = 65L
        const val BLINK_MS = 165L
        const val BLINK_GAP_MS = 230L
        const val BLINK_SPAN_MS = BLINK_GAP_MS + BLINK_MS
        const val SACCADE_MS = 95L
        const val SQUINT_MS = 300L
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
