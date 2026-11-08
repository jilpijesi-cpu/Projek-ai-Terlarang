package com.mythos.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn

/**
 * Ukuran terukur dari rekaman layar referensi (kerapatan 3,0 → 1 px = 1/3 dp).
 */
internal val Int.px: Dp get() = (this / 3f).dp

object MythosColors {
    val Background = Color(0xFFF9F9F6)   // terukur dari rekaman acuan terbaru (#F9F9F6)
    val Ink = Color(0xFF13110F)
    val Hint = Color(0xFF6B6965)
    val Border = Color(0xFFDADAD8)
    val Card = Color(0xFFF3F3F1)
    val Accent = Color(0xFFD77656)
    val Bubble = Color(0xFFEFEDEB)
    val BubbleBorder = Color(0xFFD0CECC)
    val Chip = Color(0xFFE8E8E6)
    val Icon = Color(0xFF141413)   // sama dengan Ink: ikon pekat dan tajam seperti rekaman referensi
    val Send = Color(0xFFBF5837)
    val NavBar = Color(0xFFFDFDFD)
    val Composer = Color(0xFFFDFDFD)
    val ComposerBorder = Color(0xFFDCDCDC)
    val Placeholder = Color(0xFF7A7874)
    val Chevron = Color(0xFF6B6965)
    val StepBase = Color(0xFF373735)
    val StepPeak = Color(0xFFC4C4C2)
    val Error = Color(0xFFB3261E)
}

val MythosSerif: FontFamily = FontFamily.Serif
val MythosSans: FontFamily = FontFamily.SansSerif

internal fun bodyStyle(color: Color = MythosColors.Ink) = TextStyle(
    fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 23.sp, color = color
)

internal fun inputStyle(color: Color) = TextStyle(
    fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 16.5.sp, lineHeight = 24.sp, color = color
)

// ---------------------------------------------------------------- bilah bawah sistem

@Composable
internal fun NavBarFill() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(MythosColors.NavBar)
    )
}

// ---------------------------------------------------------------- tombol & ikon bulat (1:1 video)

@Composable
internal fun GlyphButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "", content: @Composable () -> Unit) {
    Box(
        modifier
            .size(48.dp)
            .semantics { if (label.isNotEmpty()) contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label.ifEmpty { null },
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Efek "timbul" seperti rekaman referensi: cincin cahaya putih 1 dp tepat di dalam garis tepi (inset highlight) dan, untuk kartu besar,
 * bayangan dalam yang sangat halus di bagian atas. `corner` = null untuk lingkaran. Digambar DI BELAKANG isi, jadi ikon/teks tetap tajam.
 */
internal fun Modifier.embossedRing(corner: Dp? = null, insetShadow: Boolean = false): Modifier = drawWithContent {
    val stroke = 1.dp.toPx()
    val inset = 0.67.dp.toPx() + stroke / 2f
    val w = size.width
    val h = size.height
    val ring = Color(0xE6FFFFFF)
    if (corner == null) {
        drawCircle(ring, radius = minOf(w, h) / 2f - inset, style = Stroke(stroke))
    } else {
        val r = (corner.toPx() - inset).coerceAtLeast(0f)
        if (insetShadow) {
            val sh = 26.dp.toPx()
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0x0B000000), Color.Transparent), startY = inset, endY = sh),
                topLeft = Offset(inset, inset), size = Size(w - 2 * inset, sh), cornerRadius = CornerRadius(r)
            )
        }
        drawRoundRect(ring, topLeft = Offset(inset, inset), size = Size(w - 2 * inset, h - 2 * inset), cornerRadius = CornerRadius(r), style = Stroke(stroke))
    }
    drawContent()
}

/**
 * Tombol bulat 44 dp (132 px pada video 1170×2532) dengan latar #F8F8F6, garis tepi tipis #DCDCD9, cincin cahaya putih di dalam tepi (efek timbul), dan bayangan halus.
 * Dipakai pada bilah atas (menu kiri, incognito kanan) dan tombol tutup (X) pada lembar bawah & layar suara.
 */
@Composable
internal fun CircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    fill: Color = Color(0xFFF3F3F1),
    borderColor: Color = Color(0xFFDCDCD9),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(44.dp)
            .shadow(2.dp, CircleShape, ambientColor = Color(0x14000000), spotColor = Color(0x1E000000))
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(0.67.dp, borderColor, CircleShape)
            .embossedRing()
            .semantics { if (label.isNotEmpty()) contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label.ifEmpty { null },
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Tiga garis menu sesuai video: dua garis atas sama panjang (18 dp = 54 px), garis bawah setengah panjang (9 dp = 27 px).
 */
@Composable
internal fun MenuGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val d = density
        val t = 1.67f * d
        val x0 = center.x - 9f * d + t / 2f
        val xFull = center.x + 9f * d - t / 2f
        val xHalf = center.x - t / 2f
        drawLine(MythosColors.Icon, Offset(x0, center.y - 7.17f * d), Offset(xFull, center.y - 7.17f * d), t, StrokeCap.Round)
        drawLine(MythosColors.Icon, Offset(x0, center.y), Offset(xFull, center.y), t, StrokeCap.Round)
        drawLine(MythosColors.Icon, Offset(x0, center.y + 7.17f * d), Offset(xHalf, center.y + 7.17f * d), t, StrokeCap.Round)
    }
}

@Composable
internal fun NewChatGlyph() {
    Canvas(Modifier.size(48.dp)) {
        val d = density
        val fill = MythosColors.Icon
        val c = Offset(center.x + 0.5f * d, center.y - 0.6f * d)
        drawCircle(fill, 7.5f * d, c)
        val tail = Path().apply {
            moveTo(center.x - 7.9f * d, center.y + 7.9f * d)
            lineTo(center.x - 6.6f * d, center.y + 0.8f * d)
            lineTo(center.x - 1.2f * d, center.y + 5.6f * d)
            close()
        }
        drawPath(tail, fill)
        val w = 1.4f * d
        drawLine(Color.White, Offset(c.x - 3f * d, c.y), Offset(c.x + 3f * d, c.y), w, StrokeCap.Round)
        drawLine(Color.White, Offset(c.x, c.y - 3f * d), Offset(c.x, c.y + 3f * d), w, StrokeCap.Round)
    }
}

@Composable
internal fun KebabGlyph() {
    Canvas(Modifier.size(48.dp)) {
        val d = density
        listOf(-5.7f, 0.7f, 6.7f).forEach { drawCircle(MythosColors.Icon, 1.4f * d, Offset(center.x, center.y + it * d)) }
    }
}

