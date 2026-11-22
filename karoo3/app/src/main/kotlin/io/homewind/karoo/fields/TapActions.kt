package io.homewind.karoo.fields

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.homewind.karoo.HomewindActions
import io.homewind.karoo.HomewindRuntime
import io.homewind.karoo.core.HrStep

/** Taps on the tile keys; same behaviour as the bonus actions. */
abstract class TapAction(private val run: () -> Unit) : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HomewindRuntime.attach(context)
        run()
    }
}

class PlusAction : TapAction({ HomewindActions.step(HrStep.Direction.PLUS) })

class MinusAction : TapAction({ HomewindActions.step(HrStep.Direction.MINUS) })

class ModeAction : TapAction({ HomewindActions.cycleMode() })
