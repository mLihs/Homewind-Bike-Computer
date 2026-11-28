package io.homewind.karoo.core

/**
 * Parses `GET /api/v1/state` (Homewind 2.2): one `key=value` per line, no JSON.
 * Names (`*_name`) run to the end of the line, so commas in names are fine.
 *
 * ```
 * v=1
 * mode=hybrid          chosen mode
 * effective=smart      what really runs (Hybrid without power → Smart)
 * fan_pct=45
 * fans=1,1,2,0         by slot: 0 empty, 1 on, 2 off, 3 error
 * hr=142  hr_min=120  hr_max=160  ftp=250
 * limit=none|min|max
 * hr_sensor=1          1 connected, 0 disconnected, - not set up (same for pwr_sensor, csc_sensor)
 * power=210,1,0        W, valid, from trainer
 * cadence=88,1,1       rpm, valid, from power meter
 * speed=324,1          0.1 km/h, valid
 * trainer=4,1          BikeSensor TrainerState, mirror (key missing = no trainer)
 * apps=1
 * hr_name=Polar H10    (pwr_name, csc_name, trainer_name)
 * ```
 *
 * Unknown keys are ignored. The "was connected" flags behind the orange badges stay
 * client-side: they come from [prev], the result of the previous poll.
 */
object StateParser {
    const val PATH = "/api/v1/state"

    /** Null when [body] is not a state response (no `v=`), e.g. an HTML error page. */
    fun parse(body: String, prev: HomewindState): HomewindState? {
        val kv = HashMap<String, String>()
        for (raw in body.lineSequence()) {
            val line = raw.trimEnd('\r')
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            kv[line.substring(0, eq).trim()] = line.substring(eq + 1)
        }
        if (kv["v"]?.trim()?.toIntOrNull() == null) return null

        fun int(key: String) = kv[key]?.trim()?.toIntOrNull()
        fun ints(key: String) = kv[key]?.split(',')?.map { it.trim().toIntOrNull() }

        val mode = mode(kv["mode"])
        val pct = int("fan_pct")
        val fans = ints("fans")?.let { slots ->
            List(HomewindState.MAX_FANS) { i ->
                when (slots.getOrNull(i)) {
                    1 -> FanDot.ON
                    2 -> FanDot.OFF
                    3 -> FanDot.ERROR
                    else -> FanDot.EMPTY
                }
            }
        }

        val sensorKeys = listOf("hr" to SensorType.HR, "pwr" to SensorType.PWR, "csc" to SensorType.CSC)
        val sensors = if (sensorKeys.none { kv.containsKey("${it.first}_sensor") }) null else sensorKeys.mapNotNull { (key, type) ->
            when (kv["${key}_sensor"]?.trim()) {
                "1" -> Sensor(kv["${key}_name"].orEmpty().trim(), type, connected = true, battery = null)
                "0" -> Sensor(kv["${key}_name"].orEmpty().trim(), type, connected = false, battery = null)
                else -> null // "-" or missing: not set up
            }
        }

        val power = ints("power")
        val cadence = ints("cadence")
        val bike = if (power == null && cadence == null) null else BikeMetrics(
            powerW = power?.getOrNull(0) ?: 0,
            cadenceRpm = cadence?.getOrNull(0) ?: 0,
            powerValid = power?.getOrNull(1) == 1,
            cadenceValid = cadence?.getOrNull(1) == 1,
            cadenceFromPowerMeter = cadence?.getOrNull(2) == 1,
            powerFromTrainer = power?.getOrNull(2) == 1,
        )

        val speed = ints("speed")

        val trainer = ints("trainer")?.let { t ->
            Trainer(
                state = t.getOrNull(0) ?: 0,
                appConnected = false, // not in state; the APP badge uses apps=
                mirror = t.getOrNull(1) == 1,
                enabled = true,
                setUp = true,
                name = kv["trainer_name"].orEmpty().trim(),
            )
        }
        val apps = int("apps")

        return prev.copy(
            mode = mode,
            effective = mode(kv["effective"]) ?: mode,
            targetOn = (pct ?: 0) > 0,
            targetPct = (pct ?: 0).coerceIn(0, 100),
            ftpW = int("ftp"),
            cooldown = null,
            fans = fans ?: List(HomewindState.MAX_FANS) { FanDot.EMPTY },
            fansKnown = fans != null,
            heartRate = int("hr"),
            hrMin = int("hr_min"),
            hrMax = int("hr_max"),
            limit = when (kv["limit"]?.trim()?.lowercase()) {
                "none" -> HrLimit.NONE
                "min" -> HrLimit.MIN
                "max" -> HrLimit.MAX
                else -> null
            },
            sensors = sensors,
            bike = bike,
            speedKmh10 = speed?.getOrNull(0),
            speedValid = speed?.getOrNull(1) == 1,
            cadenceFromPowerMeterSeen = prev.cadenceFromPowerMeterSeen || bike?.cadenceFromPowerMeter == true,
            trainer = trainer,
            trainerWasReady = prev.trainerWasReady || (trainer != null && trainer.mirror && trainer.ready),
            apps = apps,
            appWasConnected = prev.appWasConnected || (apps ?: 0) > 0,
            clientId = null,
        )
    }

    /** `pure|smart|hybrid`, or the wire number as in FAN_CONTROL. */
    private fun mode(value: String?): FanMode? {
        val v = value?.trim()?.lowercase() ?: return null
        return FanMode.entries.firstOrNull { it.api == v || it.label.lowercase() == v }
            ?: v.toIntOrNull()?.let { FanMode.fromWire(it) }
    }

    /** `ERR:limit_min` / `ERR:limit_max` in a 409 answer of `fan/step`. */
    fun stepLimit(body: String): HrLimit? = when {
        "limit_min" in body -> HrLimit.MIN
        "limit_max" in body -> HrLimit.MAX
        else -> null
    }
}
