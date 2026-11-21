package io.homewind.karoo

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.homewind.karoo.core.HrStep

/**
 * Homewind for Karoo 3, MVP for Homewind 2.0.
 *
 * Bonus actions (map them to AXS bonus buttons or Wireless Blips on the Karoo):
 * - `plus`  more wind: HR range −5 bpm
 * - `minus` less wind: HR range +5 bpm
 * - `mode`  Pure → Smart → Hybrid → Pure (Hybrid skipped when Homewind rejects it)
 */
class HomewindExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private lateinit var karoo: KarooSystemService
    private var rideConsumer: String? = null

    override val types by lazy {
        listOf(
            StatusField(EXTENSION_ID),
            ControlField(EXTENSION_ID),
            FanSpeedField(EXTENSION_ID, applicationContext),
        )
    }

    override fun onCreate() {
        super.onCreate()
        HomewindRuntime.attach(this)
        karoo = KarooSystemService(this)
        karoo.connect { connected ->
            if (!connected) return@connect
            rideConsumer?.let { karoo.removeConsumer(it) }
            rideConsumer = karoo.addConsumer<RideState> { state ->
                // Keep the connection during a ride even when no Homewind field is on screen,
                // so the buttons answer at once. Idle → slot freed for the browsers.
                when (state) {
                    is RideState.Recording, is RideState.Paused -> HomewindRuntime.acquire(this, RIDE)
                    is RideState.Idle -> HomewindRuntime.release(RIDE)
                }
            }
        }
        HomewindRuntime.alertSink = { title, detail ->
            val sent = karoo.dispatch(
                InRideAlert(
                    id = "homewind",
                    icon = R.drawable.ic_homewind,
                    title = title,
                    detail = detail,
                    autoDismissMs = 3_000L,
                    backgroundColor = R.color.alert_background,
                    textColor = R.color.alert_text,
                ),
            )
            if (!sent) Log.d(TAG, "alert dropped (Karoo system not connected): $title $detail")
        }
    }

    override fun onDestroy() {
        HomewindRuntime.alertSink = null
        rideConsumer?.let { karoo.removeConsumer(it) }
        karoo.disconnect()
        HomewindRuntime.releaseAll()
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        Log.d(TAG, "onBonusAction $actionId")
        when (actionId) {
            ACTION_PLUS -> HomewindRuntime.step(HrStep.Direction.PLUS)
            ACTION_MINUS -> HomewindRuntime.step(HrStep.Direction.MINUS)
            ACTION_MODE -> HomewindRuntime.cycleMode()
            else -> Log.w(TAG, "unknown action $actionId")
        }
    }

    companion object {
        private const val TAG = "HomewindExtension"
        const val EXTENSION_ID = "homewind"
        private const val RIDE = "ride"
        const val ACTION_PLUS = "plus"
        const val ACTION_MINUS = "minus"
        const val ACTION_MODE = "mode"
    }
}
