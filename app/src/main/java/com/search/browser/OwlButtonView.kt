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
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
 * phase at night.
 *
 * The owl is alive the way a small animal is: its eyes hold on something and
 * dart to the next (quick for a small glance, slower and with a little settle
 * for a big one), the head follows the eyes a beat later, it blinks at uneven
 * intervals - now and then twice, now and then only half way - breathes in
 * shorter than it breathes out, and now and then tilts its head at what it is
 * looking at. It says hello when the browser opens ([requestGreet]), squashes
 * under a finger ([press]) and hops when tapped ([pop]), fluffs up under a
 * long press ([fluff]), follows the loading ring round with its eyes
 * ([setLoad]) and blinks contentedly when the page is in.
 *
 * Everything is drawn in a 120x120 box scaled to the view. The springs are
 * solved exactly for each frame's length, so a hop is the same hop at 30, 60
 * or 120fps. Frames: every display frame (at most 60fps) only while something
 * is moving, 30fps for half a minute after the owl appears or is tapped, 15fps
 * when it just sits there, and slower in Battery Saver; nothing at all while
 * it is off screen or [covered], and a still frame looking at you when the
 * phone's animations are off. Night Owl tabs ([privateMode]) always show the
 * night.
 */
class OwlButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * The owl's hellos. COLD: the browser has just started - it rises into its
     * window, wakes (groggily by day, at once at night, being an owl), looks
     * at you, tilts its head and winks. RETURN: back after twenty minutes or
     * more - it was dozing, opens its eyes wide and tilts its head. NOD: back
     * after a few minutes - a look, a slow blink and a small nod.
     */
    enum class Hello { COLD, RETURN, NOD }

    /** Night Owl: always the night sky. Turned on, the owl's pupils widen and it blinks slowly. */
    var privateMode = false
        set(value) {
            if (field == value) return
            field = value
            targetAt = 0L
            if (animationsOn() && isAttachedToWindow) {
                val now = SystemClock.uptimeMillis()
                if (value) {
                    surpriseUntil = now + 700L
                    wideUntil = now + 700L
                    slowBlinkNext = true
                    nextBlinkAt = now + 500L
                } else {
                    nextBlinkAt = now + 150L
                }
                smoothUntil = max(smoothUntil, now + 900L)
                kick()
            }
            invalidate()
        }

    /**
     * Something opaque is over the owl - the search page, the tab deck - so
     * nothing is drawn at all. Uncovered, it is caught looking up at the page
     * and glances back at you.
     */
    var covered = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                cutPendingGreeting()
                stop()
            } else {
                peekAt = SystemClock.uptimeMillis()
                kick()
            }
        }

    private var shownDay = -1f       // eased toward the clock's value, so a jump plays as a short transition
    private var shownTwilight = 0f
    private var lastFrame = 0L
    private val clock0 = SystemClock.uptimeMillis()
    private var running = false
    private var moonAge = OwlSky.moonAge(System.currentTimeMillis())
    private var moonAgeAt = 0L
    private var target = floatArrayOf(1f, 0f)
    private var targetAt = 0L
    private var powerAt = 0L
    private var powerSave = false
    /** One display frame, and the shortest gap between frames while something moves (at most 60fps). */
    private var vsyncMs = 16.67f
    private var eventMs = 16.67f
    /** Until when the owl moves at 30fps; after that it settles into a calmer rate. */
    private var livelyUntil = 0L
    /** Until when every display frame is drawn: a tap, a glance, a blink in progress. */
    private var smoothUntil = 0L
    private var hiddenSince = -1L
    private var peekAt = -1L

    // ---- A damped spring, solved exactly for each frame ----
    //
    // Stepping a spring by Euler's method gives a different motion at every
    // frame rate: the tap's hop came out at less than half its height at
    // 20fps. The closed form below lands in the same place whatever the step.
    private class Sp(var x: Float, private val w: Float, private val z: Float) {
        var v = 0f
        var target = x
        fun step(dt: Float) {
            if (dt <= 0f) return
            val a = x - target
            if (z < 1f) {
                val wd = w * sqrt(1f - z * z)
                val b = (v + z * w * a) / wd
                val e = exp(-z * w * dt)
                val c = cos(wd * dt)
                val s = sin(wd * dt)
                x = target + e * (a * c + b * s)
                v = e * ((b * wd - z * w * a) * c - (a * wd + z * w * b) * s)
            } else {
                val e = exp(-w * dt)
                val k = v + w * a
                x = target + (a + k * dt) * e
                v = (v - w * k * dt) * e
            }
        }
        fun snap(to: Float) { x = to; v = 0f; target = to }
        fun moving(): Boolean = abs(x - target) > 0.002f || abs(v) > 0.02f
    }

    private val hop = Sp(0f, 16.12f, 0.45f)       // units below its rest; negative is up
    private val squash = Sp(0f, 30f, 0.8f)        // under the finger: < 0 squashed, > 0 fluffed up
    private val wide = Sp(1f, 17.3f, 0.5f)        // eye size
    private val headX = Sp(0f, 12f, 1f)           // the head following the eyes
    private val headY = Sp(0f, 12f, 1f)
    private val tilt = Sp(0f, 9.49f, 0.55f)       // degrees: curiosity, a tap, a long press
    private val tufts = Sp(1f, 14f, 0.6f)         // 1 up, 0 flat (asleep), above 1 perked
    private val surprise = Sp(0f, 12f, 1f)        // pupils wide
    private val lids = Sp(0f, 25f, 1f)            // eyes closed by a press (a smile) or a long press
    private val springs = arrayOf(hop, squash, wide, headX, headY, tilt, tufts, surprise, lids)
    private val pursuitX = Sp(0f, 14f, 1f)        // the eyes following the loading ring
    private val pursuitY = Sp(YOU_Y, 14f, 1f)

    private val rnd = java.util.Random(SystemClock.uptimeMillis())

    // ---- gaze: hold on something, then a saccade to the next ----
    private var nextGazeAt = 0L
    private var gazeAt = -1L
    private var gazeDur = 95L
    private var gazeBig = false
    private var gazeFromX = 0f
    private var gazeFromY = YOU_Y
    private var gazeToX = 0f
    private var gazeToY = YOU_Y
    private var gazeToYou = true
    private var gazeFromYou = true
    private var forceYou = false
    private var lastLookX = 0f
    private var lastLookY = YOU_Y
    private var pickX = 0f
    private var pickY = 0f

    // ---- blinks ----
    private var nextBlinkAt = 0L
    private var blinkAt = -1L
    private var blinkSlow = false
    private var blinkShut = 0.06f
    private var blinkGap = -1L
    private var slowBlinkNext = false

    // ---- everything else alive ----
    private var tapAt = -1_000_000L
    private var surpriseUntil = 0L
    private var wideUntil = 0L
    private var perkUntil = 0L
    private var nodAt = -1L
    private var nextTiltAt = 0L
    private var tiltHoldUntil = -1L
    private var fluffing = false
    private var breathPh = 0f

    // ---- loading ----
    private var busy = false
    private var ringTarget = 0f
    private var ringShown = 0f
    private var nextGlanceAt = 0L
    private var glanceUntil = 0L

    // ---- the greeting ----
    private var pendingKind = NONE
    private var pendingSince = 0L
    private var gatesSince = -1L
    private var calmFrames = 0
    private var greetKind = NONE
    private var greetAt = -1L
    private var greetOff = 0f
    private var greetSign = -1f
    private var greetWink = true
    private var greetFromX = 0f
    private var greetFromY = YOU_Y
    private var lastGreetAt = -1L

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
    // The shooting star's trail, in its own coordinates; faded with the paint's alpha.
    private val starTrail = LinearGradient(0f, 0f, -16f, -5.5f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
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

    // ---------- What the bar tells it ----------

    /** A finger on the button: the owl squashes a little and smiles; off, it lets go. */
    fun press(down: Boolean) {
        if (!animationsOn()) return
        val now = SystemClock.uptimeMillis()
        if (down) {
            squash.target = -0.05f
            lids.target = 0.3f
        } else {
            squash.target = 0f
            lids.target = 0f
            if (fluffing) { fluffing = false; tilt.target = 0f }
        }
        livelyUntil = max(livelyUntil, now + LIVELY_MS)
        smoothUntil = max(smoothUntil, now + 450L)
        kick()
    }

    /** Held: the owl fluffs up, closes its eyes contentedly and leans in, until let go ([press]). */
    fun fluff() {
        if (!animationsOn()) return
        fluffing = true
        squash.target = 0.04f
        lids.target = 0.7f
        tilt.target = if (rnd.nextBoolean()) 5f else -5f
        tiltHoldUntil = -1L
        smoothUntil = max(smoothUntil, SystemClock.uptimeMillis() + 600L)
        kick()
    }

    /** A tap: a springy hop - stretching as it goes up, squashing as it lands - wide eyes on you. */
    fun pop() {
        if (!animationsOn()) return
        val now = SystemClock.uptimeMillis()
        tapAt = now
        cutPendingGreeting()
        hop.v = max(-260f, hop.v - 211f)
        wide.v += 3f
        lids.target = 0f
        surpriseUntil = now + 600L
        perkUntil = now + 300L
        if (greetKind == NONE || greetKind == RISE) startSaccade(now, 0f, YOU_Y, 60L)
        if (rnd.nextBoolean()) tilt.v += if (rnd.nextBoolean()) 25f else -25f
        livelyUntil = now + LIVELY_MS
        smoothUntil = max(smoothUntil, now + 450L)
        kick()
    }

    /**
     * Asks for a hello. It plays only once the owl can be seen and the app has
     * settled - the splash gone, nothing in front of it, a few smooth frames in
     * a row - so it is never half-hidden or stuttering through start-up jank.
     * A COLD hello puts the owl to sleep low in its window at once, so the
     * first frame after the splash is already the sleeping owl. Not within a
     * minute of the last one; nothing when the phone's animations are off.
     */
    fun requestGreet(kind: Hello) {
        if (!animationsOn()) return
        val now = SystemClock.uptimeMillis()
        val k = when (kind) {
            Hello.COLD -> COLD
            Hello.RETURN -> RETURN
            Hello.NOD -> NOD
        }
        if (greetKind != NONE || pendingKind == COLD || pendingKind == RISE) return
        if (k != COLD && lastGreetAt >= 0L && now - lastGreetAt < GREET_COOLDOWN_MS) return
        pendingKind = k
        pendingSince = now
        gatesSince = -1L
        calmFrames = 0
        if (k == COLD) tufts.snap(0f)
        kick()
        invalidate()
    }

    /**
     * The tab on screen's load, 0-100. While it loads the owl follows the
     * ring's head round with its eyes, glancing back at you now and then;
     * when it is in, a slow contented blink and a small nod. [jump]: a tab
     * switch - take the new tab's state with no reaction.
     */
    fun setLoad(percent: Int, jump: Boolean = false) {
        val loading = percent in 1..99
        ringTarget = (percent.coerceIn(0, 100)) / 100f
        val now = SystemClock.uptimeMillis()
        if (jump) {
            ringShown = ringTarget
            if (loading && !busy) startPursuit(now)
            busy = loading
            if (!loading) { gazeFromX = lastLookX; gazeFromY = lastLookY; gazeToX = lastLookX; gazeToY = lastLookY; gazeAt = -1L }
            return
        }
        if (busy == loading) return
        busy = loading
        if (loading) {
            startPursuit(now)
        } else if (animationsOn() && greetKind == NONE && pendingKind == NONE) {
            // Done: a contented blink, a little nod, and back to you.
            startBlink(now, slow = true)
            nodAt = now
            startSaccade(now, 0f, YOU_Y)
            nextGazeAt = now + 1800L
        } else {
            gazeFromX = lastLookX; gazeFromY = lastLookY; gazeToX = lastLookX; gazeToY = lastLookY; gazeAt = -1L
        }
        kick()
    }

    private fun startPursuit(now: Long) {
        pursuitX.snap(lastLookX)
        pursuitY.snap(lastLookY)
        ringShown = ringTarget
        nextGlanceAt = now + lognormal(2200f, 0.4f).toLong()
        glanceUntil = 0L
    }

    // ---------- Frames ----------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        readVsync()
        kick()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val now = SystemClock.uptimeMillis()
        if (isVisible) {
            // Back after a while out of sight (the bars hid as a page
            // scrolled): caught looking up at the page.
            if (hiddenSince >= 0L && now - hiddenSince >= 1500L) peekAt = now
            hiddenSince = -1L
            kick()
        } else {
            if (hiddenSince < 0L) hiddenSince = now
            stop()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) kick() else stop()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            // The next frame is booked by onDraw, which knows what this one
            // needs; this is only in case that draw never comes.
            postOnAnimationDelayed(this, if (animationsOn()) 250L else 60_000L)
        }
    }

    private fun kick() {
        if (running || covered || !isAttachedToWindow || windowVisibility != VISIBLE || !isShown) return
        livelyUntil = max(livelyUntil, SystemClock.uptimeMillis() + LIVELY_MS)
        running = true
        lastFrame = 0L
        postOnAnimation(tick)
    }

    private fun stop() {
        running = false
        removeCallbacks(tick)
    }

    private fun readVsync() {
        val hz = display?.refreshRate ?: 60f
        vsyncMs = 1000f / (if (hz > 1f) hz else 60f)
        // Every display frame up to 60Hz; every second one above that.
        eventMs = if (vsyncMs < 14f) vsyncMs * 2f else vsyncMs
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
        // The frame's own time (the display's vsync), not the moment this
        // runs, so motion does not jitter with how late the draw started.
        val now = drawingTime.let { if (it > 0L) it else SystemClock.uptimeMillis() }
        val interval = if (lastFrame == 0L) 0L else max(0L, now - lastFrame)
        val dt = min(0.05f, interval / 1000f)
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
        if (now - powerAt > 5000L || powerAt == 0L) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerSave = pm?.isPowerSaveMode == true
            readVsync()
            powerAt = now
        }
        if (shownDay < 0f || !moving) {
            shownDay = target[0]; shownTwilight = target[1]
        } else {
            val k = min(1f, dt * 3f)
            shownDay += (target[0] - shownDay) * k
            shownTwilight += (target[1] - shownTwilight) * k
        }
        if (moving && pendingKind != NONE) checkGreetGates(now, interval)

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
            drawOwl(canvas, shownDay, pose(now, dt, t))
        } else {
            still.reset()
            drawOwl(canvas, shownDay, still)
        }
        canvas.restore()

        // The next frame. Every display frame (to 60fps) while something
        // moves - a blink drawn at 15fps is two frames and reads as a
        // flicker - 30fps for half a minute after the owl appears or is
        // tapped, then a calm 15fps for as long as it simply sits there; in
        // Battery Saver 30fps for moments and 8fps otherwise. Booked half a
        // display frame early, so it lands on the frame it is meant for.
        if (running) {
            removeCallbacks(tick)
            if (moving) {
                val eventful = now < smoothUntil || greetKind != NONE || pendingKind != NONE ||
                    blinkAt >= 0L || (gazeAt >= 0L && now - gazeAt < gazeDur) || nodAt >= 0L ||
                    springs.any { it.moving() }
                val budget = when {
                    powerSave -> if (eventful) 33.3f else 125f
                    eventful -> eventMs
                    busy || now < livelyUntil -> 33.3f
                    else -> 66.7f
                }
                val due = now + (budget - vsyncMs / 2f).toLong()
                postOnAnimationDelayed(tick, max(0L, due - SystemClock.uptimeMillis()))
            } else {
                postDelayed(tick, 60_000L)    // still frame: only follow the clock
            }
        }
    }

    // ---------- The greeting ----------

    /** A pending hello starts once the owl is on screen, in front, with the app settled. */
    private fun checkGreetGates(now: Long, interval: Long) {
        val age = now - pendingSince
        if (pendingKind == COLD && age > 15_000L) {
            // Something held it back all this time: just a plain rise.
            pendingKind = RISE
            startGreeting(now)
            return
        }
        if ((pendingKind == RETURN || pendingKind == NOD) && age > 3_000L) { pendingKind = NONE; return }
        val clear = isShown && hasWindowFocus() && !covered
        if (!clear) { gatesSince = -1L; calmFrames = 0; return }
        if (gatesSince < 0L) { gatesSince = now; calmFrames = 0; return }
        calmFrames = if (interval in 1L..(eventMs * 1.5f + 1f).toLong()) calmFrames + 1 else 0
        val settled = calmFrames >= 3 || now - gatesSince >= 700L
        // A cold start waits a moment more, for the splash to finish leaving.
        val wait = if (pendingKind == COLD) 250L else 0L
        if (settled && now - gatesSince >= wait) startGreeting(now)
    }

    /** Covered or tapped before it began: a cold hello becomes a plain rise, the others are dropped. */
    private fun cutPendingGreeting() {
        if (pendingKind == COLD) pendingKind = RISE
        else if (pendingKind == RETURN || pendingKind == NOD) pendingKind = NONE
    }

    private fun startGreeting(now: Long) {
        greetKind = pendingKind
        pendingKind = NONE
        greetAt = now
        lastGreetAt = now
        // An owl is wide awake at night: no grogginess, and it all comes sooner.
        greetOff = if (greetKind == COLD && (shownDay < 0.3f || privateMode)) -220f else 0f
        greetSign = -greetSign               // the head tilts the other way each time
        greetWink = greetKind == COLD || (greetKind == RETURN && rnd.nextBoolean())
        greetFromX = lastLookX
        greetFromY = lastLookY
        blinkAt = -1L
        val len = greetLength().toLong()
        nextBlinkAt = now + len + 600L
        nextGazeAt = now + len + 300L
        livelyUntil = now + len + LIVELY_MS
    }

    private fun greetLength(): Float = when (greetKind) {
        COLD -> 1900f + greetOff
        RETURN -> 950f
        NOD -> 420f
        else -> 300f
    }

    /** Hands the owl back to its own life from where the greeting left it: no jump in the eyes. */
    private fun endGreeting(now: Long) {
        val kind = greetKind
        greetKind = NONE
        greetAt = -1L
        wide.target = 1f
        surprise.target = 0f
        tufts.target = 1f
        if (kind != RISE) {
            gazeFromX = 0f; gazeToX = 0f; gazeFromY = YOU_Y; gazeToY = YOU_Y
            gazeAt = -1L
            gazeToYou = true
            lastLookX = 0f; lastLookY = YOU_Y
            // Keeps eye contact a while after saying hello.
            nextGazeAt = now + (1.6f * lognormal(1600f, 0.5f)).toLong().coerceAtMost(8000L)
            nextBlinkAt = now + if (kind == COLD) 2400L else blinkInterval(now)
        }
        livelyUntil = now + LIVELY_MS
    }

    /** The greeting, [e] ms in. Sets the pose directly, and the springs' targets for this moment. */
    private fun greetPose(e: Float, p: Pose) {
        when (greetKind) {
            COLD -> {
                val o = greetOff
                // The climb: from 30 below, a little past its rest (5%) and back.
                p.rise = 30f * (1f - easeOutBack((e / 520f).coerceIn(0f, 1f), 1.2f))
                val lid = if (o == 0f) when {
                    // By day it wakes groggily: half opens, closes again, then opens.
                    e < 450f -> 0.05f
                    e < 610f -> 0.05f + 0.40f * easeOutQuad((e - 450f) / 160f)
                    e < 730f -> 0.45f
                    e < 800f -> 0.45f - 0.40f * easeInQuad((e - 730f) / 70f)
                    else -> 0.05f + 0.95f * easeOutCubic(((e - 800f) / 130f).coerceAtMost(1f))
                } else {
                    if (e < 450f) 0.05f else 0.05f + 0.95f * easeOutCubic(((e - 450f) / 130f).coerceAtMost(1f))
                }
                p.openL = lid; p.openR = lid
                val awake = 800f + o
                wide.target = if (e >= awake && e < 1500f + o) 1.18f else 1f
                surprise.target = if (e >= awake && e < 1700f + o) 1f else 0f
                tufts.target = if (e >= awake) 1f else 0f
                // Drowsy eyes up, then a quick look straight at you.
                val q = ((e - awake) / 80f).coerceIn(0f, 1f)
                p.lookX = 0f
                p.lookY = -2.0f + (YOU_Y + 2.0f) * minJerk(q)
                p.verge = 0.25f * q
                p.tilt += greetSign * tiltCurve(e - (1050f + o), 7f, 210f, 140f, 320f)
                if (greetWink) wink(e - (1300f + o), p)
            }
            RETURN -> {
                // It had dozed off: eyes half shut, then open and wide.
                val lid = if (e < 120f) 0.55f
                    else 0.55f + 0.45f * easeOutCubic(((e - 120f) / 130f).coerceAtMost(1f))
                p.openL = lid; p.openR = lid
                wide.target = if (e >= 120f && e < 700f) 1.15f else 1f
                surprise.target = if (e >= 120f && e < 800f) 0.7f else 0f
                val q = ((e - 120f) / 80f).coerceIn(0f, 1f)
                lookTowardYou(p, q)
                p.tilt += greetSign * tiltCurve(e - 380f, 5f, 180f, 80f, 260f)
                if (greetWink) wink(e - 600f, p)
            }
            NOD -> {
                lookTowardYou(p, (e / 70f).coerceIn(0f, 1f))
                val b = e - 120f
                val lid = when {
                    b < 0f -> 1f
                    b < 90f -> 1f - 0.94f * easeInOutSine(b / 90f)
                    b < 120f -> 0.06f
                    b < 300f -> 0.06f + 0.94f * easeOutCubic((b - 120f) / 180f)
                    else -> 1f
                }
                p.openL = min(p.openL, lid); p.openR = min(p.openR, lid)
                p.rise += 1.4f * sin(PI.toFloat() * (b / 300f).coerceIn(0f, 1f))
            }
            RISE -> p.rise = 30f * (1f - easeOutCubic((e / 300f).coerceIn(0f, 1f)))
        }
    }

    private fun lookTowardYou(p: Pose, q: Float) {
        val m = minJerk(q)
        p.lookX = greetFromX + (0f - greetFromX) * m
        p.lookY = greetFromY + (YOU_Y - greetFromY) * m
        p.verge = 0.25f * q
    }

    /** Up on a gentle overshoot, held, and back on an ease - velocity continuous all the way. */
    private fun tiltCurve(e: Float, amp: Float, up: Float, hold: Float, down: Float): Float = when {
        e <= 0f -> 0f
        e < up -> amp * easeOutBack(e / up, 1.0f)
        e < up + hold -> amp
        e < up + hold + down -> amp * (1f - easeInOutSine((e - up - hold) / down))
        else -> 0f
    }

    /** The right eye winks; the left narrows a little with it, as a face does. */
    private fun wink(e: Float, p: Pose) {
        if (e <= 0f || e >= 220f) return
        val r = when {
            e < 70f -> 1f - 0.95f * easeInQuad(e / 70f)
            e < 110f -> 0.05f
            else -> 0.05f + 0.95f * easeOutCubic((e - 110f) / 110f)
        }
        p.openR = min(p.openR, r)
        p.openL = min(p.openL, 1f - 0.15f * sin(PI.toFloat() * e / 220f))
    }

    // ---------- One frame of the owl ----------

    /** Where everything about the owl is, for one frame. */
    private class Pose {
        var rise = 0f          // units down from its rest: the greeting's climb, a nod
        var hop = 0f           // the tap's hop (negative is up)
        var squash = 0f        // > 0 taller, < 0 squashed
        var breath = 0f        // 0 out .. 1 in
        var tilt = 0f          // degrees, about the bottom of the body
        var openL = 1f
        var openR = 1f
        var wide = 1f
        var lookX = 0f
        var lookY = -0.3f
        var verge = 0f         // both eyes turned in a little: looking at someone close
        var pupil = 0.38f      // pupil radius, as a share of the eye's
        var tufts = 1f
        var headX = 0f
        var headY = 0f
        fun reset() {
            rise = 0f; hop = 0f; squash = 0f; breath = 0f; tilt = 0f; openL = 1f; openR = 1f
            wide = 1f; lookX = 0f; lookY = -0.3f; verge = 0f; pupil = 0.38f; tufts = 1f
            headX = 0f; headY = 0f
        }
    }
    private val livePose = Pose()
    private val still = Pose()

    private fun pose(now: Long, dt: Float, t: Float): Pose {
        val p = livePose
        p.reset()
        val greeting = greetKind == COLD || greetKind == RETURN || greetKind == NOD
        if (peekAt >= 0L) {
            if (pendingKind == NONE && greetKind == NONE && !busy) peek(now)
            peekAt = -1L
        }

        // ---- gaze ----
        if (busy && !greeting) {
            ringShown += (ringTarget - ringShown) * (1f - exp(-dt / 0.16f))
            if (now >= nextGlanceAt) {
                glanceUntil = now + 450L
                nextGlanceAt = now + 450L + lognormal(2200f, 0.4f).toLong()
            }
            if (now < glanceUntil) {
                pursuitX.target = 0f; pursuitY.target = YOU_Y
            } else {
                // The head of the ring, going round clockwise from the top.
                val th = -PI.toFloat() / 2f + 2f * PI.toFloat() * ringShown
                pursuitX.target = 2.3f * cos(th)
                pursuitY.target = (2.3f * sin(th)).coerceIn(-2.4f, 0.9f)
            }
            pursuitX.step(dt); pursuitY.step(dt)
            p.lookX = pursuitX.x
            p.lookY = pursuitY.x
        } else {
            if (nextGazeAt == 0L) nextGazeAt = now + 900L
            if (!greeting && now >= nextGazeAt) newGaze(now)
            val q = gazeEase(now)
            p.lookX = gazeFromX + (gazeToX - gazeFromX) * q
            p.lookY = gazeFromY + (gazeToY - gazeFromY) * q
            val lin = if (gazeAt < 0L) 1f else ((now - gazeAt) / gazeDur.toFloat()).coerceIn(0f, 1f)
            p.verge = when {
                gazeToYou && gazeFromYou -> 0.25f
                gazeToYou -> 0.25f * lin
                gazeFromYou -> 0.25f * (1f - lin)
                else -> 0f
            }
        }

        // ---- blinks: uneven, sometimes two at once, sometimes half; fewer while watching a load ----
        if (!greeting) {
            if (nextBlinkAt == 0L) nextBlinkAt = now + 1800L + rnd.nextInt(2000)
            if (blinkAt < 0L && now >= nextBlinkAt) startBlink(now, slowBlinkNext)
        }
        if (blinkAt >= 0L) {
            val e = now - blinkAt
            var o = blinkOpen(e)
            if (blinkGap > 0L) o = min(o, blinkOpen(e - blinkGap))
            p.openL = min(p.openL, o); p.openR = min(p.openR, o)
            if (e >= blinkSpan()) {
                blinkAt = -1L
                nextBlinkAt = now + blinkInterval(now)
            }
        }

        // ---- the hello, or the sleeping pose while one waits to start ----
        when (pendingKind) {
            COLD -> { p.rise = 30f; p.openL = 0.05f; p.openR = 0.05f; tufts.target = 0f }
            RISE -> p.rise = 30f
            RETURN -> { p.openL = min(p.openL, 0.55f); p.openR = min(p.openR, 0.55f) }
        }
        if (greetKind != NONE) {
            val e = (now - greetAt).toFloat().coerceAtLeast(0f)
            if (e >= greetLength()) endGreeting(now) else greetPose(e, p)
        }
        if (nodAt >= 0L) {
            val q = (now - nodAt) / 300f
            if (q >= 1f) nodAt = -1L else p.rise += 1.4f * sin(PI.toFloat() * q.coerceAtLeast(0f))
        }

        // ---- now and then a curious tilt toward what it is looking at ----
        if (!greeting && !busy && !powerSave && !fluffing) {
            if (tiltHoldUntil >= 0L) {
                if (now >= tiltHoldUntil) { tiltHoldUntil = -1L; tilt.target = 0f }
            } else if (nextTiltAt == 0L) {
                nextTiltAt = now + lognormal(11000f, 0.5f).toLong()
            } else if (now >= nextTiltAt) {
                if (abs(gazeToX) >= 1.2f && gazeAt >= 0L && now - gazeAt >= 250L) {
                    tilt.target = (if (gazeToX > 0f) 1f else -1f) * (4f + 2f * rnd.nextFloat())
                    tiltHoldUntil = now + 900L + rnd.nextInt(700)
                    nextTiltAt = now + lognormal(11000f, 0.5f).coerceIn(4000f, 30000f).toLong()
                } else {
                    nextTiltAt = now + 400L
                }
            }
        }

        // ---- springs ----
        if (!greeting) {
            wide.target = if (now < wideUntil) 1.1f else 1f
            surprise.target = if (now < surpriseUntil) 1f else 0f
            if (pendingKind != COLD) tufts.target = if (now < perkUntil) 1.25f else 1f
        }
        // The head follows the eyes a beat behind, the face more than the body.
        headX.target = (0.55f * p.lookX).coerceIn(-1.3f, 1.3f)
        headY.target = 0.3f * p.lookY
        for (s in springs) s.step(dt)
        if (hop.x < -11f) { hop.x = -11f; if (hop.v < 0f) hop.v = 0f }

        // ---- breathing: in shorter than out, quicker for a while after a tap ----
        val period = if (busy) 3600f else 4200f - 1200f * exp(-(now - tapAt) / 4000f)
        breathPh = (breathPh + dt * 1000f / period) % 1f
        p.breath = if (breathPh < 0.42f) smooth(breathPh / 0.42f) else 1f - smooth((breathPh - 0.42f) / 0.58f)

        lastLookX = p.lookX
        lastLookY = p.lookY
        // The faint tremor living eyes have even while they hold still, less
        // when they hold on you.
        val drift = if (gazeToYou && !busy) 0.7f else 1f
        p.lookX += 0.12f * drift * sin(t * 1.7f)
        p.lookY += 0.1f * drift * sin(t * 1.3f + 1f)

        p.hop = hop.x
        p.squash = (0.00035f * abs(hop.v) - 0.025f * max(0f, hop.x)).coerceIn(-0.06f, 0.08f) + squash.x
        p.wide *= wide.x
        p.tilt += tilt.x
        p.tufts = tufts.x
        p.pupil = 0.36f + 0.06f * (1f - shownDay.coerceIn(0f, 1f)) + 0.06f * surprise.x
        p.headX = headX.x
        p.headY = headY.x
        val l = lids.x.coerceIn(0f, 0.95f)
        if (l > 0.001f) { p.openL = min(p.openL, 1f - l); p.openR = min(p.openR, 1f - l) }
        return p
    }

    /** Caught looking up at the page, it glances back at you. */
    private fun peek(now: Long) {
        gazeFromX = 0f; gazeToX = 0f; gazeFromY = -2.4f; gazeToY = -2.4f
        gazeAt = -1L
        gazeToYou = false; gazeFromYou = false
        lastLookX = 0f; lastLookY = -2.4f
        forceYou = true
        nextGazeAt = now + 140L
        if (rnd.nextBoolean() && blinkAt < 0L) nextBlinkAt = now + 200L
    }

    /** Picks what to look at next and goes there. */
    private fun newGaze(now: Long) {
        var tries = 0
        do {
            pickGaze()
            tries++
        } while (tries < 2 && hypot(pickX - lastLookX, pickY - lastLookY) < 0.6f)
        startSaccade(now, pickX, pickY)
        val hold = lognormal(1600f, 0.5f).coerceIn(500f, 6000f) * (if (gazeToYou) 1.6f else 1f)
        nextGazeAt = now + gazeDur + hold.toLong()
    }

    /**
     * You, most often; the page above; up to either side; along the bar; or a
     * small re-fixation near where it already looks.
     */
    private fun pickGaze() {
        if (forceYou) { forceYou = false; pickX = 0f; pickY = YOU_Y; return }
        val r = rnd.nextFloat()
        when {
            r < 0.30f -> { pickX = 0f; pickY = YOU_Y }
            r < 0.48f -> { pickX = (rnd.nextFloat() * 2f - 1f) * 0.6f; pickY = -2.3f }
            r < 0.60f -> { pickX = -1.9f; pickY = -1.9f }
            r < 0.72f -> { pickX = 1.9f; pickY = -1.9f }
            r < 0.81f -> { pickX = -2.3f; pickY = -0.5f }
            r < 0.90f -> { pickX = 2.3f; pickY = -0.5f }
            else -> {
                fun nudge() = (0.2f + 0.3f * rnd.nextFloat()) * (if (rnd.nextBoolean()) 1f else -1f)
                pickX = (lastLookX + nudge()).coerceIn(-2.4f, 2.4f)
                pickY = (lastLookY + nudge()).coerceIn(-2.4f, 0.9f)
            }
        }
    }

    /**
     * A saccade: small ones quick, big ones slower and landing with a
     * slight overshoot that settles, as eyes do. A big one often brings a
     * blink with it.
     */
    private fun startSaccade(now: Long, tx: Float, ty: Float, fixedMs: Long = -1L) {
        gazeFromX = lastLookX; gazeFromY = lastLookY
        gazeFromYou = abs(lastLookX) < 0.05f && abs(lastLookY - YOU_Y) < 0.05f
        gazeToX = tx; gazeToY = ty
        gazeToYou = tx == 0f && ty == YOU_Y
        val dist = hypot(tx - gazeFromX, ty - gazeFromY)
        gazeDur = if (fixedMs > 0L) fixedMs else (45f + 18f * dist).toLong()
        gazeBig = dist >= 1.2f
        gazeAt = now
        if (dist >= 2.5f && blinkAt < 0L && greetKind == NONE && rnd.nextFloat() < 0.35f) {
            startBlink(now, slow = false)
            nextBlinkAt = max(nextBlinkAt, now + 1200L)
        }
        smoothUntil = max(smoothUntil, now + gazeDur + 20L)
    }

    private fun gazeEase(now: Long): Float {
        if (gazeAt < 0L) return 1f
        val q = ((now - gazeAt) / gazeDur.toFloat()).coerceIn(0f, 1f)
        if (q >= 1f) return 1f
        return if (gazeBig) easeOutBack(q, 0.9f) else minJerk(q)
    }

    private fun startBlink(now: Long, slow: Boolean) {
        blinkAt = now
        blinkSlow = slow
        slowBlinkNext = false
        blinkShut = if (!slow && rnd.nextFloat() < 0.15f) 0.4f else 0.06f
        blinkGap = if (!slow && rnd.nextFloat() < 0.12f) 170L + rnd.nextInt(71) else -1L
        smoothUntil = max(smoothUntil, now + blinkSpan())
    }

    private fun blinkSpan(): Long = (if (blinkSlow) 320L else 240L) + max(0L, blinkGap)

    /** One blink [e] ms in: shut quickly, a moment closed, open a little slower, as lids do. */
    private fun blinkOpen(e: Long): Float {
        if (e < 0L) return 1f
        val shut = blinkShut
        return if (blinkSlow) when {
            e < 110L -> 1f - (1f - shut) * easeInOutSine(e / 110f)
            e < 160L -> shut
            e < 320L -> shut + (1f - shut) * easeOutCubic((e - 160L) / 160f)
            else -> 1f
        } else when {
            e < 75L -> 1f - (1f - shut) * easeInQuad(e / 75f)
            e < 100L -> shut
            e < 240L -> shut + (1f - shut) * easeOutCubic((e - 100L) / 140f)
            else -> 1f
        }
    }

    /** About four seconds, unevenly (1.2-9s); longer while it watches a load or at night, shorter after a tap. */
    private fun blinkInterval(now: Long): Long {
        var ms = lognormal(3600f, 0.45f).coerceIn(1200f, 9000f)
        if (busy) ms *= 1.6f
        if (shownDay < 0.3f || privateMode) ms *= 1.15f
        if (now - tapAt < 5000L) ms *= 0.8f
        return ms.toLong()
    }

    private fun lognormal(median: Float, sigma: Float): Float =
        median * exp(sigma * rnd.nextGaussian().toFloat())

    private fun easeOutCubic(x: Float): Float { val u = 1f - x; return 1f - u * u * u }
    private fun easeOutQuad(x: Float): Float = 1f - (1f - x) * (1f - x)
    private fun easeInQuad(x: Float): Float = x * x
    private fun easeInOutSine(x: Float): Float = 0.5f - 0.5f * cos(PI.toFloat() * x)
    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)
    private fun minJerk(x: Float): Float = x * x * x * (10f - 15f * x + 6f * x * x)
    private fun easeOutBack(x: Float, c1: Float = 1.70158f): Float {
        val c3 = c1 + 1f
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
                // One gradient made once, moved into place, faded by alpha:
                // no new shader on every frame of the streak.
                c.save(); c.translate(x0, y0)
                stroke.shader = starTrail
                stroke.strokeWidth = 1.2f; stroke.alpha = (230 * (1 - sp) * starA).toInt().coerceIn(0, 255)
                c.drawLine(0f, 0f, -16f, -5.5f, stroke)
                stroke.shader = null; stroke.alpha = 255
                c.restore()
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

    private fun drawOwl(c: Canvas, d: Float, pose: Pose) {
        c.save()
        // The greeting's climb, a nod, the tap's hop, and the breath.
        c.translate(0f, pose.rise + pose.hop - 0.9f * pose.breath)
        // Squash and stretch - taller on the way up, wider as it lands or
        // under a finger - and the chest filling, all about the bottom of
        // the body; the head's tilt about the same point.
        val s = pose.squash
        c.scale(1f - 0.5f * s, 1f + s, 60f, 120f)
        c.scale(1f + 0.005f * pose.breath, 1f + 0.014f * pose.breath, 60f, 120f)
        if (pose.tilt != 0f) c.rotate(pose.tilt, 60f, 118f)
        // The head turning after the eyes: the face slides furthest, the
        // tufts less and the body least.
        val hx = pose.headX.coerceIn(-1.3f, 1.3f)
        shaded.shader = owlBody; shaded.alpha = 255
        val flat = (1f - pose.tufts) * 10f            // degrees: laid back when asleep, perked when surprised
        c.save(); c.translate(0.7f * hx, 0f)
        c.save(); c.rotate(-flat, 29f, 84f); c.drawPath(tuftL, shaded); c.restore()
        c.save(); c.rotate(flat, 91f, 84f); c.drawPath(tuftR, shaded); c.restore()
        c.restore()
        c.save(); c.translate(0.4f * hx, 0f)
        c.drawCircle(60f, 112f, 50f, shaded)
        shaded.shader = owlSheen; shaded.alpha = (255 * (0.2f + 0.1f * d)).toInt()
        c.drawCircle(60f, 112f, 50f, shaded)
        c.restore()
        c.save(); c.translate(hx, 0f)
        fill.shader = null; fill.color = Color.WHITE; fill.alpha = 31
        path.reset()
        path.addCircle(45f, 86f, 17f, Path.Direction.CW); path.addCircle(75f, 86f, 17f, Path.Direction.CW)
        c.drawPath(path, fill)                        // the facial discs, one shape so the overlap is not doubled
        c.translate(0f, pose.headY)
        // Where it is looking, each eye turned in a little when that is you.
        drawEye(c, 45f, 86f, 12.5f, pose.lookX + pose.verge, pose.lookY, pose.openL, pose.wide, pose.pupil, scleraL)
        drawEye(c, 75f, 86f, 12.5f, pose.lookX - pose.verge, pose.lookY, pose.openR, pose.wide, pose.pupil, scleraR)
        shaded.shader = beak; shaded.alpha = 255
        c.drawPath(beakPath, shaded)
        c.restore()
        c.restore()
    }

    private fun drawEye(c: Canvas, cx: Float, cy: Float, r: Float, lx: Float, ly: Float, open: Float, wide: Float, pupil: Float, sclera: Shader) {
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
        c.drawCircle(cx + lx * 1.15f, cy + ly * 1.15f, r * pupil, fill)
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
        const val LIVELY_MS = 30_000L
        const val GREET_COOLDOWN_MS = 60_000L
        /** Where "looking at you" is, in eye units: straight out, a touch up. */
        const val YOU_Y = -0.3f
        // Greeting kinds; RISE is a cold hello cut short, a plain climb.
        const val NONE = 0
        const val COLD = 1
        const val RETURN = 2
        const val NOD = 3
        const val RISE = 4
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
