package com.mythos.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.mythos.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

/*
 * Intro pembuka aplikasi (hanya saat aplikasi dibuka dari keadaan mati).
 *
 * Urutan (ms) - semuanya diturunkan dari satu jam [clock] sehingga animasi dan suara (res/raw/intro_chime.ogg) selalu sinkron:
 *   0..1300   64 piksel oranye melayang dari segala arah dan menyusun maskot, diiringi kedipan nada naik
 *   1300      benturan: maskot memantul (squash & stretch), cincin kejut, percikan piksel, dan akor lonceng
 *   1300..    mata muncul, lalu berkedip di 2000
 *   1500..    tulisan "MYTHOS" muncul huruf demi huruf
 *   2900      selesai; layar memudar 450 ms. Ketuk layar kapan saja untuk melewati.
 */

internal const val INTRO_IMPACT_MS = 1300f
internal const val INTRO_END_MS = 2900f
private const val FADE_MS = 450

private val INTRO_BG = Color(0xFFF9F9F6)
private val INTRO_ACCENT = Color(0xFFD97757)
private val INTRO_ACCENT_LIGHT = Color(0xFFF0A27F)
private val INTRO_EYE = Color(0xFF141412)
private val INTRO_TEXT = Color(0xFF2B2A28)

internal const val INTRO_COLS = 12
internal const val INTRO_ROWS = 8

/** Bentuk maskot sama persis dengan ikon aplikasi (12 x 8 sel). */
internal val INTRO_SHAPE = arrayOf(
    "..########..",
    "..########..",
    "############",
    "############",
    "..########..",
    "..########..",
    "..#.#..#.#..",
    "..#.#..#.#..",
)

internal class IntroCell(
    val col: Int,
    val row: Int,
    /** Waktu mulai terbang (ms). */
    val delay: Float,
    /** Lama terbang (ms). */
    val duration: Float,
    /** Posisi awal relatif terhadap tujuan, dalam satuan sel. */
    val fromX: Float,
    val fromY: Float,
    val spin: Float,
) {
    val arrival: Float get() = delay + duration
}

internal fun buildIntroCells(): List<IntroCell> {
    val rnd = Random(11)
    val out = ArrayList<IntroCell>()
    for (r in 0 until INTRO_ROWS) {
        for (c in 0 until INTRO_COLS) {
            if (INTRO_SHAPE[r][c] != '#') continue
            val angle = rnd.nextFloat() * (2f * PI.toFloat())
            val radius = 14f + rnd.nextFloat() * 18f
            out.add(
                IntroCell(
                    col = c,
                    row = r,
                    delay = rnd.nextFloat() * 450f,
                    duration = 650f + rnd.nextFloat() * 200f,
                    fromX = cos(angle) * radius,
                    fromY = sin(angle) * radius,
                    spin = (rnd.nextFloat() - 0.5f) * 540f,
                )
            )
        }
    }
    return out
}

private fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x
private fun easeOutCubic(x: Float): Float { val u = 1f - clamp01(x); return 1f - u * u * u }
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val u = clamp01(x) - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

@Composable
fun IntroSplash(onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val clock = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val sound = remember { IntroSound(ctx.applicationContext) }
    var leaving by remember { mutableStateOf(false) }
    val cells = remember { buildIntroCells() }

    DisposableEffect(Unit) { onDispose { sound.release() } }

    fun leave(fadeMs: Int) {
        if (leaving) return
        leaving = true
        scope.launch {
            fade.animateTo(1f, tween(fadeMs, easing = LinearEasing))
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        sound.play()
        clock.animateTo(INTRO_END_MS, tween(INTRO_END_MS.toInt(), easing = LinearEasing))
        leave(FADE_MS)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - fade.value }
            .background(INTRO_BG)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!leaving) {
                    sound.fadeOutAndRelease()
                    leave(300)
                }
            }
    ) {
        val cellDp: Dp = minOf(maxWidth * 0.60f / INTRO_COLS, maxHeight * 0.30f / INTRO_ROWS)
        val centerY: Dp = maxHeight * 0.42f
        val t = clock.value

        Canvas(Modifier.fillMaxSize()) {
            drawIntro(t, cells, cellDp.toPx(), Offset(size.width / 2f, centerY.toPx()))
        }

        // Tulisan "MYTHOS": huruf muncul satu per satu, naik sambil memudar masuk.
        val textCenterY = centerY + cellDp * (INTRO_ROWS / 2f + 3.4f)
        Row(
            Modifier
                .align(Alignment.Center)
                .offset(y = textCenterY - maxHeight / 2f),
        ) {
            "MYTHOS".forEachIndexed { i, ch ->
                val p = easeOutCubic((t - (1500f + i * 70f)) / 450f)
                Text(
                    text = ch.toString(),
                    color = INTRO_TEXT,
                    fontWeight = FontWeight.Bold,
                    fontSize = (cellDp.value * 1.25f).sp,
                    letterSpacing = (cellDp.value * 0.22f).sp,
                    modifier = Modifier.graphicsLayer {
                        alpha = p
                        translationY = (1f - p) * cellDp.toPx() * 1.2f
                    },
                )
            }
        }
    }
}

