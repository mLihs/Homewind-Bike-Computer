package io.homewind.karoo.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Example data for the field previews in the Karoo's field picker, like the Karoo's own
 * fields: a ride that builds up and eases off in a 60 s loop. No connection to Homewind.
 */
object Demo {
    const val LOOP_TICKS = 55 // one tick per view update (~1.1 s)

    private fun wave(tick: Int): Double = (1 - kotlin.math.cos(2 * PI * (tick % LOOP_TICKS) / LOOP_TICKS)) / 2 // 0..1..0

    fun fanPct(tick: Int): Int = (wave(tick) * 95).roundToInt()
    fun heartRate(tick: Int): Int = (110 + wave(tick) * 55).roundToInt()

    fun state(tick: Int): HomewindState {
        val w = wave(tick)
        return HomewindState(
            hrMin = 80,
            hrMax = 160,
            heartRate = heartRate(tick),
            mode = FanMode.HYBRID,
            effective = FanMode.HYBRID,
            targetOn = true,
            targetPct = fanPct(tick),
            ftpW = 250,
            cooldown = 0,
            fans = listOf(FanDot.ON, FanDot.ON, FanDot.OFF, FanDot.EMPTY),
            fansKnown = true,
            sensors = listOf(
                Sensor("TICKR", SensorType.HR, true, 80),
                Sensor("Assioma", SensorType.PWR, true, 90),
            ),
            trainer = Trainer(4, true, true, true, true, "Tacx NEO 2T"),
            trainerWasReady = true,
            apps = 1,
            appWasConnected = true,
            bike = BikeMetrics(
                powerW = (140 + w * 160).roundToInt(),
                cadenceRpm = (82 + sin(2 * PI * tick / 13.0) * 6).roundToInt(),
                powerValid = true,
                cadenceValid = true,
                cadenceFromPowerMeter = true,
                powerFromTrainer = false,
            ),
            cadenceFromPowerMeterSeen = true,
            speedKmh10 = (300 + w * 60).roundToInt(),
            speedValid = true,
        )
    }

    /** Graph history for the preview: the last [count] ticks, oldest first. */
    fun samples(tick: Int, count: Int = 300): List<Sample> =
        (tick - count + 1..tick).map { t -> Sample(fanPct(t), heartRate(t)) }
}