/** Ikon bilah atas gaya baru (polos, tanpa lingkaran): garis menu 14,3 dp berjarak 5 dp. */
@Composable
internal fun HeaderMenuGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val d = density
        val t = 1.67f * d
        val x0 = center.x - 7.15f * d + t / 2f
        val xFull = center.x + 7.15f * d - t / 2f
        val xHalf = center.x - t / 2f
        drawLine(MythosColors.Icon, Offset(x0, center.y - 5f * d), Offset(xFull, center.y - 5f * d), t, StrokeCap.Round)
        drawLine(MythosColors.Icon, Offset(x0, center.y), Offset(xFull, center.y), t, StrokeCap.Round)
        drawLine(MythosColors.Icon, Offset(x0, center.y + 5f * d), Offset(xHalf, center.y + 5f * d), t, StrokeCap.Round)
    }
}

/** Ikon sunting (kotak bertepi dengan pensil) untuk chat baru, seperti rekaman acuan. */
@Composable
internal fun EditGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val d = density
        val w = 1.6f * d
        val l = 4.5f * d; val t = 5.5f * d; val r = 17.5f * d; val b = 18.5f * d; val c = 3f * d
        val box = Path().apply {
            moveTo(r - 5.5f * d, t)
            lineTo(l + c, t); quadraticBezierTo(l, t, l, t + c)
            lineTo(l, b - c); quadraticBezierTo(l, b, l + c, b)
            lineTo(r - c, b); quadraticBezierTo(r, b, r, b - c)
            lineTo(r, t + 5.5f * d)
        }
        drawPath(box, MythosColors.Icon, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(MythosColors.Icon, Offset(9.2f * d, 14.8f * d), Offset(18.6f * d, 5.4f * d), 2.1f * d, StrokeCap.Round)
    }
}

/** Tiga titik tegak (4 dp, berjarak 5 dp) untuk bilah atas gaya baru. */
@Composable
internal fun HeaderKebabGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val d = density
        listOf(-5f, 0f, 5f).forEach { drawCircle(MythosColors.Icon, 1.9f * d, Offset(center.x, center.y + it * d)) }
    }
}

/** Ikon hantu garis luar (18 dp = 54 px pada video), dengan warna yang dapat disesuaikan untuk mode normal maupun aktif. */
@Composable
internal fun GhostGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width / 24f
        val p = Path().apply {
            moveTo(3.5f * s, 12f * s)
            arcTo(Rect(3.5f * s, 3.5f * s, 20.5f * s, 20.5f * s), 180f, 180f, false)
            lineTo(20.5f * s, 20.2f * s)
            quadraticBezierTo(17.7f * s, 17.0f * s, 14.8f * s, 20.2f * s)
            quadraticBezierTo(12.0f * s, 17.0f * s, 9.2f * s, 20.2f * s)
            quadraticBezierTo(6.3f * s, 17.0f * s, 3.5f * s, 20.2f * s)
            close()
        }
        drawPath(p, color, style = Stroke(width = 1.85f * s, join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawCircle(color, 1.35f * s, Offset(9.3f * s, 11.8f * s))
        drawCircle(color, 1.35f * s, Offset(14.7f * s, 11.8f * s))
    }
}

/**
 * Tombol kanan atas mode incognito sesuai video:
 * - Normal: lingkaran 44 dp terang berisi GhostGlyph hitam.
 * - Aktif (Chat samaran): lingkaran luar 44 dp terang berisi lingkaran gelap 35,33 dp (#2F2F2B) dan GhostGlyph putih.
 */
@Composable
internal fun IncognitoToggleButton(
    incognito: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircleIconButton(
        onClick = onToggle,
        modifier = modifier,
        label = if (incognito) "Keluar dari mode samaran" else "Mode samaran",
        fill = Color(0xFFF3F3F1),
        borderColor = if (incognito) Color(0xFFC4C4C2) else Color(0xFFD4D4D2),
    ) {
        if (incognito) {
            Box(
                Modifier.size(35.33.dp).clip(CircleShape).background(Color(0xFF2F2F2B)),
                contentAlignment = Alignment.Center
            ) {
                GhostGlyph(Color.White)
            }
        } else {
            GhostGlyph(MythosColors.Ink)
        }
    }
}

@Composable
private fun PlusGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val d = density
        val t = 1.45f * d
        drawLine(MythosColors.Ink, Offset(center.x - 6.8f * d, center.y), Offset(center.x + 6.8f * d, center.y), t, StrokeCap.Round)
        drawLine(MythosColors.Ink, Offset(center.x, center.y - 6.8f * d), Offset(center.x, center.y + 6.8f * d), t, StrokeCap.Round)
    }
}

@Composable
private fun MicGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val d = density
        val st = Stroke(1.4f * d, cap = StrokeCap.Round)
        val col = MythosColors.Icon
        drawRoundRect(col, Offset(center.x - 3.3f * d, center.y - 8.8f * d), Size(6.6f * d, 10.6f * d), CornerRadius(3.3f * d), st)
        drawArc(col, 0f, 180f, false, Offset(center.x - 6.0f * d, center.y - 6.2f * d), Size(12.0f * d, 11.6f * d), style = st)
        drawLine(col, Offset(center.x, center.y + 5.4f * d), Offset(center.x, center.y + 8.6f * d), 1.4f * d, StrokeCap.Round)
        drawLine(col, Offset(center.x - 2.8f * d, center.y + 8.6f * d), Offset(center.x + 2.8f * d, center.y + 8.6f * d), 1.4f * d, StrokeCap.Round)
    }
}

@Composable
private fun StopGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val d = density
        drawRoundRect(
            MythosColors.Ink, Offset(center.x - 6.5f * d, center.y - 6.5f * d), Size(13f * d, 13f * d),
            CornerRadius(1.6f * d), Stroke(1.4f * d)
        )
    }
}

@Composable
private fun ArrowUpGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val d = density
        val w = 1.65f * d
        drawLine(Color.White, Offset(center.x, center.y + 6.8f * d), Offset(center.x, center.y - 6.8f * d), w, StrokeCap.Round)
        drawLine(Color.White, Offset(center.x - 5.6f * d, center.y - 1.2f * d), Offset(center.x, center.y - 6.8f * d), w, StrokeCap.Round)
        drawLine(Color.White, Offset(center.x + 5.6f * d, center.y - 1.2f * d), Offset(center.x, center.y - 6.8f * d), w, StrokeCap.Round)
    }
}