private fun DrawScope.drawIntro(t: Float, cells: List<IntroCell>, cell: Float, center: Offset) {
    val ox = center.x - INTRO_COLS / 2f * cell
    val oy = center.y - INTRO_ROWS / 2f * cell
    val feetY = oy + INTRO_ROWS * cell

    // --- Kilau lembut di belakang maskot: menguat saat benturan, lalu mengendap.
    val impact = (t - INTRO_IMPACT_MS) / 1000f
    val glowA = when {
        t < INTRO_IMPACT_MS - 500f -> 0f
        t < INTRO_IMPACT_MS -> 0.10f * (t - (INTRO_IMPACT_MS - 500f)) / 500f
        else -> 0.12f + 0.20f * exp(-impact * 3.2f)
    }
    if (glowA > 0f) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(INTRO_ACCENT.copy(alpha = glowA), Color.Transparent),
                center = center,
                radius = cell * 10f,
            ),
            radius = cell * 10f,
            center = center,
        )
    }

    // --- Bayangan di tanah: makin jelas saat piksel mendarat, melebar saat maskot gepeng.
    val landed = clamp01((t - 300f) / 1000f)
    val k = if (impact > 0f) exp(-6f * impact) * sin(18f * impact) * 0.12f else 0f
    val shadowW = cell * 9f * (1f + k)
    drawOval(
        color = Color.Black.copy(alpha = 0.08f * landed),
        topLeft = Offset(center.x - shadowW / 2f, feetY + cell * 0.25f),
        size = Size(shadowW, cell * 0.9f),
    )

    // --- Maskot: pegas gepeng-memanjang di titik tumpu kaki, lalu melayang pelan.
    val sy = 1f - k
    val sx = 1f + k * 0.8f
    val bob = if (t > 1900f) sin((t - 1900f) / 450f) * cell * 0.12f else 0f
    val pivot = Offset(center.x, feetY)
    scale(scaleX = sx, scaleY = sy, pivot = pivot) {
        val dy = bob
        for (c in cells) {
            val p = clamp01((t - c.delay) / c.duration)
            if (p <= 0f) continue
            val e = easeOutCubic(p)
            val left = ox + c.col * cell
            val top = oy + c.row * cell + dy
            val x = left + c.fromX * cell * (1f - e)
            val y = top + c.fromY * cell * (1f - e)
            val sz = cell * (0.35f + 0.65f * e)
            val inset = (cell - sz) / 2f
            rotate(degrees = c.spin * (1f - e), pivot = Offset(x + cell / 2f, y + cell / 2f)) {
                drawRect(
                    color = INTRO_ACCENT.copy(alpha = clamp01(p * 3f)),
                    topLeft = Offset(x + inset, y + inset),
                    size = Size(sz + 0.6f, sz + 0.6f),
                )
            }
        }

        // Mata: muncul membal di 1300 ms, berkedip di 2000 ms.
        val eyeP = easeOutBack((t - 1300f) / 350f)
        if (eyeP > 0f) {
            val blink = if (t in 2000f..2180f) 1f - 0.9f * sin(PI.toFloat() * (t - 2000f) / 180f) else 1f
            for (col in intArrayOf(3, 8)) {
                val w = cell * eyeP
                val h = cell * eyeP * blink
                drawRect(
                    color = INTRO_EYE,
                    topLeft = Offset(ox + col * cell + (cell - w) / 2f, oy + 1 * cell + dy + (cell - h) / 2f),
                    size = Size(w, h),
                )
            }
        }
    }

    // --- Cincin kejut dari titik benturan.
    val ring = clamp01((t - INTRO_IMPACT_MS) / 800f)
    if (t >= INTRO_IMPACT_MS && ring < 1f) {
        drawCircle(
            color = INTRO_ACCENT.copy(alpha = 0.45f * (1f - ring)),
            radius = cell * (3f + 12f * easeOutCubic(ring)),
            center = center,
            style = Stroke(width = cell * 0.28f * (1f - ring) + 1f),
        )
    }

    // --- Percikan piksel yang meledak keluar dari maskot.
    val burst = clamp01((t - INTRO_IMPACT_MS) / 1100f)
    if (t >= INTRO_IMPACT_MS && burst < 1f) {
        val n = 18
        for (i in 0 until n) {
            val a = i * (2f * PI.toFloat() / n) + (i % 3) * 0.12f
            val reach = 2.5f + 8.5f * (0.65f + 0.35f * ((i * 7) % 5) / 4f)
            val d = cell * (2f + reach * easeOutCubic(burst))
            val s = cell * 0.34f * (1f - burst)
            val px = center.x + cos(a) * d
            val py = center.y + sin(a) * d * 0.8f
            rotate(degrees = 45f * i + burst * 180f, pivot = Offset(px, py)) {
                drawRect(
                    color = (if (i % 2 == 0) INTRO_ACCENT else INTRO_ACCENT_LIGHT).copy(alpha = 1f - burst),
                    topLeft = Offset(px - s / 2f, py - s / 2f),
                    size = Size(s, s),
                )
            }
        }
    }
}

/** Pemutar efek suara intro (res/raw/intro_chime.ogg). Mengikuti volume/mode senyap sistem; gagal = diam saja. */
class IntroSound(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun play() {
        release()
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = MediaPlayer.create(ctx, R.raw.intro_chime, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE) ?: return
            p.setVolume(0.9f, 0.9f)
            p.setOnCompletionListener { release() }
            player = p
            p.start()
        } catch (_: Exception) {
            release()
        }
    }

    /** Dipakai saat intro dilewati: suara dikecilkan cepat agar tidak terpotong kasar. */
    fun fadeOutAndRelease() {
        val p = player ?: return
        val steps = 6
        for (i in 1..steps) {
            handler.postDelayed({
                try { val v = 0.9f * (1f - i / steps.toFloat()); p.setVolume(v, v) } catch (_: Exception) { }
                if (i == steps) release()
            }, i * 40L)
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        try { player?.release() } catch (_: Exception) { }
        player = null
    }
}
