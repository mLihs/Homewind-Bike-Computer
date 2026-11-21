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
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
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
import io.homewind.karoo.core.FanDot
import io.homewind.karoo.core.HrStep
import io.homewind.karoo.core.Link
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
private val EMPTY = Color(0xFF303132)
private val CONTROL = Color(0xFF1E1E2A)
private val BACKGROUND = Color(0xFF000000)
private val MUTED = Color(0xB3FFFFFF)

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
        val result = glance.compose(context, DpSize.Unspecified) { Content(config, state) }
        return try {
            emitter.updateView(result.remoteViews)
            true
        } catch (e: RemoteException) {
            Log.w("HomewindField", "host gone, stopping view", e)
            false
        }
    }
}

/** "Homewind": mode in effect, fan speed in %, four fan dots. Display only. */
class StatusField(extension: String) : HomewindGraphicField(extension, "homewind") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        Box(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(BACKGROUND)).padding(2.dp),
            contentAlignment = Alignment.Center,
        ) { StatusBlock(config, state) }
    }
}

/** "Homewind control": minus | status | plus. Tap minus or plus; the centre does nothing. */
class ControlField(extension: String) : HomewindGraphicField(extension, "homewind-control") {
    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        Row(
            modifier = GlanceModifier.fillMaxSize().background(ColorProvider(BACKGROUND)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(R.drawable.ic_minus, actionRunCallback<MinusAction>())
            Box(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) { StatusBlock(config, state) }
            Key(R.drawable.ic_plus, actionRunCallback<PlusAction>())
        }
    }

    @Composable
    private fun Key(icon: Int, action: androidx.glance.action.Action) {
        Box(
            modifier = GlanceModifier
                .width(52.dp)
                .fillMaxHeight()
                .padding(2.dp)
                .background(ColorProvider(CONTROL))
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
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
private fun StatusBlock(config: ViewConfig, state: HomewindRuntime.FieldState) {
    val big = minOf(config.textSize, 34)
    val hw = state.hw
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.link != Link.CONNECTED || !hw.hasFanControl) {
            Text("Homewind", style = small())
            Text(
                text = when (state.link) {
                    Link.NOT_CONFIGURED -> "Set up"
                    Link.CONNECTING -> "…"
                    Link.UNREACHABLE -> "Offline"
                    Link.FULL -> "Web UI full"
                    Link.CONNECTED -> "…"
                },
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (state.link == Link.UNREACHABLE || state.link == Link.FULL) {
                state.error?.let { Text(it, style = TextStyle(color = ColorProvider(MUTED), fontSize = 9.sp, textAlign = TextAlign.Center), maxLines = 2) }
            }
            return@Column
        }
        Text((hw.effective ?: hw.mode)?.label ?: "", style = small(), maxLines = 1)
        Text(
            text = "${hw.shownPct} %",
            style = TextStyle(
                color = ColorProvider(if (hw.shownPct > 0) GREEN else Color.White),
                fontSize = big.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
        Row {
            hw.fans.forEachIndexed { i, dot ->
                Text(
                    text = if (i == 0) "●" else " ●",
                    style = TextStyle(color = ColorProvider(dotColor(dot)), fontSize = 12.sp),
                )
            }
        }
    }
}

private fun small() = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp, textAlign = TextAlign.Center)

private fun dotColor(dot: FanDot): Color = when (dot) {
    FanDot.ON -> GREEN
    FanDot.OFF -> Color.White
    FanDot.ERROR -> ORANGE
    FanDot.EMPTY -> EMPTY
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