/** Panah ">" 6×10 dp, abu #6B6965. */
@Composable
internal fun ChevronGlyph(rotation: Float = 0f, color: Color = MythosColors.Chevron) {
    Canvas(Modifier.size(width = 6.5.dp, height = 11.dp).rotate(rotation)) {
        val d = density
        val p = Path().apply {
            moveTo(0.8f * d, 0.8f * d)
            lineTo(size.width - 0.8f * d, size.height / 2f)
            lineTo(0.8f * d, size.height - 0.8f * d)
        }
        drawPath(p, color, style = Stroke(1.1f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Panah ganda atas-bawah (⌄) di samping "Indonesia" pada layar suara. */
@Composable
internal fun UpDownChevronGlyph(color: Color = Color(0xFF4B4948)) {
    Canvas(Modifier.size(width = 10.dp, height = 14.dp)) {
        val d = density
        val st = Stroke(1.25f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val up = Path().apply {
            moveTo(1.5f * d, 5.2f * d)
            lineTo(size.width / 2f, 1.8f * d)
            lineTo(size.width - 1.5f * d, 5.2f * d)
        }
        val down = Path().apply {
            moveTo(1.5f * d, 8.8f * d)
            lineTo(size.width / 2f, 12.2f * d)
            lineTo(size.width - 1.5f * d, 8.8f * d)
        }
        drawPath(up, color, style = st)
        drawPath(down, color, style = st)
    }
}

// ---------------------------------------------------------------- ikon lembar bawah & laci (1:1 video)

/** Ikon Kamera pada petak "Tambahkan ke chat". */
@Composable
internal fun CameraGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(23.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(6.8f * s, 6.8f * s)
            lineTo(8.5f * s, 4.2f * s)
            lineTo(15.5f * s, 4.2f * s)
            lineTo(17.2f * s, 6.8f * s)
            lineTo(19.2f * s, 6.8f * s)
            arcTo(Rect(18.2f * s, 6.8f * s, 21.8f * s, 10.4f * s), 270f, 90f, false)
            lineTo(21.8f * s, 17.6f * s)
            arcTo(Rect(18.2f * s, 15.8f * s, 21.8f * s, 19.4f * s), 0f, 90f, false)
            lineTo(4.8f * s, 19.4f * s)
            arcTo(Rect(2.2f * s, 15.8f * s, 5.8f * s, 19.4f * s), 90f, 90f, false)
            lineTo(2.2f * s, 8.6f * s)
            arcTo(Rect(2.2f * s, 6.8f * s, 5.8f * s, 10.4f * s), 180f, 90f, false)
            close()
        }
        drawPath(body, color, style = st)
        drawCircle(color, 3.4f * s, Offset(12f * s, 13.0f * s), style = st)
    }
}

/** Ikon Foto (gunung + matahari dalam bingkai persegi membulat) pada petak "Tambahkan ke chat". */
@Composable
internal fun PhotoGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(23.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(color, Offset(3.2f * s, 3.2f * s), Size(17.6f * s, 17.6f * s), CornerRadius(3.4f * s), style = st)
        drawCircle(color, 1.85f * s, Offset(14.6f * s, 8.8f * s), style = st)
        val mtn = Path().apply {
            moveTo(3.5f * s, 16.5f * s)
            lineTo(8.2f * s, 12.2f * s)
            quadraticBezierTo(9.2f * s, 11.4f * s, 10.2f * s, 12.2f * s)
            lineTo(17.8f * s, 20.4f * s)
        }
        drawPath(mtn, color, style = st)
    }
}

/** Ikon File (halaman terlipat + panah naik di kanan bawah) pada petak "Tambahkan ke chat". */
@Composable
internal fun FileUploadGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(23.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val doc = Path().apply {
            moveTo(12.5f * s, 19.8f * s)
            lineTo(6.2f * s, 19.8f * s)
            arcTo(Rect(4.0f * s, 17.2f * s, 6.8f * s, 19.8f * s), 90f, 90f, false)
            lineTo(4.0f * s, 5.2f * s)
            arcTo(Rect(4.0f * s, 3.0f * s, 6.8f * s, 5.8f * s), 180f, 90f, false)
            lineTo(12.2f * s, 3.0f * s)
            lineTo(17.5f * s, 8.3f * s)
            lineTo(17.5f * s, 11.0f * s)
        }
        drawPath(doc, color, style = st)
        val fold = Path().apply {
            moveTo(12.0f * s, 3.2f * s)
            lineTo(12.0f * s, 8.3f * s)
            lineTo(17.2f * s, 8.3f * s)
        }
        drawPath(fold, color, style = st)
        // Panah ke atas di kanan bawah
        drawLine(color, Offset(17.5f * s, 21.0f * s), Offset(17.5f * s, 13.5f * s), 1.75f * s, StrokeCap.Round)
        val arr = Path().apply {
            moveTo(14.5f * s, 16.5f * s)
            lineTo(17.5f * s, 13.5f * s)
            lineTo(20.5f * s, 16.5f * s)
        }
        drawPath(arr, color, style = st)
    }
}

/** Ikon biru dua foto bertumpuk pada baris "Tampilkan foto terbaru". */
@Composable
internal fun RecentPhotosGlyph(color: Color = Color(0xFF1B5091)) {
    Canvas(Modifier.size(21.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Foto depan (kiri atas)
        drawRoundRect(color, Offset(2.5f * s, 2.5f * s), Size(14.2f * s, 14.2f * s), CornerRadius(3.0f * s), style = st)
        drawCircle(color, 1.3f * s, Offset(11.6f * s, 7.2f * s))
        val mtn = Path().apply {
            moveTo(3.0f * s, 13.5f * s)
            lineTo(6.8f * s, 10.0f * s)
            lineTo(13.8f * s, 16.4f * s)
        }
        drawPath(mtn, color, style = st)
        // Foto belakang (kanan bawah)
        val back = Path().apply {
            moveTo(16.7f * s, 7.2f * s)
            lineTo(18.5f * s, 7.2f * s)
            arcTo(Rect(17.5f * s, 7.2f * s, 21.2f * s, 10.9f * s), 270f, 90f, false)
            lineTo(21.2f * s, 17.5f * s)
            arcTo(Rect(17.5f * s, 17.5f * s, 21.2f * s, 21.2f * s), 0f, 90f, false)
            lineTo(10.9f * s, 21.2f * s)
            arcTo(Rect(7.2f * s, 17.5f * s, 10.9f * s, 21.2f * s), 90f, 90f, false)
            lineTo(7.2f * s, 16.7f * s)
        }
        drawPath(back, color, style = st)
    }
}

/** Ikon Proyek (kotak trapesium membulat dengan 2 garis bertumpuk di atasnya) sesuai laci & lembar bawah video. */
@Composable
internal fun ProyekGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(21.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(7.5f * s, 3.2f * s), Offset(16.5f * s, 3.2f * s), 1.75f * s, StrokeCap.Round)
        drawLine(color, Offset(5.2f * s, 6.8f * s), Offset(18.8f * s, 6.8f * s), 1.75f * s, StrokeCap.Round)
        val box = Path().apply {
            moveTo(3.2f * s, 10.4f * s)
            lineTo(20.8f * s, 10.4f * s)
            lineTo(19.6f * s, 19.2f * s)
            quadraticBezierTo(19.4f * s, 20.8f * s, 17.6f * s, 20.8f * s)
            lineTo(6.4f * s, 20.8f * s)
            quadraticBezierTo(4.6f * s, 20.8f * s, 4.4f * s, 19.2f * s)
            close()
        }
        drawPath(box, color, style = st)
    }
}

