package io.homewind.karoo.fields

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
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

/** "Homewind sensors": one line per set-up sensor: icon, source name, value in the sensor colour. */
class SensorsField(extension: String) : HomewindGraphicField(extension, "homewind-sensors") {
    override fun renderKey(state: HomewindRuntime.FieldState): Any =
        listOf(state.link, state.error, state.hw.sensors == null) + Kind.entries.map { it.row(state.hw) }

    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (state.hw.sensors == null) { Waiting(); return }
        val rows = Kind.entries.map { it to it.row(state.hw) }.filter { it.second.status != RowStatus.NOT_CONFIGURED }
        if (rows.isEmpty()) { Message("No sensors"); return }
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp)) {
            rows.forEach { (kind, r) ->
                val active = r.status == RowStatus.ACTIVE
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(kind.icon, if (active) kind.color else MUTED)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(r.name ?: "", style = small(TextAlign.Start), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    Text(
                        sensorValue(kind, r, state.hw),
                        style = bold(if (active) minOf(config.textSize, 24) else 14, TextAlign.End)
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
class AppsField(extension: String) : HomewindGraphicField(extension, "homewind-apps") {
    override fun renderKey(state: HomewindRuntime.FieldState): Any =
        listOf(state.link, state.error, state.hw.apps, state.hw.trainer, Display.appBadge(state.hw), Display.trainerBadge(state.hw))

    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        val app = Display.appBadge(state.hw)
        val trainer = Display.trainerBadge(state.hw)
        Card(R.drawable.ic_fan, GREEN, "Apps", modifier = GlanceModifier.clickable(actionStartActivity<QrActivity>())) {
            val detail = buildList {
                state.hw.apps?.let { add(if (it == 1) "1 app" else "$it apps") }
                state.hw.trainer?.takeIf { it.mirror }?.name?.ifBlank { null }?.let { add(it) }
            }.joinToString(" · ")
            Text(detail, style = small(TextAlign.End), maxLines = 1, modifier = GlanceModifier.fillMaxWidth())
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.defaultWeight())
                BadgeChip("APP", app)
                if (trainer != Badge.HIDDEN) {
                    Spacer(GlanceModifier.width(6.dp))
                    BadgeChip("Trainer", trainer)
                }
            }
        }
    }
}
