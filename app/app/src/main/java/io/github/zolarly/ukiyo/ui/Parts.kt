package io.github.zolarly.ukiyo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zolarly.ukiyo.formatRemaining
import io.github.zolarly.ukiyo.ui.theme.Abyss
import io.github.zolarly.ukiyo.ui.theme.Blossom
import io.github.zolarly.ukiyo.ui.theme.Dim
import io.github.zolarly.ukiyo.ui.theme.Gold
import io.github.zolarly.ukiyo.ui.theme.Lavender
import io.github.zolarly.ukiyo.ui.theme.Moon
import io.github.zolarly.ukiyo.ui.theme.Tide
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.delay

@Composable
fun PageHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = Moon)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Dim)
    }
}

/** 主操作按钮：一条发光的渐变胶囊。 */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: List<Color> = listOf(Blossom, Lavender),
) {
    val fill = if (enabled) Brush.horizontalGradient(colors) else SolidColor(Moon.copy(alpha = 0.1f))
    Box(
        modifier
            .height(56.dp)
            .shadow(if (enabled) 18.dp else 0.dp, CircleShape, ambientColor = colors.first(), spotColor = colors.last())
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Abyss else Dim)
    }
}

/** 次要按钮：细描边胶囊。 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Moon,
) {
    val c = if (enabled) color else Dim.copy(alpha = 0.4f)
    Box(
        modifier
            .height(56.dp)
            .clip(CircleShape)
            .border(1.dp, c.copy(alpha = 0.35f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c)
    }
}

/** 文字链接式的小操作。 */
@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = Tide) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** 每秒刷新一次的当前时间。 */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

private const val DAY = 24 * 60 * 60 * 1000f

/** 剩余时间画成一支线香：烧掉的部分消失，香头一闪一闪。 */
@Composable
fun Incense(expiresAt: Long, modifier: Modifier = Modifier) {
    val left = expiresAt - rememberNow()
    val clock = rememberClock()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.width(88.dp).height(12.dp)) {
            val y = size.height / 2
            val end = size.width * (left / DAY).coerceIn(0f, 1f)
            drawLine(Moon.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
            if (end > 0f) {
                drawLine(Gold, Offset(0f, y), Offset(end, y), 2.dp.toPx(), StrokeCap.Round)
                val glow = 5.dp.toPx() * (1f + 0.25f * sin(clock.value * 3))
                val tip = Offset(end, y)
                drawCircle(Brush.radialGradient(listOf(Gold, Gold.copy(alpha = 0f)), tip, glow), glow, tip)
            }
        }
        Text(formatRemaining(left), style = MaterialTheme.typography.bodyMedium, color = Gold)
    }
}

// 残影从远到近：金、浅葱、鴇色
private val ghosts = listOf(Gold, Tide, Blossom)

/**
 * 翻页时的拖尾残影：把页面内容再画三遍，每遍染成一种颜色、往运动的反方向错开。
 * 翻到一半时最明显，停稳后完全消失，静止时没有额外开销。
 */
fun Modifier.afterimage(pager: PagerState, page: Int): Modifier = drawWithContent {
    val o = abs(pager.getOffsetDistanceInPages(page)).coerceAtMost(1f)
    val strength = sin(PI.toFloat() * o)
    if (strength > 0.01f) {
        val dir = if (pager.lastScrolledForward) 1f else -1f
        val bounds = Rect(-size.width, 0f, size.width * 2, size.height)
        ghosts.forEachIndexed { i, color ->
            val k = ghosts.size - i
            val paint = Paint().apply {
                alpha = strength * 0.16f * (i + 1)
                colorFilter = ColorFilter.tint(color)
            }
            drawIntoCanvas { canvas ->
                canvas.saveLayer(bounds, paint)
                translate(left = dir * k * 22.dp.toPx() * strength) { this@drawWithContent.drawContent() }
                canvas.restore()
            }
        }
    }
    drawContent()
}

/** 离开中心的页面缩小、变淡，再叠上残影。 */
fun Modifier.pageMotion(pager: PagerState, page: Int): Modifier = graphicsLayer {
    val o = abs(pager.getOffsetDistanceInPages(page)).coerceAtMost(1f)
    val s = 1f - 0.08f * o
    scaleX = s
    scaleY = s
    alpha = 1f - 0.55f * o
}.afterimage(pager, page)

private val seals = listOf("送" to "发送", "收" to "接收", "我" to "我的")

/**
 * 底部导航：三枚印章字，鴇色的光斑跟着手指在它们之间流动，
 * 两页中间时被拉长成水滴形。
 */
@Composable
fun SealBar(pager: PagerState, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val position = pager.currentPage + pager.currentPageOffsetFraction
    val clock = rememberClock()
    Row(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(CircleShape)
            .background(Abyss.copy(alpha = 0.78f))
            .border(1.dp, Moon.copy(alpha = 0.12f), CircleShape)
            .drawBehind {
                val slot = size.width / seals.size
                val frac = position - floor(position)
                val stretch = sin(PI.toFloat() * frac)
                val w = 64.dp.toPx() * (1 + 0.8f * stretch)
                val h = 52.dp.toPx() * (1 - 0.12f * stretch)
                val cx = (position + 0.5f) * slot
                val breathe = 1f + 0.06f * sin(clock.value * 1.6f)
                drawCircle(
                    Brush.radialGradient(listOf(Blossom.copy(alpha = 0.45f), Color.Transparent), Offset(cx, center.y), w * breathe),
                    w * breathe,
                    Offset(cx, center.y),
                )
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Blossom, Lavender), cx - w / 2, cx + w / 2),
                    topLeft = Offset(cx - w / 2, center.y - h / 2),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(h / 2),
                )
            },
    ) {
        seals.forEachIndexed { i, (seal, label) ->
            val near = (1f - abs(position - i)).coerceIn(0f, 1f)
            val color = lerp(Dim, Abyss, near)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = pager.targetPage == i,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                    ) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(seal, fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 24.sp, color = color)
                Text(label, fontSize = 10.sp, lineHeight = 12.sp, color = color)
            }
        }
    }
}

/** 一排单选的胶囊。 */
@Composable
fun <T> Choices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(if (on) Blossom.copy(alpha = 0.18f) else Color.Transparent)
                    .border(1.dp, if (on) Blossom else Moon.copy(alpha = 0.18f), CircleShape)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Blossom else if (enabled) Dim else Dim.copy(alpha = 0.4f))
            }
        }
    }
}

private val panelShape = RoundedCornerShape(24.dp)

/** 半透明的面板，用在列表项和结果区。 */
fun Modifier.panel(): Modifier = this
    .clip(panelShape)
    .background(Abyss.copy(alpha = 0.55f))
    .background(Moon.copy(alpha = 0.05f))
    .border(1.dp, Moon.copy(alpha = 0.1f), panelShape)
    .padding(20.dp)