/** Ikon Konektor (3 kotak menyatu + 1 kotak terpisah di kanan atas) sesuai video. */
@Composable
internal fun ConnectorGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(21.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Kotak terpisah di kanan atas
        drawRoundRect(color, Offset(13.2f * s, 2.8f * s), Size(7.6f * s, 7.6f * s), CornerRadius(1.8f * s), style = st)
        // Tiga kotak menyatu (kiri atas, kiri bawah, kanan bawah)
        val lShape = Path().apply {
            moveTo(3.2f * s, 8.2f * s)
            lineTo(10.6f * s, 8.2f * s)
            lineTo(10.6f * s, 13.4f * s)
            lineTo(18.0f * s, 13.4f * s)
            lineTo(18.0f * s, 20.8f * s)
            lineTo(3.2f * s, 20.8f * s)
            close()
        }
        drawPath(lShape, color, style = st)
        drawLine(color, Offset(3.2f * s, 13.4f * s), Offset(10.6f * s, 13.4f * s), 1.7f * s)
        drawLine(color, Offset(10.6f * s, 13.4f * s), Offset(10.6f * s, 20.8f * s), 1.7f * s)
    }
}

/** Ikon Chat di laci: dua gelembung percakapan bertumpuk sesuai video. */
@Composable
internal fun DrawerChatGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(21.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Gelembung kiri atas
        val b1 = Path().apply {
            moveTo(15.2f * s, 8.5f * s)
            arcTo(Rect(2.2f * s, 2.2f * s, 15.8f * s, 14.8f * s), 350f, -230f, false)
            lineTo(2.4f * s, 15.2f * s)
            lineTo(6.2f * s, 14.5f * s)
        }
        drawPath(b1, color, style = st)
        // Gelembung kanan bawah
        val b2 = Path().apply {
            moveTo(10.2f * s, 15.5f * s)
            arcTo(Rect(9.2f * s, 9.2f * s, 21.8f * s, 20.8f * s), 180f, 235f, false)
            lineTo(21.6f * s, 21.2f * s)
            lineTo(17.8f * s, 20.5f * s)
            arcTo(Rect(9.2f * s, 9.2f * s, 21.8f * s, 20.8f * s), 70f, 110f, false)
        }
        drawPath(b2, color, style = st)
    }
}

/** Ikon Code (</>) di laci sesuai video. */
@Composable
internal fun CodeGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(21.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val left = Path().apply {
            moveTo(7.8f * s, 7.2f * s)
            lineTo(2.8f * s, 12.0f * s)
            lineTo(7.8f * s, 16.8f * s)
        }
        val right = Path().apply {
            moveTo(16.2f * s, 7.2f * s)
            lineTo(21.2f * s, 12.0f * s)
            lineTo(16.2f * s, 16.8f * s)
        }
        drawPath(left, color, style = st)
        drawPath(right, color, style = st)
        drawLine(color, Offset(13.6f * s, 4.2f * s), Offset(10.4f * s, 19.8f * s), 1.75f * s, StrokeCap.Round)
    }
}

/** Ikon Artefak (dua kartu miring bertumpuk) di laci sesuai video. */
@Composable
internal fun ArtefakGlyph(color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(21.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        rotate(degrees = -6f, pivot = Offset(8.5f * s, 13.5f * s)) {
            drawRoundRect(color, Offset(3.0f * s, 6.8f * s), Size(11.0f * s, 14.0f * s), CornerRadius(2.4f * s), style = st)
        }
        rotate(degrees = 6f, pivot = Offset(15.5f * s, 10.0f * s)) {
            val p = Path().apply {
                moveTo(11.2f * s, 6.0f * s)
                lineTo(11.2f * s, 4.8f * s)
                arcTo(Rect(11.2f * s, 3.0f * s, 14.8f * s, 6.6f * s), 180f, 90f, false)
                lineTo(18.6f * s, 3.0f * s)
                arcTo(Rect(17.4f * s, 3.0f * s, 21.0f * s, 6.6f * s), 270f, 90f, false)
                lineTo(21.0f * s, 14.2f * s)
                arcTo(Rect(17.4f * s, 12.4f * s, 21.0f * s, 16.0f * s), 0f, 90f, false)
                lineTo(14.5f * s, 16.0f * s)
            }
            drawPath(p, color, style = st)
        }
    }
}

/** Ikon gelembung percakapan tunggal untuk setiap baris riwayat "Terbaru" di laci sesuai video. */
@Composable
internal fun BubbleGlyph(color: Color = Color(0xFF3D3C3A)) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width / 24f
        val st = Stroke(1.75f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            arcTo(Rect(3.2f * s, 3.2f * s, 20.8f * s, 19.6f * s), -140f, 305f, true)
            lineTo(4.0f * s, 20.2f * s)
            lineTo(5.6f * s, 15.8f * s)
            close()
        }
        drawPath(p, color, style = st)
    }
}

/**
 * Maskot piksel pada sapaan beranda (tetap dipertahankan sesuai permintaan pengguna).
 */
private val MascotShade = Color(0xFFB55C40)
private val MascotStick = Color(0xFF8A8A8A)

@Composable
fun PixelMascot(modifier: Modifier = Modifier) {
    var stepIndex by remember { mutableIntStateOf(0) }
    var routineTapCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(routineTapCount) {
        if (routineTapCount > 0) {
            val startIdx = when (routineTapCount.mod(3)) {
                1 -> MASCOT_ROUTINE_WALK.size + 1
                2 -> MASCOT_ROUTINE_WALK.size + MASCOT_ROUTINE_STICK.size + 1
                else -> 1
            }
            stepIndex = startIdx.mod(MASCOT_TIMELINE.size)
        }
        while (true) {
            val step = MASCOT_TIMELINE[stepIndex.mod(MASCOT_TIMELINE.size)]
            delay(step.durationMs.toLong())
            stepIndex = (stepIndex + 1).mod(MASCOT_TIMELINE.size)
        }
    }
    val poseId = MASCOT_TIMELINE[stepIndex.mod(MASCOT_TIMELINE.size)].pose
    val rects = MASCOT_POSES[poseId.coerceIn(0, MASCOT_POSES.lastIndex)]
    val interaction = remember { MutableInteractionSource() }
    Canvas(
        modifier.clickable(interactionSource = interaction, indication = null) {
            routineTapCount++
        }
    ) {
        val halfCell = size.width / 24f
        val body = MythosColors.Accent
        val ink = MythosColors.Ink
        for (packed in rects) {
            val color = when (mascotRectColor(packed)) {
                MASCOT_COLOR_SHADE -> MascotShade
                MASCOT_COLOR_EYE -> ink
                MASCOT_COLOR_STICK -> MascotStick
                else -> body
            }
            val gx = mascotRectX(packed)
            val gy = mascotRectY(packed)
            val gw = mascotRectW(packed)
            val gh = mascotRectH(packed)
            drawRect(
                color = color,
                topLeft = Offset(gx * halfCell, gy * halfCell),
                size = Size(gw * halfCell + 0.6f, gh * halfCell + 0.6f)
            )
        }
    }
}

