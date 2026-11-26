package io.github.zolarly.ukiyo.ui

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.zolarly.ukiyo.ui.theme.Abyss
import io.github.zolarly.ukiyo.ui.theme.Blossom
import io.github.zolarly.ukiyo.ui.theme.Dusk
import io.github.zolarly.ukiyo.ui.theme.Gold
import io.github.zolarly.ukiyo.ui.theme.Lavender
import io.github.zolarly.ukiyo.ui.theme.Moon
import io.github.zolarly.ukiyo.ui.theme.Night
import io.github.zolarly.ukiyo.ui.theme.Tide
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

private const val TAU = (2 * PI).toFloat()

/** 黑洞事件视界的半径。接收页的「粘贴链接」按钮按这个尺寸叠在黑洞上。 */
val HorizonRadius = 64.dp

/**
 * 动画时钟，单位秒。只在绘制阶段读它，每帧只重画不重组。
 * 系统设置里关掉了动画时停在 0，画面保持静止。
 */
@Composable
fun rememberClock(): State<Float> {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    return produceState(0f) {
        if (still) return@produceState
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }
}

private fun hash(i: Int, k: Int): Float {
    val v = sin(i * 12.9898f + k * 78.233f) * 43758.547f
    return v - floor(v)
}

private fun wrap(v: Float, m: Float) = ((v % m) + m) % m

// 三幅画面之间按页码插值：0 是发送，1 是接收，2 是我的。
private fun mix(a: Float, b: Float, c: Float, p: Float) = if (p <= 1f) a + (b - a) * p else b + (c - b) * (p - 1)
private fun mix(a: Offset, b: Offset, c: Offset, p: Float) = if (p <= 1f) a + (b - a) * p else b + (c - b) * (p - 1)
private fun mix(a: Color, b: Color, c: Color, p: Float) = if (p <= 1f) lerp(a, b, p) else lerp(b, c, p - 1)

private const val DUST = 130
private val trailColors = listOf(Moon, Moon, Tide, Lavender, Blossom, Gold)

/** 斜射进来的一束光。[light] 算某一点被照亮的程度，0–1。 */
private class Beam(val origin: Offset, val dir: Offset, val length: Float, val near: Float, val far: Float, val power: Float) {
    fun light(p: Offset): Float {
        val d = p - origin
        val along = d.x * dir.x + d.y * dir.y
        if (along <= 0f || along >= length) return 0f
        val half = near + (far - near) * along / length
        val q = abs(d.x * dir.y - d.y * dir.x) / half
        return exp(-2.5f * q * q) * (1 - along / length) * power
    }
}

/**
 * 整个 App 的背景：一片宇宙，三个页面各是一幅画面。
 * 发送：星尘不规则地漂，一束光斜照进来，尘埃穿过光束时被照亮（丁达尔效应）。
 * 接收：一个黑洞，星尘绕着它旋转、落进去。
 * 我的：绕右上角极星转动的星轨。
 * 三幅画面用的是同一批星尘，翻页时它们会被吸进黑洞、再拉成星轨。[position] 是带小数的页码。
 */
