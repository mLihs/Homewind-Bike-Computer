package io.homewind.karoo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.models.ViewConfig
import io.homewind.karoo.HomewindRuntime
import io.homewind.karoo.core.Badge
import io.homewind.karoo.core.FanDot
import io.homewind.karoo.core.HomewindState
import io.homewind.karoo.core.Link
import io.homewind.karoo.fields.ModeAction

/**
 * Karoo-style card: header with a small icon on the left and the title in capitals on
 * the right, content (usually the value, right-aligned) at the bottom.
 */
@Composable
internal fun Card(
    icon: Int,
    iconColor: Color,
    title: String,
    background: Color = BACKGROUND,
    titleColor: Color = Color.White,
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Rounded(background, modifier.fillMaxSize()) {
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, iconColor)
                Spacer(GlanceModifier.defaultWeight())
                Text(title.uppercase(), style = bold(15, TextAlign.End).copy(color = ColorProvider(titleColor)), maxLines = 1)
            }
            Spacer(GlanceModifier.defaultWeight())
            content()
        }
    }
}

@Composable
internal fun Icon(icon: Int, color: Color) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = null,
        modifier = GlanceModifier.size(18.dp),
        colorFilter = ColorFilter.tint(ColorProvider(color)),
    )
}

/** The big value, right-aligned like the Karoo's own fields. */
@Composable
internal fun Value(text: String, color: Color, config: ViewConfig, max: Int = 40) {
    Text(
        text,
        style = TextStyle(color = ColorProvider(color), fontSize = minOf(config.textSize, max).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
        maxLines = 1,
    )
}

/** Shown instead of the field content while there is no live connection. */
@Composable
internal fun LinkBlock(state: HomewindRuntime.FieldState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Homewind", style = small())
        Text(
            text = when (state.link) {
                Link.NOT_CONFIGURED -> "Set up"
                Link.IDLE, Link.CONNECTING, Link.CONNECTED -> "…"
                Link.WIFI_OFF -> "Wi-Fi off"
                Link.UNREACHABLE -> "Offline"
                Link.FULL -> "Web UI full"
                Link.BUSY -> "Busy"
            },
            style = bold(18),
            maxLines = 1,
        )
        if (state.link in DETAIL_LINKS) {
            state.error?.let {
                Text(it, style = TextStyle(color = ColorProvider(MUTED), fontSize = 9.sp, textAlign = TextAlign.Center), maxLines = 2)
            }
        }
    }
}

private val DETAIL_LINKS = setOf(Link.UNREACHABLE, Link.FULL, Link.WIFI_OFF, Link.BUSY)

@Composable
internal fun Waiting() = Message("…")

@Composable
internal fun Message(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Homewind", style = small())
        Text(text, style = bold(18), maxLines = 1)
    }
}

@Composable
internal fun FanPercent(hw: HomewindState, size: Int) {
    Text(
        text = "${hw.shownPct} %",
        style = TextStyle(
            color = ColorProvider(if (hw.shownPct > 0) GREEN else Color.White),
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
    )
}

@Composable
internal fun Dots(hw: HomewindState) {
    Row {
        hw.fans.forEachIndexed { i, dot ->
            Text(
                text = if (i == 0) "●" else " ●",
                style = TextStyle(color = ColorProvider(dotColor(dot)), fontSize = 12.sp),
            )
        }
    }
}

@Composable
internal fun ModeButton(hw: HomewindState, modifier: GlanceModifier) {
    Rounded(CONTROL, modifier.height(28.dp).clickable(actionRunCallback<ModeAction>()), CONTROL_RADIUS) {
        Text(modeLabel(hw), style = bold(14), maxLines = 1)
    }
}

@Composable
internal fun Key(icon: Int, action: Action, modifier: GlanceModifier) {
    Box(modifier = modifier.padding(2.dp)) {
        Rounded(CONTROL, GlanceModifier.fillMaxSize().clickable(action), CONTROL_RADIUS) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = null,
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
            )
        }
    }
}

@Composable
internal fun BadgeChip(label: String, badge: Badge) {
    val color = when (badge) {
        Badge.GREEN -> GREEN
        Badge.ORANGE -> ORANGE
        Badge.GREY, Badge.HIDDEN -> BADGE_GREY
    }
    Rounded(color, GlanceModifier, CONTROL_RADIUS) {
        // Touch concept: black text on the badge colour.
        Box(modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(label, style = TextStyle(color = ColorProvider(Color.Black), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** Coloured background with rounded corners (Glance cornerRadius, Android 12+; Karoo 3 runs Android 12). */
@Composable
internal fun Rounded(
    color: Color,
    modifier: GlanceModifier,
    radius: Dp = CELL_RADIUS,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.cornerRadius(radius).background(ColorProvider(color)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

internal fun modeLabel(hw: HomewindState): String = (hw.effective ?: hw.mode)?.label ?: ""

internal fun small(align: TextAlign = TextAlign.Center) =
    TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp, textAlign = align)

internal fun bold(size: Int, align: TextAlign = TextAlign.Center) =
    TextStyle(color = ColorProvider(Color.White), fontSize = size.sp, fontWeight = FontWeight.Bold, textAlign = align)

private fun dotColor(dot: FanDot): Color = when (dot) {
    FanDot.ON -> GREEN
    FanDot.OFF -> Color.White
    FanDot.ERROR -> ORANGE
    FanDot.EMPTY -> EMPTY
}
