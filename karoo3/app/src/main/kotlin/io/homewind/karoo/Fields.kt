package io.homewind.karoo

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.LocalContext
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import io.homewind.karoo.core.History
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

// Corner radii: cells close to the Karoo's data cells, controls smaller.
private val CELL_RADIUS = 10.dp
private val CONTROL_RADIUS = 6.dp

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

/** "Homewind fan · numeric": Karoo-style card: FAN · mode, fan dots, fan speed in % (green while running). */
class StatusField(extension: String) : HomewindGraphicField(extension, "homewind") {
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
class FanColorField(extension: String) : HomewindGraphicField(extension, "homewind-fan-color") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (!state.hw.hasFanControl) { Waiting(); return }
        val argb = HeatScale.colorFor(state.hw.shownPct)
        val text = Color.White
        Card(R.drawable.ic_fan, text, "Fan · ${modeLabel(state.hw)}", background = Color(argb), titleColor = text) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(GlanceModifier.defaultWeight())
                Value("${state.hw.shownPct} %", text, config)
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

/** "Homewind mode": MODE card, tap switches Pure → Smart → Hybrid. */
class ModeField(extension: String) : HomewindGraphicField(extension, "homewind-mode") {
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

/** "Homewind apps": APPS card with the badges APP and Trainer (Touch colours). */
class AppsField(extension: String) : HomewindGraphicField(extension, "homewind-apps") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        val app = Display.appBadge(state.hw)
        val trainer = Display.trainerBadge(state.hw)
        Card(R.drawable.ic_fan, GREEN, "Apps") {
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

// ---- sensors ---------------------------------------------------------------------------------

private enum class Kind(val icon: Int, val color: Color, val title: String, val unit: String) {
    HR(R.drawable.ic_heart, HR_ON, "HR", ""),
    POWER(R.drawable.ic_bolt, POWER_ON, "Power", " W"),
    CADENCE(R.drawable.ic_crank, CADENCE_ON, "Cadence", " rpm"),
}

private fun row(kind: Kind, hw: HomewindState): SensorRow = when (kind) {
    Kind.HR -> Display.heartRate(hw)
    Kind.POWER -> Display.power(hw)
    Kind.CADENCE -> Display.cadence(hw)
}

/** "Homewind sensors": one line per set-up sensor: icon, source name, value in the sensor colour. */
class SensorsField(extension: String) : HomewindGraphicField(extension, "homewind-sensors") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (state.hw.sensors == null) { Waiting(); return }
        val rows = Kind.entries.map { it to row(it, state.hw) }.filter { it.second.status != RowStatus.NOT_CONFIGURED }
        if (rows.isEmpty()) { Message("No sensors"); return }
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp)) {
            rows.forEach { (kind, r) ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kind.icon, if (r.status == RowStatus.ACTIVE) kind.color else MUTED)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(r.name ?: "", style = small(TextAlign.Start), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                    Text(
                        sensorValue(kind, r),
                        style = bold(if (r.status == RowStatus.ACTIVE) minOf(config.textSize, 24) else 14, TextAlign.End)
                            .copy(color = ColorProvider(if (r.status == RowStatus.ACTIVE) kind.color else MUTED)),
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
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        if (state.hw.sensors == null) { Waiting(); return }
        val r = row(kind, state.hw)
        val active = r.status == RowStatus.ACTIVE
        Card(kind.icon, if (active) kind.color else MUTED, kind.title) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(r.name ?: "", style = small(TextAlign.Start), maxLines = 1, modifier = GlanceModifier.defaultWeight())
                if (active) Value(sensorValue(kind, r), kind.color, config)
                else Text(sensorValue(kind, r), style = bold(16, TextAlign.End).copy(color = ColorProvider(MUTED)), maxLines = 1)
            }
        }
    }
}

fun hrField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-hr", Kind.HR)
fun powerField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-power", Kind.POWER)
fun cadenceField(extension: String): DataTypeImpl = SingleSensorField(extension, "homewind-cadence", Kind.CADENCE)

private fun sensorValue(kind: Kind, r: SensorRow): String = when (r.status) {
    RowStatus.ACTIVE -> "${r.value}${kind.unit}"
    RowStatus.DISCONNECTED -> "Disconnected"
    RowStatus.NOT_CONFIGURED -> "Not set up"
}

// ---- graphs ----------------------------------------------------------------------------------

/**
 * "Homewind graph" / "Homewind graph · zones": fan speed and heart rate over the last
 * 10 minutes, drawn by [GraphRenderer]. Header shows the current values.
 */
class GraphField(extension: String, typeId: String, private val style: GraphRenderer.Style) :
    HomewindGraphicField(extension, typeId) {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        val context = LocalContext.current
        val density = context.resources.displayMetrics.density
        val hw = state.hw
        val headerPx = (30 * density).toInt()
        val samples = HomewindRuntime.history.snapshot()
        val bitmap = GraphRenderer.render(
            width = config.viewSize.first - (16 * density).toInt(),
            height = config.viewSize.second - headerPx - (8 * density).toInt(),
            density = density,
            samples = samples,
            capacity = 300,
            hrMin = hw.hrMin,
            hrMax = hw.hrMax,
            style = style,
        )
        val hr = Display.heartRate(hw)
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(R.drawable.ic_fan, GREEN)
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    if (hw.hasFanControl) "${hw.shownPct} %" else "–",
                    style = bold(15, TextAlign.Start).copy(color = ColorProvider(if (style == GraphRenderer.Style.ZONES) Color(HeatScale.colorFor(hw.shownPct)) else GREEN)),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(8.dp))
                Icon(R.drawable.ic_heart, if (style == GraphRenderer.Style.ZONES) Color.White else HR_ON)
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    if (hr.status == RowStatus.ACTIVE) "${hr.value}" else "–",
                    style = bold(15, TextAlign.Start).copy(color = ColorProvider(if (style == GraphRenderer.Style.ZONES) Color.White else HR_ON)),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.defaultWeight())
                Text("FAN · HR", style = bold(15, TextAlign.End), maxLines = 1)
            }
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentScale = androidx.glance.layout.ContentScale.FillBounds,
            )
        }
    }
}

// ---- building blocks -------------------------------------------------------------------------

/**
 * Karoo-style card: header with a small icon on the left and the title in capitals on
 * the right, content (usually the value, right-aligned) at the bottom.
 */
@Composable
private fun Card(
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
private fun Icon(icon: Int, color: Color) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = null,
        modifier = GlanceModifier.size(18.dp),
        colorFilter = ColorFilter.tint(ColorProvider(color)),
    )
}

/** The big value, right-aligned like the Karoo's own fields. */
@Composable
private fun Value(text: String, color: Color, config: ViewConfig, max: Int = 40) {
    Text(
        text,
        style = TextStyle(color = ColorProvider(color), fontSize = minOf(config.textSize, max).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
        maxLines = 1,
    )
}

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
    Rounded(CONTROL, modifier.height(28.dp).clickable(actionRunCallback<ModeAction>()), CONTROL_RADIUS) {
        Text(modeLabel(hw), style = bold(14), maxLines = 1)
    }
}

@Composable
private fun Key(icon: Int, action: Action, modifier: GlanceModifier) {
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
private fun BadgeChip(label: String, badge: Badge) {
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
private fun Rounded(
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