// ---------------------------------------------------------------- render Markdown ringan

private val headingRe = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
private val bulletRe = Regex("^(\\s*)[-*•]\\s+(.*)$")

internal fun markdownToAnnotated(src: String): AnnotatedString = buildAnnotatedString {
    var inFence = false
    var first = true
    for (raw in src.split('\n')) {
        if (raw.trimStart().startsWith("```")) { inFence = !inFence; continue }
        if (!first) append('\n')
        first = false
        if (inFence) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp)) { append(raw) }
            continue
        }
        var line = raw
        var heading = false
        headingRe.find(line)?.let { line = it.groupValues[2]; heading = true }
        if (!heading) bulletRe.find(line)?.let { line = it.groupValues[1] + "•  " + it.groupValues[2] }
        appendInline(line, heading)
    }
}

private fun AnnotatedString.Builder.appendInline(line: String, allBold: Boolean) {
    var bold = allBold
    var code = false
    val sb = StringBuilder()
    fun flush() {
        if (sb.isEmpty()) return
        val st = SpanStyle(
            fontWeight = if (bold) FontWeight.SemiBold else null,
            fontFamily = if (code) FontFamily.Monospace else null,
            fontSize = if (code) 13.5.sp else TextUnit.Unspecified,
            color = if (code) CodeInk else Color.Unspecified,
            background = if (code) CodeChip else Color.Unspecified
        )
        withStyle(st) { append(sb.toString()) }
        sb.clear()
    }
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            !code && c == '*' && i + 1 < line.length && line[i + 1] == '*' -> {
                flush(); bold = if (allBold) true else !bold; i += 2
            }
            !code && c == '*' && i == line.length - 1 -> i++
            c == '`' -> { flush(); code = !code; i++ }
            else -> { sb.append(c); i++ }
        }
    }
    flush()
}

// ---------------------------------------------------------------- animasi "streaming"

internal class Dot(val x: Float, val y: Float, val r: Float)

private const val DOT_X = 5.6f
private const val DOT_Y = 4.0f
private val TAU = (2.0 * PI).toFloat()
internal const val DOT_PATTERNS = 22
private const val DOT_PATTERN_MS = 1500
private const val DOT_BLEND = 0.30f

