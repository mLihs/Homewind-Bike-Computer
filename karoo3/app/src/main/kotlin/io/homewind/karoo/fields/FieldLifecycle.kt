package io.homewind.karoo.fields

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.fillMaxSize
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import io.homewind.karoo.HomewindRuntime
import io.homewind.karoo.core.Demo
import io.homewind.karoo.core.Link
import io.homewind.karoo.ui.BACKGROUND
import io.homewind.karoo.ui.LinkBlock
import io.homewind.karoo.ui.Rounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scope for all field coroutines. Supervised and with a handler, so an error in one field
 * is logged and ends that field only, never the extension process (which would take all
 * Homewind fields and the bonus actions with it).
 */
internal val FieldScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("HomewindField", "field coroutine failed", e) },
)

/**
 * Common view lifecycle: holds the connection while the field is on screen and redraws
 * when the part of the state the field shows ([renderKey]) changes, at most once per
 * [MIN_RENDER_MS]. The Karoo drops view updates that arrive less than ~900 ms apart, so
 * the newest state waits for the next slot instead of being drawn and dropped.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
abstract class HomewindGraphicField(extension: String, typeId: String) : DataTypeImpl(extension, typeId) {
    private val glance = GlanceRemoteViews()

    @Composable
    abstract fun Content(config: ViewConfig, state: HomewindRuntime.FieldState)

    /**
     * What this field shows; the view is redrawn only when it changes. Default: everything
     * but the graph counter. [GraphField] adds the counter.
     */
    protected open fun renderKey(state: HomewindRuntime.FieldState): Any = state.copy(historyVersion = 0)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        runCatching { emitter.onNext(UpdateGraphicConfig(showHeader = false)) }
        val key = "view:$typeId:${UUID.randomUUID()}"
        if (!config.preview) HomewindRuntime.acquire(context, key) else HomewindRuntime.attach(context)

        val tornDown = AtomicBoolean(false)
        val job = FieldScope.launch {
            if (config.preview) {
                // Field picker preview: animated example data like the Karoo's own fields, no connection.
                var tick = 0
                while (true) {
                    val demo = HomewindRuntime.FieldState(Link.CONNECTED, Demo.state(tick), fresh = true, demoSamples = Demo.samples(tick))
                    if (!render(context, config, emitter, demo)) break
                    tick++
                    delay(MIN_RENDER_MS + 100)
                }
            } else {
                HomewindRuntime.field
                    .distinctUntilChangedBy { renderKey(it) }
                    .conflate()
                    .collect { state ->
                        if (!render(context, config, emitter, state)) throw CancellationException("host gone")
                        delay(MIN_RENDER_MS)
                    }
            }
        }
        job.invokeOnCompletion {
            if (tornDown.compareAndSet(false, true) && !config.preview) HomewindRuntime.release(key)
        }
        emitter.setCancellable { job.cancel() }
    }

    /** Draws [state]; false when the Karoo is gone and the view should stop. */
    private suspend fun render(
        context: Context,
        config: ViewConfig,
        emitter: ViewEmitter,
        state: HomewindRuntime.FieldState,
    ): Boolean {
        return try {
            val result = glance.compose(context, DpSize.Unspecified) {
                Rounded(BACKGROUND, GlanceModifier.fillMaxSize()) {
                    if (state.link != Link.CONNECTED) LinkBlock(state) else Content(config, state)
                }
            }
            emitter.updateView(result.remoteViews)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            Log.w(TAG, "host gone, stopping $typeId", e)
            false
        } catch (e: Exception) {
            // A bad frame or layout must not end the field: log and try again with the next state.
            Log.e(TAG, "render failed for $typeId", e)
            true
        }
    }

    companion object {
        private const val TAG = "HomewindField"
        const val MIN_RENDER_MS = 1_000L
    }
}
