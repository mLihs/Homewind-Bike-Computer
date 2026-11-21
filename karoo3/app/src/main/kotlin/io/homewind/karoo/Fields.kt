package io.homewind.karoo

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.homewind.karoo.core.Badge
import io.homewind.karoo.core.Display
import io.homewind.karoo.core.FanDot
import io.homewind.karoo.core.HeatScale
import io.homewind.karoo.core.HomewindState
import io.homewind.karoo.core.HrStep
import io.homewind.karoo.core.Link
import io.homewind.karoo.core.RowStatus
import io.homewind.karoo.core.SensorRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Colours from the Touch concept V2.
private val GREEN = Color(0xFF06BB60)
private val ORANGE = Color(0xFFFC9300)
private val BADGE_GREY = Color(0xFF3D4F67)
private val EMPTY = Color(0xFF303132)
private val CONTROL = Color(0xFF1E1E2A)
private val BACKGROUND = Color(0xFF000000)
private val MUTED = Color(0xB3FFFFFF)
private val HR_ON = Color(0xFFF00F66)
private val POWER_ON = Color(0xFF7A5AF8)
private val POWER_OFF = Color(0xFF322566)
private val CADENCE_ON = Color(0xFF0E7FA3)
private val CADENCE_OFF = Color(0xFF0A2933)
private val HR_OFF = Color(0xFF4A0520)

// ---- tap actions -----------------------------------------------------------------------------

class PlusAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HomewindRuntime.attach(context)
        HomewindRuntime.step(HrStep.Direction.PLUS)
    }
}

class MinusAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HomewindRuntime.attach(context)
        HomewindRuntime.step(HrStep.Direction.MINUS)
    }
}

class ModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HomewindRuntime.attach(context)
        HomewindRuntime.cycleMode()
    }
}

// ---- view lifecycle --------------------------------------------------------------------------

/**
 * Common view lifecycle: holds the connection while the field is on screen and
 * re-renders on every state change. The Karoo drops view updates that arrive
 * less than ~900 ms apart, so the latest state is drawn once more after 1.1 s.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class HomewindGraphicField(extension: String, typeId: String) : DataTypeImpl(extension, typeId) {
    private val glance = GlanceRemoteViews()

    @Composable
    abstract fun Content(config: ViewConfig, state: HomewindRuntime.FieldState)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        runCatching { emitter.onNext(UpdateGraphicConfig(showHeader = false)) }
        val key = "view:$typeId:${UUID.randomUUID()}"
        if (!config.preview) HomewindRuntime.acquire(context, key) else HomewindRuntime.attach(context)

        val tornDown = AtomicBoolean(false)
        val jobRef = AtomicReference<Job?>(null)
        fun tearDown() {
            if (tornDown.compareAndSet(false, true)) {
                HomewindRuntime.release(key)
                jobRef.get()?.cancel()
            }
        }

        val job = CoroutineScope(Dispatchers.Main).launch {
            HomewindRuntime.field.collectLatest { state ->
                if (!render(context, config, emitter, state)) { tearDown(); return@collectLatest }
                delay(1_100)
                if (!render(context, config, emitter, state)) tearDown()
            }
        }
        jobRef.set(job)
        if (tornDown.get()) job.cancel()
        emitter.setCancellable { tearDown() }
    }

    private suspend fun render(
        context: Context,
        config: ViewConfig,
        emitter: ViewEmitter,
        state: HomewindRuntime.FieldState,
    ): Boolean {
        val result = glance.compose(context, DpSize.Unspecified) {
            Rounded(BACKGROUND, GlanceModifier.fillMaxSize()) {
                if (state.link != Link.CONNECTED) LinkBlock(state) else Content(config, state)
            }
        }
        return try {
            emitter.updateView(result.remoteViews)
            true
        } catch (e: RemoteException) {
            Log.w("HomewindField", "host gone, stopping view", e)
            false
        }
    }
}

// ---- fan fields ------------------------------------------------------------------------------

/** "Homewind fans" (small): mode in effect, fan speed in %, four fan dots. Display only. */
class StatusField(extension: String) : HomewindGraphicField(extension, "homewind") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(modeLabel(state.hw), style = small(), maxLines = 1)
            FanPercent(state.hw, minOf(config.textSize, 34))
            Dots(state.hw)
        }
    }
}

/**
 * "Homewind fan colour" (small): fan speed in %, the whole tile coloured in 10 % steps
 * from blue (0 %) to red (100 %), see [HeatScale]. Mode in effect above the number.
 */