internal fun dotPattern(pattern: Int, i: Int, u: Float): Dot {
    val k = i / 3f
    return when (pattern) {
        0 -> {
            val a = TAU * (u * 1.25f + k)
            Dot(DOT_X * cos(a), DOT_Y * sin(a), 1.35f + 0.65f * (sin(a) + 1f) / 2f)
        }
        1 -> Dot((i - 1) * DOT_X * 0.95f, DOT_Y * 0.95f * sin(TAU * u * 1.5f - i * 1.25f), 1.65f + 0.3f * sin(TAU * u * 1.5f - i * 1.25f))
        2 -> {
            val a = TAU * (u * 1.1f + k)
            Dot(DOT_X * 0.62f * cos(a), DOT_Y * 0.95f * sin(a), 1.8f)
        }
        3 -> {
            val spread = DOT_X * (0.5f + 0.5f * cos(TAU * u))
            Dot((i - 1) * spread, DOT_Y * 0.18f * sin(TAU * (u * 2f + k)), 1.5f + 0.7f * (1f - spread / DOT_X))
        }
        4 -> {
            val b = abs(sin(PI.toFloat() * (u * 2f - i * 0.28f)))
            Dot((i - 1) * DOT_X * 0.9f, DOT_Y - 2f * DOT_Y * b, 1.55f + 0.45f * b)
        }
        5 -> {
            val a = TAU * (u + k)
            Dot(DOT_X * cos(a), DOT_Y * sin(2f * a), 1.5f + 0.45f * (sin(a) + 1f) / 2f)
        }
        6 -> {
            if (i == 0) Dot(0f, 0f, 2.0f + 0.2f * sin(TAU * u * 2f))
            else {
                val a = TAU * (u * 1.4f + (i - 1) * 0.5f)
                Dot(DOT_X * 0.95f * cos(a), DOT_Y * 0.95f * sin(a), 1.45f + 0.5f * (sin(a) + 1f) / 2f)
            }
        }
        // ---- pola 7..15: formasi tambahan. Semuanya periodik di u dan terdefinisi juga untuk u sedikit negatif (dipakai saat peralihan antar pola).
        // 7: JUGGLING: bergantian tukar tempat; tiap bola melompat satu slot lewat busur atas, bola paling ujung melompat lewat busur bawah.
        7 -> {
            val s = stepProgress(u * 2f)
            val m = (((i + s) % 3f) + 3f) % 3f
            val seg = m - floor(m)
            val half = DOT_X * 0.95f
            if (m < 2f) Dot(-half + m * half, -3.4f * sin(PI.toFloat() * seg), 1.7f)
            else Dot(half - seg * 2f * half, 3.8f * sin(PI.toFloat() * seg), 1.7f)
        }
        // 8: BANDUL NEWTON: bola ujung kiri lalu kanan terayun naik dan menghantam, bola tengah diam.
        8 -> {
            val w = sin(TAU * u)
            val swing = when (i) { 0 -> maxOf(0f, w); 2 -> maxOf(0f, -w); else -> 0f }
            Dot((i - 1) * (3.8f + 1.6f * swing), 2.8f - 4.4f * swing, 1.75f)
        }
        // 9: SEGITIGA KLIK: tiga bola membentuk segitiga yang berputar 120 derajat bertahap; mengembang di tengah tiap putaran.
        9 -> {
            val a = -PI.toFloat() / 2f + TAU * k + (TAU / 3f) * stepProgress(u * 3f)
            val rad = 3.9f + 1.5f * sin(PI.toFloat() * (u * 3f - floor(u * 3f)))
            Dot(rad * cos(a), rad * sin(a), 1.8f)
        }
        // 10: PEMINDAI (Knight Rider): bola yang dilewati sorotan membesar dan terangkat, sorotan bolak-balik.
        10 -> {
            val d = i - (1f + 1.5f * sin(TAU * u))
            val bump = exp(-(d * d) / 0.45f)
            Dot((i - 1) * DOT_X * 0.9f, -2.6f * bump, 1.3f + 1.0f * bump)
        }
        // 11: EKOR KOMET: satu bola besar memimpin di lintasan lonjong, dua bola makin kecil mengekor di belakangnya.
        11 -> {
            val a = TAU * u * 1.1f - i * 0.62f
            Dot(DOT_X * 0.92f * cos(a), DOT_Y * 1.15f * sin(a), 2.2f - 0.5f * i)
        }
        // 12: GELOMBANG BANDUL: tiga bola naik-turun dengan kecepatan berbeda; selaras, menyebar jadi gelombang, lalu selaras lagi.
        12 -> {
            val f = 1f + 0.5f * i
            Dot((i - 1) * DOT_X * 0.9f, 5.0f * sin(TAU * u * f), 1.7f)
        }
        // 13: PUSARAN: tiga bola berputar sambil menyedot ke pusat lalu melebar lagi; makin dekat pusat makin besar.
        13 -> {
            val rad = 1.2f + 4.2f * (0.5f + 0.5f * cos(TAU * u))
            val a = TAU * (u * 2f + k)
            Dot(rad * cos(a), rad * sin(a), 1.45f + 0.8f * (1f - rad / 5.4f))
        }
        // 14: LEDAKAN: menyatu jadi satu bola besar di pusat, meledak ke formasi segitiga sambil berputar pelan, lalu menyatu lagi.
        14 -> {
            val e = 0.5f - 0.5f * cos(TAU * u)
            val a = -PI.toFloat() / 2f + TAU * k + 0.5f * TAU * u
            Dot(5.4f * e * cos(a), 5.4f * e * sin(a), 1.6f + 0.8f * (1f - e))
        }
        // 15: BALING-BALING: batang tiga bola berputar di titik tengah; bola tengah berdenyut.
        15 -> {
            val th = TAU * 0.8f * u
            val len = DOT_X * 0.95f
            Dot((i - 1) * len * cos(th), (i - 1) * len * sin(th), if (i == 1) 2.0f + 0.25f * sin(TAU * u * 3f) else 1.65f)
        }
        // ---- pola 16..21 (v64): semuanya periodik di u (u=0 sama dengan u=1) dan aman untuk u sedikit negatif.
        // 16: DENYUT: tiga bola sejajar berdenyut bergantian dari kiri ke kanan seperti gelombang sonar; bola yang berdenyut juga terangkat sedikit.
        16 -> {
            val pulse = maxOf(0f, sin(TAU * (u * 2f - k)))
            Dot((i - 1) * DOT_X * 0.93f, -1.4f * pulse, 1.4f + 1.0f * pulse)
        }
        // 17: ULAR: tiga bola mengikuti kepala di lintasan gelombang lebar; kepala paling besar, ekor makin kecil.
        17 -> {
            val t = TAU * u - i * 0.55f
            Dot(5.4f * cos(t), 3.2f * sin(3f * t), 2.1f - 0.45f * i)
        }
        // 18: TATA SURYA: satu bola besar diam di pusat, satu mengorbit cepat di dalam, satu mengorbit pelan di luar.
        18 -> when (i) {
            0 -> Dot(0f, 0f, 2.2f)
            1 -> { val a = TAU * u * 2f; Dot(3.4f * cos(a), 3.4f * sin(a), 1.5f) }
            else -> { val a = TAU * (u + 0.25f); Dot(5.4f * cos(a), 5.4f * sin(a), 1.7f) }
        }
        // 19: ELIPS BERPUTAR: tiga bola menyusuri satu elips yang ikut berputar pelan sehingga lintasannya terus berubah arah.
        19 -> {
            val a = TAU * (u + k)
            val phi = TAU * u
            val ex = 5.2f * cos(a)
            val ey = 2.4f * sin(a)
            Dot(ex * cos(phi) - ey * sin(phi), ex * sin(phi) + ey * cos(phi), 1.6f + 0.5f * (sin(a) + 1f) / 2f)
        }
        // 20: OMBAK LOMPAT: bola melompat satu per satu dari kiri ke kanan seperti gelombang suporter (Mexican wave).
        20 -> {
            val t = (((u - k) % 1f) + 1f) % 1f
            val hop = if (t < 0.4f) sin(PI.toFloat() * t / 0.4f) else 0f
            Dot((i - 1) * DOT_X * 0.93f, 2.4f - 4.8f * hop, 1.5f + 0.6f * hop)
        }
        // 21: PINGPONG: satu bola kecil memantul lewat busur di antara dua bola ujung; bola ujung membesar sesaat ketika terkena.
        else -> {
            val sw = sin(TAU * u)
            when (i) {
                1 -> Dot(4.4f * sw, -3.2f * (1f - sw * sw), 1.5f)
                else -> {
                    val q = maxOf(0f, if (i == 2) sw else -sw)
                    val hit = q * q * q * q * q * q
                    Dot((i - 1) * 5.0f, 0f, 1.9f + 0.6f * hit)
                }
            }
        }
    }
}

/** Naik halus tepat 1 tiap satuan [x], diam sebentar di tiap bilangan bulat: dasar gerak "klik" bertahap. Berlaku juga untuk x negatif. */
private fun stepProgress(x: Float): Float { val f = floor(x); return f + smooth(x - f) }

private fun smooth(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

@Composable
internal fun LoaderDots(modifier: Modifier = Modifier) {
    val cycle = DOT_PATTERNS * DOT_PATTERN_MS
    val phase = rememberPhase(cycle)
    Canvas(modifier.size(width = 15.33.dp, height = 20.dp)) {
        val d = density
        val pos = phase.value * cycle / DOT_PATTERN_MS
        val idx = pos.toInt().coerceIn(0, DOT_PATTERNS - 1)
        val u = pos - idx
        val next = (idx + 1) % DOT_PATTERNS
        val b = smooth((u - (1f - DOT_BLEND)) / DOT_BLEND)
        for (i in 0 until 3) {
            val p = dotPattern(idx, i, u)
            val q = if (b > 0f) dotPattern(next, i, u - 1f) else p
            val x = p.x + (q.x - p.x) * b
            val y = p.y + (q.y - p.y) * b
            val r = p.r + (q.r - p.r) * b
            drawCircle(MythosColors.Accent, r * d, Offset(size.width / 2f + x * d, size.height / 2f + y * d))
        }
    }
}

@Composable
internal fun StaticDot(color: Color) {
    Canvas(Modifier.size(width = 15.33.dp, height = 20.dp)) {
        drawCircle(color, 1.7f * density, Offset(size.width / 2f, size.height / 2f))
    }
}

@Composable
internal fun Modifier.lightWave(active: Boolean, base: Color, peak: Color, periodMs: Int = 2000, halfDp: Float = 120f): Modifier {
    if (!active) return this
    val t = rememberPhase(periodMs)
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            if (w <= 0f) return@drawWithContent
            val half = halfDp * density
            val p = t.value * (w + 2f * half) - half
            fun f(v: Float) = (v / w).coerceIn(0f, 1f)
            val brush = Brush.horizontalGradient(
                0f to base, f(p - half) to base, f(p) to peak, f(p + half) to base, 1f to base,
                startX = 0f, endX = w
            )
            drawRect(brush, blendMode = BlendMode.SrcIn)
        }
}

