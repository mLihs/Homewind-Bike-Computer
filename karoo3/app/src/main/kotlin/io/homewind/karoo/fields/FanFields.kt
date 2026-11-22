package io.homewind.karoo.fields

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import io.homewind.karoo.HomewindRuntime
import io.homewind.karoo.R
import io.homewind.karoo.core.FanMode
import io.homewind.karoo.core.HeatScale
import io.homewind.karoo.core.HomewindState
import io.homewind.karoo.core.Link
import io.homewind.karoo.ui.CONTROL
import io.homewind.karoo.ui.Card
import io.homewind.karoo.ui.Dots
import io.homewind.karoo.ui.FanPercent
import io.homewind.karoo.ui.GREEN
import io.homewind.karoo.ui.Key
import io.homewind.karoo.ui.ModeButton
import io.homewind.karoo.ui.Value
import io.homewind.karoo.ui.Waiting
import io.homewind.karoo.ui.modeLabel
import io.homewind.karoo.ui.small
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/** Fan fields redraw on fan, mode and range changes only, not on every heart-rate frame. */
abstract class FanField(extension: String, typeId: String) : HomewindGraphicField(extension, typeId) {
    override fun renderKey(state: HomewindRuntime.FieldState): Any {
        val hw = state.hw
        return listOf(state.link, state.error, hw.mode, hw.effective, hw.targetOn, hw.targetPct, hw.fans, hw.hrMin, hw.hrMax, hw.ftpW)
    }
}

/** "Homewind fan · numeric": Karoo-style card: FAN · mode, fan dots, fan speed in % (green while running). */
class StatusField(extension: String) : FanField(extension, "homewind") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        Card(R.drawable.ic_fan, GREEN, "Fan · ${modeLabel(state.hw)}") {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Dots(state.hw)
                Spacer(GlanceModifier.defaultWeight())
                Value("${state.hw.shownPct} %", if (state.hw.shownPct > 0) GREEN else Color.White, config)
            }
        }
    }
}

/**
 * "Homewind fan · zone colour": the whole tile in the 10 % step colour of the Homewind
 * palette, cadence blue (0 %) to HR pink (90 % and up), see [HeatScale]; white text.
 */
class FanColorField(extension: String) : FanField(extension, "homewind-fan-color") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        val text = Color.White
        Card(R.drawable.ic_fan, text, "Fan · ${modeLabel(state.hw)}", background = Color(HeatScale.colorFor(state.hw.shownPct)), titleColor = text) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.defaultWeight())
                Value("${state.hw.shownPct} %", text, config)
            }
        }
    }
}

/** "Homewind fan control" (wide): minus | mode (tap to switch), %, dots | plus. */
class ControlField(extension: String) : FanField(extension, "homewind-control") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Key(R.drawable.ic_minus, actionRunCallback<MinusAction>(), GlanceModifier.width(56.dp).fillMaxHeight())
            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val heightDp = config.viewSize.second / LocalContext.current.resources.displayMetrics.density
                ModeButton(state.hw, GlanceModifier.fillMaxWidth())
                FanPercent(state.hw, minOf(config.textSize, 30))
                // Low cells: the HR range matters more while adjusting than the dots.
                if (heightDp >= 120) Dots(state.hw)
                rangeLine(state.hw)?.let { Text(it, style = small(), maxLines = 1) }
            }
            Key(R.drawable.ic_plus, actionRunCallback<PlusAction>(), GlanceModifier.width(56.dp).fillMaxHeight())
        }
    }
}

/** "Homewind − +" (small): the two keys only. */
class KeysField(extension: String) : FanField(extension, "homewind-keys") {
    // The keys look the same in every state; redraw only when the connection changes.
    override fun renderKey(state: HomewindRuntime.FieldState): Any = listOf(state.link, state.error)

    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            Key(R.drawable.ic_minus, actionRunCallback<MinusAction>(), GlanceModifier.defaultWeight().fillMaxHeight())
            Key(R.drawable.ic_plus, actionRunCallback<PlusAction>(), GlanceModifier.defaultWeight().fillMaxHeight())
        }
    }
}

/** "Homewind mode": MODE card, tap switches Pure → Smart → Hybrid. */
class ModeField(extension: String) : FanField(extension, "homewind-mode") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        Card(
            R.drawable.ic_fan, GREEN, "Mode",
            background = CONTROL,
            modifier = GlanceModifier.clickable(actionRunCallback<ModeAction>()),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.defaultWeight())
                Value(modeLabel(state.hw).uppercase(), Color.White, config, max = 28)
            }
        }
    }
}

/** "Homewind fan %": plain number field, styled by the Karoo like its own fields. */
class FanSpeedField(extension: String, private val context: Context) : DataTypeImpl(extension, "homewind-fan") {
    override fun startStream(emitter: Emitter<StreamState>) {
        val key = "stream:${UUID.randomUUID()}"
        HomewindRuntime.acquire(context, key)
        val job = FieldScope.launch {
            HomewindRuntime.field
                .map { s -> if (s.link == Link.CONNECTED && s.hw.hasFanControl) s.hw.shownPct else null }
                .distinctUntilChanged()
                .collect { pct ->
                    // onNext is a blocking binder call; a dead host must end this stream only.
                    runCatching {
                        emitter.onNext(
                            if (pct == null) StreamState.Searching
                            else StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to pct.toDouble()))),
                        )
                    }.onFailure { throw kotlinx.coroutines.CancellationException("host gone") }
                }
        }
        job.invokeOnCompletion { HomewindRuntime.release(key) }
        emitter.setCancellable { job.cancel() }
    }
}

/** "HR 80–160 · FTP 250" for the control field; FTP only in Hybrid, where it acts on the fan. */
private fun rangeLine(hw: HomewindState): String? {
    val min = hw.hrMin ?: return null
    val max = hw.hrMax ?: return null
    val ftp = hw.ftpW?.takeIf { it > 0 && hw.mode == FanMode.HYBRID }
    return "HR $min–$max" + (ftp?.let { " · FTP $it" } ?: "")
}