class FanColorField(extension: String) : HomewindGraphicField(extension, "homewind-fan-color") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        val argb = HeatScale.colorFor(state.hw.shownPct)
        val text = if (HeatScale.prefersDarkText(argb)) Color.Black else Color.White
        Rounded(Color(argb), GlanceModifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    modeLabel(state.hw),
                    style = TextStyle(color = ColorProvider(text.copy(alpha = 0.75f)), fontSize = 12.sp, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
                Text(
                    "${state.hw.shownPct} %",
                    style = TextStyle(color = ColorProvider(text), fontSize = minOf(config.textSize, 34).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
    }
}

/** "Homewind fan control" (wide): minus | mode (tap to switch), %, dots | plus. */
class ControlField(extension: String) : HomewindGraphicField(extension, "homewind-control") {
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
                ModeButton(state.hw, GlanceModifier.fillMaxWidth())
                FanPercent(state.hw, minOf(config.textSize, 30))
                Dots(state.hw)
            }
            Key(R.drawable.ic_plus, actionRunCallback<PlusAction>(), GlanceModifier.width(56.dp).fillMaxHeight())
        }
    }
}

/** "Homewind − +" (small): the two keys only. */
class KeysField(extension: String) : HomewindGraphicField(extension, "homewind-keys") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        Row(modifier = GlanceModifier.fillMaxSize()) {
            Key(R.drawable.ic_minus, actionRunCallback<MinusAction>(), GlanceModifier.defaultWeight().fillMaxHeight())
            Key(R.drawable.ic_plus, actionRunCallback<PlusAction>(), GlanceModifier.defaultWeight().fillMaxHeight())
        }
    }
}

/** "Homewind mode" (small): shows the mode, a tap switches Pure → Smart → Hybrid. */
class ModeField(extension: String) : HomewindGraphicField(extension, "homewind-mode") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        Rounded(CONTROL, GlanceModifier.fillMaxSize().clickable(actionRunCallback<ModeAction>())) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Mode", style = small(), maxLines = 1)
                Text(modeLabel(state.hw), style = bold(minOf(config.textSize, 26)), maxLines = 1)
            }
        }
    }
}

/** "Homewind fan %": plain number field, styled by the Karoo like its own fields. */
class FanSpeedField(extension: String, private val context: Context) : DataTypeImpl(extension, "homewind-fan") {
    override fun startStream(emitter: Emitter<StreamState>) {
        val key = "stream:${UUID.randomUUID()}"
        HomewindRuntime.acquire(context, key)
        val job = CoroutineScope(Dispatchers.IO).launch {
            HomewindRuntime.field
                .map { s -> if (s.link == Link.CONNECTED && s.hw.hasFanControl) s.hw.shownPct else null }
                .distinctUntilChanged()
                .collect { pct ->
                    emitter.onNext(
                        if (pct == null) StreamState.Searching
                        else StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to pct.toDouble())))
                    )
                }
        }
        emitter.setCancellable {
            job.cancel()
            HomewindRuntime.release(key)
        }
    }
}

// ---- trainer / app ---------------------------------------------------------------------------

/** "Homewind apps": badges APP and Trainer with the Touch colours. */
class AppsField(extension: String) : HomewindGraphicField(extension, "homewind-apps") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        val app = Display.appBadge(state.hw)
        val trainer = Display.trainerBadge(state.hw)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgeChip("APP", app)
                if (trainer != Badge.HIDDEN) {
                    Spacer(GlanceModifier.width(6.dp))
                    BadgeChip("Trainer", trainer)
                }
            }
            val detail = buildList {
                state.hw.apps?.let { add(if (it == 1) "1 app" else "$it apps") }
                state.hw.trainer?.takeIf { it.mirror }?.name?.ifBlank { null }?.let { add(it) }
            }.joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = small(), maxLines = 1)
        }
    }
}

// ---- sensors ---------------------------------------------------------------------------------

private enum class Kind(val icon: Int, val on: Color, val off: Color, val unit: String) {
    HR(R.drawable.ic_heart, HR_ON, HR_OFF, ""),
    POWER(R.drawable.ic_bolt, POWER_ON, POWER_OFF, " W"),
    CADENCE(R.drawable.ic_crank, CADENCE_ON, CADENCE_OFF, " rpm"),
}

private fun row(kind: Kind, hw: HomewindState): SensorRow = when (kind) {
    Kind.HR -> Display.heartRate(hw)
    Kind.POWER -> Display.power(hw)
    Kind.CADENCE -> Display.cadence(hw)
}

/** "Homewind sensors": heart rate, power and cadence rows; unconfigured rows are hidden. */
class SensorsField(extension: String) : HomewindGraphicField(extension, "homewind-sensors") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (state.hw.sensors == null) { Waiting(); return }
        val rows = Kind.entries.map { it to row(it, state.hw) }.filter { it.second.status != RowStatus.NOT_CONFIGURED }
        if (rows.isEmpty()) { Message("No sensors"); return }
        Column(modifier = GlanceModifier.fillMaxSize()) {
            rows.forEach { (kind, r) ->
                SensorTile(kind, r, minOf(config.textSize, 24), GlanceModifier.fillMaxWidth().defaultWeight())
            }
        }
    }
}