@Composable
internal fun ShimmerText(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Text(
        cleanLine(text),
        style = bodyStyle(if (active) Color.Black else MythosColors.Hint),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.lightWave(active, Color(0xFFCDCDCB), Color(0xFF1B1A18), periodMs = 2000, halfDp = 105f)
    )
}

internal fun typingPerTick(len: Int): Int = maxOf(1, (len + 449) / 450)
internal fun typingGapMs(len: Int): Long = if (len <= 450) 24L else 12L
internal fun typingDurationMs(len: Int): Long = ((len + typingPerTick(len) - 1) / typingPerTick(len)) * typingGapMs(len)

@Composable
internal fun rememberTyped(text: String, animate: Boolean, onTick: () -> Unit = {}): String {
    var n by remember(text) { mutableIntStateOf(if (animate) 0 else text.length) }
    LaunchedEffect(text, animate) {
        if (!animate) {
            n = text.length
            return@LaunchedEffect
        }
        val perTick = typingPerTick(text.length)
        val gap = typingGapMs(text.length)
        while (n < text.length) {
            delay(gap)
            n = minOf(text.length, n + perTick)
            if (n % 10 == 0) onTick()
        }
        onTick()
    }
    return text.substring(0, n.coerceIn(0, text.length))
}

@Composable
internal fun XGlyph(size: Dp = 13.dp, color: Color = MythosColors.Ink) {
    Canvas(Modifier.size(size)) {
        val w = 1.55f * density
        drawLine(color, Offset(0f, 0f), Offset(this.size.width, this.size.height), w, StrokeCap.Round)
        drawLine(color, Offset(this.size.width, 0f), Offset(0f, this.size.height), w, StrokeCap.Round)
    }
}

/** Hantu terisi hitam (layar Chat samaran, 28 dp = 84 px pada video). */
@Composable
internal fun FilledGhost(size: Dp = 28.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val p = Path().apply {
            moveTo(2f * s, 12f * s)
            arcTo(Rect(2f * s, 2f * s, 22f * s, 22f * s), 180f, 180f, false)
            lineTo(22f * s, 21.5f * s)
            quadraticBezierTo(18.7f * s, 17.5f * s, 15.3f * s, 21.5f * s)
            quadraticBezierTo(12.0f * s, 17.5f * s, 8.7f * s, 21.5f * s)
            quadraticBezierTo(5.3f * s, 17.5f * s, 2f * s, 21.5f * s)
            close()
        }
        drawPath(p, Color(0xFF141413))
        drawCircle(Color.White, 1.65f * s, Offset(8.6f * s, 12.2f * s))
        drawCircle(Color.White, 1.65f * s, Offset(15.4f * s, 12.2f * s))
    }
}

// ---------------------------------------------------------------- kartu lampiran

@Composable
private fun rememberThumb(path: String): ImageBitmap? {
    val bmp = produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, b)
                var sample = 1
                while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= 400) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp.value
}

@Composable
fun AttachmentCard(a: Attachment, onRemove: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.size(95.dp).clip(shape).background(MythosColors.Card).border(0.5.dp, Color(0xFFB5B5B2), shape)
    ) {
        if (a.kind == AttachKind.IMAGE) {
            val thumb = rememberThumb(a.path)
            if (thumb != null) Image(thumb, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.padding(start = 8.dp, top = 8.dp).clip(RoundedCornerShape(9.dp)).background(Color.White)
                    .border(0.5.dp, Color(0xFFB5B5B2), RoundedCornerShape(9.dp)).padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    a.name.substringAfterLast('.', "FILE").uppercase().take(5),
                    style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = MythosColors.Ink)
                )
            }
            Text(
                a.name.substringBeforeLast('.', a.name),
                style = TextStyle(fontFamily = MythosSans, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, color = MythosColors.Ink),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 10.dp)
            )
        }
        if (onRemove != null) {
            Box(
                Modifier.align(Alignment.TopEnd).size(40.dp)
                    .semantics { contentDescription = "Hapus lampiran ${a.name}" }
                    .clickable(onClickLabel = "Hapus lampiran", role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                    XGlyph(9.dp, Color.White)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- kolom input (1:1 video)

@Composable
private fun RoundButton(fill: Color, label: String, onClick: () -> Unit, size: Dp = 38.dp, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(fill)
            .semantics { contentDescription = label }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Kartu input 1:1 sesuai video:
 * - Margin kiri/kanan/bawah 16 dp (48 px), sudut 26 dp (78 px), isi #FAF9F7, garis tepi 0,67 dp #DADAD8 (tetap utuh di mode samaran), bayangan halus.
 * - Baris bawah: tombol bulat `+` (#EEECEB), pil model (penyedia/model nyata), tombol mikrofon (#EEECEB), dan tombol kirim oranye (`↑`, #BF5837) yang selalu tampil.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MythosInputCard(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    modelName: String,
    effort: String,
    running: Boolean,
    onPlus: () -> Unit,
    onModel: () -> Unit,
    provider: com.mythos.app.ProviderChoice = com.mythos.app.ProviderChoice.ANTHROPIC,
    onMic: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    dashed: Boolean = false,
    attachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (Attachment) -> Unit = {},
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(26.dp)
    // Pesan terkirim -> keyboard menutup sendiri dan kolom input kehilangan fokus. Hanya bila pesan benar-benar dikirim
    // (kolom kosong tanpa lampiran tidak dikirim oleh pemanggil, jadi keyboard dibiarkan terbuka).
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val sendAndClose: () -> Unit = {
        val willSend = text.isNotBlank() || attachments.isNotEmpty()
        onSend()
        if (willSend) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    if (compact) {
        // Pil satu baris seperti rekaman acuan: margin 16 dp, isi #FDFDFD, garis 0,67 dp #DCDCDC, tombol bulat 36 dp (#EFEDEB) berjarak 8 dp dari tepi.
        // Tumbuh ke atas bila teks banyak baris; tombol tetap menempel di bawah.
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .fillMaxWidth()
                .shadow(5.dp, shape, ambientColor = Color(0x12000000), spotColor = Color(0x18000000))
                .background(MythosColors.Composer, shape)
                .border(0.67.dp, MythosColors.ComposerBorder, shape)
        ) {
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) { attachments.forEach { AttachmentCard(it) { onRemoveAttachment(it) } } }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                RoundButton(MythosColors.Bubble, "Tambah lampiran", onPlus, 36.dp) { PlusGlyph() }
                Box(Modifier.weight(1f).heightIn(min = 36.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text(placeholder, style = inputStyle(MythosColors.Placeholder), maxLines = 1)
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        textStyle = inputStyle(MythosColors.Ink),
                        cursorBrush = SolidColor(MythosColors.Ink),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Satu-satunya tambahan di luar rekaman acuan: logo penyedia untuk berganti model (hapus blok ini bila tidak diperlukan).
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .clickable(onClickLabel = "Pemilihan model", role = Role.Button, onClick = onModel),
                    contentAlignment = Alignment.Center
                ) {
                    ProviderLogo(
                        provider, 22.dp,
                        Modifier.semantics { contentDescription = "Model: " + modelName + if (effort.isNotBlank()) " " + effort else "" }
                    )
                }
                RoundButton(MythosColors.Bubble, "Input suara", onMic, 36.dp) { MicGlyph() }
                Spacer(Modifier.width(12.dp))
                if (running) {
                    RoundButton(MythosColors.Bubble, "Hentikan tugas", onStop, 36.dp) { StopGlyph() }
                } else {
                    RoundButton(MythosColors.Send, "Kirim", sendAndClose, 36.dp) { ArrowUpGlyph() }
                }
            }
        }
        return
    }
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = Color(0x16000000), spotColor = Color(0x20000000))
            .background(MythosColors.Card, shape)
            .border(0.67.dp, Color(0xFFE2E2DF), shape)
            .embossedRing(corner = 26.dp, insetShadow = true)
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) { attachments.forEach { AttachmentCard(it) { onRemoveAttachment(it) } } }
        }
        Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 15.dp, bottom = 14.dp)) {
            if (text.isEmpty()) Text(placeholder, style = inputStyle(Color(0xFF2C2B29)), maxLines = 1)
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = inputStyle(MythosColors.Ink),
                cursorBrush = SolidColor(MythosColors.Ink),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundButton(MythosColors.Chip, "Tambah lampiran", onPlus) { PlusGlyph() }
            Spacer(Modifier.width(8.dp))
            // Area pil mengambil sisa lebar; pil menyusut + elipsis, sehingga tombol mikrofon dan kirim selalu terlihat penuh.
            Box(Modifier.weight(1f).padding(end = 8.dp), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .clickable(onClickLabel = "Pemilihan model", role = Role.Button, onClick = onModel)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Hanya logo penyedia; nama model/penyedia tetap dibacakan pembaca layar.
                    ProviderLogo(
                        provider, 24.dp,
                        Modifier.semantics { contentDescription = "Model: " + modelName + if (effort.isNotBlank()) " " + effort else "" }
                    )
                }
            }
            RoundButton(MythosColors.Chip, "Input suara", onMic) { MicGlyph() }
            Spacer(Modifier.width(8.dp))
            if (running) {
                RoundButton(MythosColors.Chip, "Hentikan tugas", onStop) { StopGlyph() }
            } else {
                RoundButton(MythosColors.Send, "Kirim", sendAndClose) { ArrowUpGlyph() }
            }
        }
    }
}