@Composable
fun Sky(position: () -> Float, modifier: Modifier = Modifier) {
    val clock = rememberClock()
    val status = WindowInsets.statusBars
    val nav = WindowInsets.navigationBars
    val space = remember { spaceShader() }
    Canvas(modifier.fillMaxSize()) {
        val t = clock.value
        val p = position().coerceIn(0f, 2f)
        val send = (1 - p).coerceIn(0f, 1f)
        val receive = 1 - abs(p - 1).coerceAtMost(1f)
        val mine = (p - 1).coerceIn(0f, 1f)
        val w = size.width
        val h = size.height
        // 和页面内容区的中心对齐：上面让开状态栏，下面让开导航栏和底栏
        val top = status.getTop(this).toFloat()
        val focus = Offset(w / 2, (top + h - nav.getBottom(this) - 100.dp.toPx()) / 2)
        val pole = Offset(w * 0.86f, top + 52.dp.toPx())

        val source = Offset(w * 0.08f, -h * 0.08f)
        val angle = atan2(focus.y - source.y, focus.x - source.x) + 0.035f * sin(t * 0.21f)
        val beam = Beam(source, Offset(cos(angle), sin(angle)), hypot(w, h) * 1.2f, 12.dp.toPx(), 150.dp.toPx(), 0.85f + 0.15f * sin(t * 0.37f))
        val disk = w * 0.62f
        val horizon = HorizonRadius.toPx()
        val tilt = -0.2f
        val squash = 0.24f
        val reach = hypot(pole.x, h - pole.y)

        val shaded = space != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (shaded) {
            drawSpace(space!!, t, p, send, receive, mine, focus, horizon, tilt, squash, source, beam.dir, beam.near, beam.far, beam.power)
        } else {
            drawRect(Brush.verticalGradient(listOf(Abyss, Night, Dusk)))
            nebula(Offset(w * 0.1f, 0f), w * 1.2f, Gold, 0.16f * send)
            nebula(Offset(w * 0.8f, h * 0.55f), w * 0.9f, Blossom, 0.08f * send)
            nebula(focus, w * 0.95f, Lavender, 0.24f * receive)
            farStars(t, p)
            if (send > 0f) drawBeam(beam, send)
        }
        nebula(pole, w * 1.3f, Tide, 0.13f * mine)
        nebula(Offset(w * 0.15f, h * 0.85f), w * 0.9f, Lavender, 0.12f * mine)

        // 星尘分两遍画：盘的后半圈在黑洞后面，前半圈挡在黑洞前面
        fun dust(front: Boolean) {
            for (i in 0 until DUST) {
                // 发送：几条不同频率的正弦叠在一起，走成不规则的路线
                val drift = Offset(
                    wrap(hash(i, 1) * w + w * (0.05f * sin(t * (0.09f + 0.08f * hash(i, 3)) + i) + 0.025f * sin(t * (0.31f + 0.2f * hash(i, 4)) + i * 1.7f)) + t * w * 0.006f * (hash(i, 5) - 0.5f), w),
                    wrap(hash(i, 2) * h + h * (0.03f * cos(t * (0.07f + 0.06f * hash(i, 6)) + i * 0.7f) + 0.015f * sin(t * (0.43f + 0.2f * hash(i, 4)) + i)) - t * h * 0.004f * hash(i, 7), h),
                )
                // 接收：沿螺旋往里落，越靠近转得越快，落到底再从外圈出现
                val u = (t * (0.025f + 0.035f * hash(i, 8)) + hash(i, 9)) % 1f
                val r = horizon + (disk - horizon) * (1 - u).pow(1.4f)
                val th = hash(i, 10) * TAU + t * 0.08f + 7f * u * u
                val ox = r * cos(th)
                val oy = r * sin(th) * squash
                val fall = focus + Offset(ox * cos(tilt) - oy * sin(tilt), ox * sin(tilt) + oy * cos(tilt))
                if ((sin(th) >= 0f) != front) continue
                // 我的：整片星空绕极星转，转出扇形范围的从另一头补进来
                val f = (hash(i, 11) + t * 0.004f) % 1f
                val star = 1.22f + f * 2.27f
                val ring = reach * (0.06f + 0.94f * hash(i, 12))
                val trail = Offset(pole.x + ring * cos(star), pole.y + ring * sin(star))

                val twinkle = (sin(t * (0.8f + hash(i, 13)) + i) + 1) / 2
                val lit = if (send > 0f) beam.light(drift) else 0f
                val edge = (minOf(f, 1 - f) * 10).coerceAtMost(1f)
                val swallow = (u * 12).coerceAtMost(1f) * ((1 - u) * 6).coerceAtMost(1f)
                val heat = u * u
                val pos = mix(drift, fall, trail, p)
                // 着色器画的黑洞是不透明的，后半圈落在视界里的星尘应该被挡住
                if (shaded && !front && receive > 0.3f && (pos - focus).getDistance() < horizon * 1.05f) continue
                val tint = trailColors[(hash(i, 14) * trailColors.size).toInt()]
                val color = mix(lerp(Moon, Gold, lit), if (shaded) lerp(Gold, Moon, u) else lerp(Lavender, lerp(Tide, Gold, heat), u), tint, p)
                val alpha = mix(0.16f + 0.24f * twinkle + 0.8f * lit, swallow * (0.35f + 0.3f * cos(th) + 0.4f * heat), edge * (0.4f + 0.5f * twinkle), p)
                val glow = mix(lit, swallow * heat * 0.8f, if (hash(i, 15) > 0.85f) edge * 0.5f else 0f, p)
                val dot = (0.7f + 1.6f * hash(i, 16)).dp.toPx()

                if (mine > 0f && i % 4 != 0) {
                    // 星轨：头亮尾暗，分几段画出渐隐
                    val length = 0.25f + 0.5f * hash(i, 17)
                    val corner = Offset(pole.x - ring, pole.y - ring)
                    // 离极星越远越淡，压在卡片下面的那些不抢文字
                    val near = 1 - 0.75f * ring / reach
                    for (s in 0 until 6) {
                        val from = star - length + s * length / 6
                        drawArc(
                            tint.copy(alpha = (mine * edge * near * 0.55f * (s + 1) / 6).coerceAtMost(1f)),
                            from * 180 / PI.toFloat(),
                            length / 6 * 180 / PI.toFloat(),
                            useCenter = false,
                            topLeft = corner,
                            size = Size(ring * 2, ring * 2),
                            style = Stroke(dot * 0.8f),
                        )
                    }
                }
                if (glow > 0.04f) {
                    val halo = dot * 7
                    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = (glow * 0.6f).coerceAtMost(1f)), Color.Transparent), pos, halo), halo, pos)
                }
                // 离镜头很近的几粒是失焦的光斑
                if (send > 0f && hash(i, 18) > 0.9f) {
                    val bokeh = dot * (8 + 10 * hash(i, 19))
                    val tone = color.copy(alpha = send * (0.04f + 0.2f * lit))
                    drawCircle(Brush.radialGradient(0f to tone, 0.7f to tone.copy(alpha = tone.alpha * 0.6f), 1f to Color.Transparent, center = pos, radius = bokeh), bokeh, pos)
                }
                drawCircle(color.copy(alpha = alpha.coerceIn(0f, 1f)), dot, pos)
            }
        }

        if (receive > 0f && !shaded) diskRings(focus, horizon, tilt, receive, back = true)
        dust(front = false)
        if (receive > 0f && !shaded) blackHole(focus, horizon, t, receive)
        dust(front = true)
        if (receive > 0f && !shaded) diskRings(focus, horizon, tilt, receive, back = false)
        if (receive > 0f) {
            // 每隔一阵有一颗星离黑洞太近，被潮汐力撕成一条细流，绕着盘落进去
            val cycle = t / 13f
            val id = floor(cycle).toInt()
            val life = cycle - id
            val start = hash(id, 30) * TAU
            val fade = sin(life * PI.toFloat())
            var prev: Offset? = null
            for (k in 0 until 64) {
                val u = life * 1.15f - k * (0.0015f + 0.009f * life)
                if (u !in 0f..1f) continue
                val r = horizon + (disk - horizon) * (1 - u).pow(1.4f)
                val th = start + 7f * u * u
                val ox = r * cos(th)
                val oy = r * sin(th) * squash
                val at = focus + Offset(ox * cos(tilt) - oy * sin(tilt), ox * sin(tilt) + oy * cos(tilt))
                if (sin(th) < 0f && (at - focus).getDistance() < horizon * 1.05f) {
                    prev = null
                    continue
                }
                val a = receive * fade * (1 - k / 64f)
                prev?.let { drawLine(lerp(Gold, Moon, 1 - k / 64f).copy(alpha = a), it, at, (2f - k * 0.025f).dp.toPx(), StrokeCap.Round) }
                prev = at
                if (k == 0) drawCircle(Brush.radialGradient(listOf(Moon.copy(alpha = a * 0.7f), Color.Transparent), at, 14.dp.toPx()), 14.dp.toPx(), at)
            }
        }
        if (mine > 0f) poleStar(pole, t, mine)
    }
}