/** One sensor alone: "Homewind HR", "Homewind power", "Homewind cadence". */
private class SingleSensorField(extension: String, typeId: String, private val kind: Kind) :
    HomewindGraphicField(extension, typeId) {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (state.hw.sensors == null) { Waiting(); return }
        SensorTile(kind, row(kind, state.hw), minOf(config.textSize, 34), GlanceModifier.fillMaxSize())
    }
}

fun hrField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-hr", Kind.HR)
fun powerField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-power", Kind.POWER)
fun cadenceField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-cadence", Kind.CADENCE)

@Composable
private fun SensorTile(kind: Kind, r: SensorRow, valueSize: Int, modifier: GlanceModifier) {
    val active = r.status == RowStatus.ACTIVE
    val bg = when (r.status) {
        RowStatus.ACTIVE -> kind.on
        RowStatus.DISCONNECTED -> kind.off
        RowStatus.NOT_CONFIGURED -> CONTROL
    }
    Rounded(bg, modifier.padding(1.dp)) {
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(kind.icon),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(ColorProvider(if (active) Color.White else MUTED)),
            )
            Spacer(GlanceModifier.width(6.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(r.name ?: "", style = small(TextAlign.Start), maxLines = 1)
                Text(
                    text = when (r.status) {
                        RowStatus.ACTIVE -> "${r.value}${kind.unit}"
                        RowStatus.DISCONNECTED -> "Disconnected"
                        RowStatus.NOT_CONFIGURED -> "Not set up"
                    },
                    style = bold(if (active) valueSize else minOf(valueSize, 16), TextAlign.Start),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---- building blocks -------------------------------------------------------------------------

@Composable
private fun LinkBlock(state: HomewindRuntime.FieldState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Homewind", style = small())
        Text(
            text = when (state.link) {
                Link.NOT_CONFIGURED -> "Set up"
                Link.CONNECTING -> "…"
                Link.WIFI_OFF -> "Wi-Fi off"
                Link.UNREACHABLE -> "Offline"
                Link.FULL -> "Web UI full"
                Link.CONNECTED -> "…"
            },
            style = bold(18),
            maxLines = 1,
        )
        if (state.link == Link.UNREACHABLE || state.link == Link.FULL || state.link == Link.WIFI_OFF) {
            state.error?.let {
                Text(it, style = TextStyle(color = ColorProvider(MUTED), fontSize = 9.sp, textAlign = TextAlign.Center), maxLines = 2)
            }
        }
    }
}

@Composable
private fun Waiting() = Message("…")

@Composable
private fun Message(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Homewind", style = small())
        Text(text, style = bold(18), maxLines = 1)
    }
}

@Composable
private fun FanPercent(hw: HomewindState, size: Int) {
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
private fun Dots(hw: HomewindState) {
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
private fun ModeButton(hw: HomewindState, modifier: GlanceModifier) {
    Rounded(CONTROL, modifier.height(28.dp).clickable(actionRunCallback<ModeAction>()), R.drawable.bg_round_small) {
        Text(modeLabel(hw), style = bold(14), maxLines = 1)
    }
}

@Composable
private fun Key(icon: Int, action: Action, modifier: GlanceModifier) {
    Box(modifier = modifier.padding(2.dp)) {
        Rounded(CONTROL, GlanceModifier.fillMaxSize().clickable(action), R.drawable.bg_round_small) {
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
private fun BadgeChip(label: String, badge: Badge) {
    val color = when (badge) {
        Badge.GREEN -> GREEN
        Badge.ORANGE -> ORANGE
        Badge.GREY, Badge.HIDDEN -> BADGE_GREY
    }
    Rounded(color, GlanceModifier, R.drawable.bg_round_small) {
        // Touch concept: black text on the badge colour.
        Box(modifier = GlanceModifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(label, style = TextStyle(color = ColorProvider(Color.Black), fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/**
 * Coloured background with rounded corners. Glance's cornerRadius() works only from
 * Android 12, so the shape is a tinted drawable behind the content; works on every version.
 */
@Composable
private fun Rounded(
    color: Color,
    modifier: GlanceModifier,
    shape: Int = R.drawable.bg_round,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(shape),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(ColorProvider(color)),
        )
        content()
    }
}

private fun modeLabel(hw: HomewindState): String = (hw.effective ?: hw.mode)?.label ?: ""

private fun small(align: TextAlign = TextAlign.Center) =
    TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp, textAlign = align)

private fun bold(size: Int, align: TextAlign = TextAlign.Center) =
    TextStyle(color = ColorProvider(Color.White), fontSize = size.sp, fontWeight = FontWeight.Bold, textAlign = align)

private fun dotColor(dot: FanDot): Color = when (dot) {
    FanDot.ON -> GREEN
    FanDot.OFF -> Color.White
    FanDot.ERROR -> ORANGE
    FanDot.EMPTY -> EMPTY
}