/** Ikon jam untuk kelompok langkah yang sudah selesai. */
@Composable
internal fun ClockGlyph(color: Color = Color(0xFF8E8C87)) {
    Canvas(Modifier.size(20.dp)) {
        val d = density
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color, 7.2f * d, c, style = Stroke(1.2f * d, cap = StrokeCap.Round))
        drawLine(color, c, Offset(c.x, c.y - 4f * d), 1.2f * d, StrokeCap.Round)
        drawLine(color, c, Offset(c.x + 3f * d, c.y + 1.2f * d), 1.2f * d, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- streaming: jam bersama, teks mengalir, ikon

private val CodeInk = Color(0xFF184F93)
private val CodeChip = Color(0xFFECECE9)

@Composable
internal fun rememberPhase(periodMs: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        while (true) withFrameMillis { phase.floatValue = (it % periodMs).toFloat() / periodMs }
    }
    return phase
}

private const val TAIL_FADE_CHARS = 9

internal fun withTailFade(src: AnnotatedString, ink: Color): AnnotatedString {
    val n = src.length
    if (n == 0) return src
    val from = maxOf(0, n - TAIL_FADE_CHARS)
    val count = n - from
    return buildAnnotatedString {
        append(src)
        for (k in 0 until count) {
            val a = 1f - (k + 1f) / (count + 1f)
            addStyle(SpanStyle(color = ink.copy(alpha = a)), from + k, from + k + 1)
        }
    }
}

@Composable
internal fun rememberRevealed(text: String, animate: Boolean, onTick: () -> Unit = {}): Int {
    var n by remember { mutableIntStateOf(if (animate) 0 else text.length) }
    if (n > text.length) n = text.length
    LaunchedEffect(text, animate) {
        if (!animate) {
            n = text.length
            return@LaunchedEffect
        }
        var frame = 0
        var acc = 0f
        var last = withFrameNanos { it }
        while (n < text.length) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
            last = now
            acc += revealSpeed(text.length - n) * dt
            val step = acc.toInt()
            if (step > 0) {
                acc -= step
                n = minOf(text.length, n + step)
                frame++
                if (frame % 3 == 0) onTick()
            }
        }
        if (frame > 0) onTick()
    }
    return n.coerceIn(0, text.length)
}

internal fun assistantStyle(color: Color = MythosColors.Ink) =
    bodyStyle(color).copy(fontFamily = MythosSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)

@Composable
internal fun TerminalGlyph(color: Color = Color(0xFF8E8C87)) {
    Canvas(Modifier.size(20.dp)) {
        val d = density
        val s = 14.4f * d
        val left = (size.width - s) / 2f
        val top = (size.height - s) / 2f
        drawRoundRect(color, Offset(left, top), Size(s, s), CornerRadius(3.2f * d), style = Stroke(1.2f * d))
        val chevron = Path().apply {
            moveTo(left + 4.0f * d, top + 4.6f * d)
            lineTo(left + 7.0f * d, top + 7.2f * d)
            lineTo(left + 4.0f * d, top + 9.8f * d)
        }
        drawPath(chevron, color, style = Stroke(1.2f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(left + 8.4f * d, top + 10.0f * d), Offset(left + 10.8f * d, top + 10.0f * d), 1.2f * d, StrokeCap.Round)
    }
}

@Composable
internal fun ArrowDownGlyph(color: Color = MythosColors.Icon) {
    Canvas(Modifier.size(width = 14.dp, height = 14.dp)) {
        val d = density
        val cx = size.width / 2f
        drawLine(color, Offset(cx, 1.5f * d), Offset(cx, size.height - 1.5f * d), 1.4f * d, StrokeCap.Round)
        val head = Path().apply {
            moveTo(cx - 4.2f * d, size.height - 5.7f * d)
            lineTo(cx, size.height - 1.5f * d)
            lineTo(cx + 4.2f * d, size.height - 5.7f * d)
        }
        drawPath(head, color, style = Stroke(1.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