private fun DrawScope.nebula(center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0f) return
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, radius), radius, center)
}

/** 远处的星，不随画面变，只随翻页轻微视差。 */
private fun DrawScope.farStars(t: Float, p: Float) {
    repeat(90) { i ->
        val x = wrap(hash(i, 20) * size.width - p * size.width * 0.05f * hash(i, 21), size.width)
        val a = 0.12f + 0.4f * (sin(t * (0.4f + hash(i, 22)) + i * 2) + 1) / 2
        drawCircle(Moon.copy(alpha = a), (0.5f + 0.8f * hash(i, 23)).dp.toPx(), Offset(x, hash(i, 24) * size.height))
    }
}

/** 光束切成一段段画，每段横向中间亮两边淡，越往远处越暗，看不出边缘。 */
private fun DrawScope.drawBeam(beam: Beam, alpha: Float) {
    val n = Offset(-beam.dir.y, beam.dir.x)
    val segments = 48
    for (s in 0 until segments) {
        val a = s.toFloat() / segments
        val b = (s + 1f) / segments
        val p0 = beam.origin + beam.dir * (beam.length * a)
        val p1 = beam.origin + beam.dir * (beam.length * b)
        val w0 = beam.near + (beam.far - beam.near) * a
        val w1 = beam.near + (beam.far - beam.near) * b
        val quad = Path().apply {
            moveTo(p0.x + n.x * w0, p0.y + n.y * w0)
            lineTo(p1.x + n.x * w1, p1.y + n.y * w1)
            lineTo(p1.x - n.x * w1, p1.y - n.y * w1)
            lineTo(p0.x - n.x * w0, p0.y - n.y * w0)
            close()
        }
        val mid = (p0 + p1) / 2f
        val half = (w0 + w1) / 2
        val peak = Gold.copy(alpha = 0.3f * alpha * beam.power * (1 - (a + b) / 2).pow(1.1f))
        drawPath(
            quad,
            Brush.linearGradient(
                0f to Color.Transparent,
                0.18f to peak.copy(alpha = peak.alpha * 0.22f),
                0.36f to peak.copy(alpha = peak.alpha * 0.75f),
                0.5f to peak,
                0.64f to peak.copy(alpha = peak.alpha * 0.75f),
                0.82f to peak.copy(alpha = peak.alpha * 0.22f),
                1f to Color.Transparent,
                start = mid - n * half,
                end = mid + n * half,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

/** 吸积盘的几圈细环，倾斜成椭圆。[back] 为 true 画上半圈（在黑洞后面），否则画下半圈。 */
private fun DrawScope.diskRings(c: Offset, horizon: Float, tilt: Float, alpha: Float, back: Boolean) {
    rotate(tilt * 180 / PI.toFloat(), c) {
        for ((k, color) in listOf(1.35f to Gold, 1.8f to Blossom, 2.5f to Lavender, 3.4f to Tide)) {
            val rx = horizon * k
            val ry = rx * 0.36f
            drawArc(
                color.copy(alpha = alpha * 0.28f / k),
                if (back) 180f else 0f,
                180f,
                useCenter = false,
                topLeft = Offset(c.x - rx, c.y - ry),
                size = Size(rx * 2, ry * 2),
                style = Stroke(1.2.dp.toPx()),
            )
        }
    }
}

/** 黑洞：一圈被引力弯折的光晕，中间是纯黑的视界，边上一道转动的光子环。 */
private fun DrawScope.blackHole(c: Offset, horizon: Float, t: Float, alpha: Float) {
    val glow = horizon * 2.3f
    drawCircle(
        Brush.radialGradient(
            0.3f to Lavender.copy(alpha = 0.4f * alpha),
            0.5f to Blossom.copy(alpha = 0.14f * alpha),
            1f to Color.Transparent,
            center = c,
            radius = glow,
        ),
        glow,
        c,
    )
    drawCircle(Abyss.copy(alpha = alpha), horizon, c)
    rotate(t * 24f, c) {
        drawCircle(
            Brush.sweepGradient(listOf(Gold, Blossom, Tide, Lavender, Gold), c),
            horizon * 1.04f,
            c,
            alpha = alpha * 0.9f,
            style = Stroke(2.dp.toPx()),
        )
    }
}

/** 极星：一点亮光加一个细长的十字星芒，慢慢呼吸。 */
private fun DrawScope.poleStar(c: Offset, t: Float, alpha: Float) {
    val breathe = 1f + 0.12f * sin(t * 1.3f)
    val glow = 26.dp.toPx() * breathe
    drawCircle(Brush.radialGradient(listOf(Moon.copy(alpha = 0.7f * alpha), Tide.copy(alpha = 0.15f * alpha), Color.Transparent), c, glow), glow, c)
    val ray = 34.dp.toPx() * breathe
    for (d in listOf(Offset(1f, 0f), Offset(0f, 1f))) {
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, Moon.copy(alpha = alpha), Color.Transparent), c - d * ray, c + d * ray),
            c - d * ray,
            c + d * ray,
            1.dp.toPx(),
        )
    }
    drawCircle(Moon.copy(alpha = alpha), 2.5.dp.toPx(), c)
}

/**
 * 会呼吸、会变色的月亮。[tide] 0–1 是月亮里潮水的高度，用来表示进度。
 * 形状由几条正弦波叠出来；亮面朝着左上方照进来的光，明暗交界线缓缓摆动。
 */
@Composable
fun MoonOrb(
    tide: Float,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val clock = rememberClock()
    val planet = remember { planetShader() }
    val level by animateFloatAsState(tide, tween(700), label = "tide")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = onClick != null,
                onClickLabel = onClickLabel,
                role = Role.Button,
            ) { onClick?.invoke() }
            .drawBehind { moon(clock.value, level, planet) },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private val Shade = Color(0xFF5B4BB4)

private fun DrawScope.moon(t: Float, level: Float, planet: RuntimeShader?) {
    val c = center
    val r = size.minDimension / 2 * 0.8f
    val body = Path()
    if (planet != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        drawPlanet(planet, t, r)
        body.addOval(Rect(c, r))
    } else {
        flatMoon(t, r, body)
    }

    if (level > 0f) clipPath(body) {
        val surface = c.y + r * 1.1f - level * r * 2.2f
        for ((phase, alpha) in listOf(0f to 0.55f, 2.2f to 0.9f)) {
            val wave = Path()
            wave.moveTo(c.x - r * 1.2f, c.y + r * 1.2f)
            var x = c.x - r * 1.2f
            while (x <= c.x + r * 1.2f) {
                wave.lineTo(x, surface + 5.dp.toPx() * sin(x / r * 3 + t * 2 + phase))
                x += 4.dp.toPx()
            }
            wave.lineTo(c.x + r * 1.2f, c.y + r * 1.2f)
            wave.close()
            drawPath(wave, Brush.verticalGradient(listOf(Tide.copy(alpha = alpha), Tide), surface, c.y + r))
        }
    }
}

/** Android 13 以前没有着色器，用渐变画一轮形状微微起伏的月亮。轮廓写进 [body]，潮水按它裁剪。 */
private fun DrawScope.flatMoon(t: Float, r: Float, body: Path) {
    val c = center
    val halo = r * (1.3f + 0.05f * sin(t * 1.1f))
    drawCircle(Brush.radialGradient(listOf(Gold.copy(alpha = 0.3f), Blossom.copy(alpha = 0.14f), Color.Transparent), c, halo), halo, c)
    val n = 96
    for (i in 0..n) {
        val th = i * TAU / n
        val k = 1f + 0.016f * sin(3 * th + t * 0.9f) + 0.01f * sin(5 * th - t * 1.3f) + 0.012f * sin(2 * th + t * 0.5f)
        val x = c.x + r * k * cos(th)
        val y = c.y + r * k * sin(th)
        if (i == 0) body.moveTo(x, y) else body.lineTo(x, y)
    }
    body.close()
    val g = PI.toFloat() / 3 + 0.45f * sin(t * 0.17f)
    val dir = Offset(cos(g), sin(g)) * r
    drawPath(body, Brush.linearGradient(listOf(Moon, Blossom, Lavender, Shade), c - dir, c + dir))
}
