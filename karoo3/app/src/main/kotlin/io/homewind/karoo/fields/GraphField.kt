package io.homewind.karoo.fields

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.unit.ColorProvider
import io.hammerhead.karooext.models.ViewConfig
import io.homewind.karoo.GraphRenderer
import io.homewind.karoo.HomewindRuntime
import io.homewind.karoo.R
import io.homewind.karoo.core.Display
import io.homewind.karoo.core.HeatScale
import io.homewind.karoo.core.RowStatus
import io.homewind.karoo.ui.GREEN
import io.homewind.karoo.ui.HR_ON
import io.homewind.karoo.ui.Icon
import io.homewind.karoo.ui.bold

/**
 * "Homewind graph" / "Homewind graph · zones": fan speed and heart rate over the last
 * 10 minutes, drawn by [GraphRenderer]. Header shows the current values.
 * Redraws with every graph sample (every 2 s) and on range or connection changes.
 */
class GraphField(extension: String, typeId: String, private val style: GraphRenderer.Style) :
    HomewindGraphicField(extension, typeId) {

    override fun renderKey(state: HomewindRuntime.FieldState): Any =
        listOf(state.link, state.error, state.historyVersion, state.demoSamples, state.hw.hrMin, state.hw.hrMax)

    @Composable
    override fun Content(config: ViewConfig, state: HomewindRuntime.FieldState) {
        val density = LocalContext.current.resources.displayMetrics.density
        val hw = state.hw
        val zones = style == GraphRenderer.Style.ZONES
        val bitmap = graph(config, density, state)
        val hr = Display.heartRate(hw)
        Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(R.drawable.ic_fan, GREEN)
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    if (hw.hasFanControl) "${hw.shownPct} %" else "–",
                    style = bold(15, TextAlign.Start).copy(color = ColorProvider(if (zones) Color(HeatScale.colorFor(hw.shownPct)) else GREEN)),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.width(8.dp))
                Icon(R.drawable.ic_heart, if (zones) Color.White else HR_ON)
                Spacer(GlanceModifier.width(4.dp))
                Text(
                    if (hr.status == RowStatus.ACTIVE) "${hr.value}" else "–",
                    style = bold(15, TextAlign.Start).copy(color = ColorProvider(if (zones) Color.White else HR_ON)),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.defaultWeight())
                Text("FAN · HR", style = bold(15, TextAlign.End), maxLines = 1)
            }
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }

    private fun graph(config: ViewConfig, density: Float, state: HomewindRuntime.FieldState): Bitmap {
        val headerPx = (30 * density).toInt()
        return GraphRenderer.render(
            width = config.viewSize.first - (16 * density).toInt(),
            height = config.viewSize.second - headerPx - (8 * density).toInt(),
            density = density,
            samples = state.demoSamples ?: HomewindRuntime.history.snapshot(),
            capacity = HomewindRuntime.history.capacity,
            hrMin = state.hw.hrMin,
            hrMax = state.hw.hrMax,
            style = style,
        )
    }
}
