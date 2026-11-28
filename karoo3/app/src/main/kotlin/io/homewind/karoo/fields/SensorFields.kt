package io.homewind.karoo.fields

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.models.ViewConfig
import io.homewind.karoo.HomewindRuntime
import io.homewind.karoo.QrActivity
import io.homewind.karoo.R
import io.homewind.karoo.core.Badge
import io.homewind.karoo.core.Display
import io.homewind.karoo.core.HomewindState
import io.homewind.karoo.core.RowStatus
import io.homewind.karoo.core.SensorRow
import io.homewind.karoo.ui.BadgeChip
import io.homewind.karoo.ui.CADENCE_ON
import io.homewind.karoo.ui.Card
import io.homewind.karoo.ui.GREEN
import io.homewind.karoo.ui.HR_ON
import io.homewind.karoo.ui.Icon
import io.homewind.karoo.ui.MUTED
import io.homewind.karoo.ui.Message
import io.homewind.karoo.ui.POWER_ON
import io.homewind.karoo.ui.SPEED_ON
import io.homewind.karoo.ui.Value
import io.homewind.karoo.ui.Waiting
import io.homewind.karoo.ui.bold
import io.homewind.karoo.ui.small

internal enum class Kind(val icon: Int, val color: Color, val title: String, val unit: String) {
    HR(R.drawable.ic_heart, HR_ON, "HR", ""),
    POWER(R.drawable.ic_bolt, POWER_ON, "Power", " W"),
    CADENCE(R.drawable.ic_crank, CADENCE_ON, "Cadence", " rpm"),
    SPEED(R.drawable.ic_speed, SPEED_ON, "Speed", " km/h");

    fun row(hw: HomewindState): SensorRow = when (this) {
        HR -> Display.heartRate(hw)
        POWER -> Display.power(hw)
        CADENCE -> Display.cadence(hw)
        SPEED -> Display.speed(hw)
    }

    /** Value text of an active row; speed comes in 0.1 km/h. */
    fun valueText(value: Int?): String = when (this) {
        SPEED -> value?.let { Display.speedText(it) } ?: ""
        else -> "${value ?: ""}"
    }
}

/** Rows of the combined field: three fit, a fourth is cut off. Speed has its own field and sits in the wide Apps field. */
private val COMBINED = listOf(Kind.HR, Kind.POWER, Kind.CADENCE)


/** "Homewind sensors": one line per set-up sensor: icon, source name, value in the sensor colour. */
class SensorsField(extension: String) : HomewindGraphicField(extension, "homewind-sensors") {
    override fun renderKey(state: HomewindRuntime.FieldState): Any =
        listOf(state.link, state.error, state.hw.sensors == null) + COMBINED.map { it.row(state.hw) }

    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (state.hw.sensors == null) { Waiting(); return }
        val rows = COMBINED.map { it to it.row(state.hw) }.filter { it.second.status != RowStatus.NOT_CONFIGURED }
        if (rows.isEmpty()) { Message("No sensors"); return }
        // Each row gets exactly a third of the height (no gaps, no padding), so nothing reaches past
        // the field. The value is sized so its text line (about 1.3 × the size, incl. font padding)
        // fits the row; on the Karoo 3 that is about the size of 1.1.6, which looked right.
        val heightDp = config.viewSize.second / LocalContext.current.resources.displayMetrics.density
        val valueSp = minOf(40, (heightDp / rows.size / 1.3f).toInt()).coerceAtLeast(12)
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp)) {
            rows.forEach { (kind, r) ->
                val active = r.status == RowStatus.ACTIVE
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(kind.icon, if (active) kind.color else MUTED)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(r.name ?: "", style = small(TextAlign.Start).copy(fontSize = 10.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    Text(
                        sensorValue(kind, r, state.hw),
                        style = bold(if (active) valueSp else minOf(valueSp, 14), TextAlign.End)
                            .copy(color = ColorProvider(if (active) kind.color else MUTED)),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One sensor as a Karoo-style card: "Homewind HR", "Homewind power", "Homewind cadence". */
private class SingleSensorField(extension: String, typeId: String, private val kind: Kind) :
    HomewindGraphicField(extension, typeId) {
    override fun renderKey(state: HomewindRuntime.FieldState): Any =
        listOf(state.link, state.error, state.hw.sensors == null, kind.row(state.hw))

    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (state.hw.sensors == null) { Waiting(); return }
        val r = kind.row(state.hw)
        val active = r.status == RowStatus.ACTIVE
        Card(kind.icon, if (active) kind.color else MUTED, kind.title) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(r.name ?: "", style = small(TextAlign.Start), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                if (active) Value(sensorValue(kind, r, state.hw), kind.color, config)
                else Text(sensorValue(kind, r, state.hw), style = bold(16, TextAlign.End).copy(color = ColorProvider(MUTED)), maxLines = 1)
            }
        }
    }
}

fun hrField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-hr", Kind.HR)
fun powerField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-power", Kind.POWER)
fun cadenceField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-cadence", Kind.CADENCE)
fun speedField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-speed", Kind.SPEED)

private fun sensorValue(kind: Kind, r: SensorRow, hw: HomewindState): String = when (r.status) {
    RowStatus.ACTIVE -> "${kind.valueText(r.value)}${kind.unit}"
    RowStatus.DISCONNECTED -> "Disconnected"
    RowStatus.NOT_CONFIGURED -> when {
        kind != Kind.SPEED -> "Not set up"
        hw.speedKmh10 == null -> "Homewind 2.2" // speed needs the state API
        else -> "No trainer"
    }
}

/** "Homewind apps": APPS card with the badges APP and Trainer (Touch colours); tap opens the QR code. */
/** From this width the Apps field counts as wide (full-width cell on the Karoo 3). */
private const val WIDE_DP = 240

class AppsField(extension: String) : HomewindGraphicField(extension, "homewind-apps") {
    override fun renderKey(state: HomewindRuntime.FieldState): Any =
        listOf(state.link, state.error, state.hw.apps, state.hw.trainer, Display.appBadge(state.hw), Display.trainerBadge(state.hw), Display.speed(state.hw))

    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        val app = Display.appBadge(state.hw)
        val trainer = Display.trainerBadge(state.hw)
        // Bottom padding 0: the line box of the 36 sp value already leaves about 8 dp under the digits,
        // so the visible gap at the bottom matches left and right.
        Card(R.drawable.ic_fan, GREEN, "Apps", modifier = GlanceModifier.clickable(actionStartActivity<QrActivity>()), bottom = 0.dp) {
            val detail = buildList {
                state.hw.apps?.let { add(if (it == 1) "1 app" else "$it apps") }
                state.hw.trainer?.takeIf { it.mirror }?.name?.ifBlank { null }?.let { add(it) }
            }.joinToString(" · ")
            Text(detail, style = small(TextAlign.End), maxLines = 1, modifier = GlanceModifier.fillMaxWidth())
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                // Wide field only: trainer speed on the left, next to the badges.
                val widthDp = config.viewSize.first / LocalContext.current.resources.displayMetrics.density
                val speed = Display.speed(state.hw)
                if (widthDp >= WIDE_DP && speed.status != RowStatus.NOT_CONFIGURED) {
                    val active = speed.status == RowStatus.ACTIVE
                    // Value large, unit small next to it.
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (active) Kind.SPEED.valueText(speed.value) else "–",
                            style = bold(if (active) 36 else 16, TextAlign.Start)
                                .copy(color = ColorProvider(if (active) Kind.SPEED.color else MUTED)),
                            maxLines = 1,
                        )
                        if (active) Text(
                            " km/h",
                            style = bold(16, TextAlign.Start).copy(color = ColorProvider(Kind.SPEED.color)),
                            maxLines = 1,
                            modifier = GlanceModifier.padding(bottom = 5.dp),
                        )
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                // Card bottom padding is 0 here: the badges keep 8 dp to the bottom, as to the right.
                Row(modifier = GlanceModifier.padding(bottom = 8.dp)) {
                    BadgeChip("APP", app)
                    if (trainer != Badge.HIDDEN) {
                        Spacer(GlanceModifier.width(6.dp))
                        BadgeChip("Trainer", trainer)
                    }
                }
            }
        }
    }
}
