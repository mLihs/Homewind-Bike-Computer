package io.homewind.karoo.core

/** Badge colours as on the Touch (concept V2, "Badges"). */
enum class Badge { GREEN, ORANGE, GREY, HIDDEN }

enum class RowStatus { ACTIVE, DISCONNECTED, NOT_CONFIGURED }

/** One sensor row: heart rate, power or cadence. [value] is null unless ACTIVE. */
data class SensorRow(val status: RowStatus, val name: String?, val value: Int?)

/**
 * The Touch display rules (concept V2), applied to the WebSocket state so the
 * Karoo shows the same as the Homewind Touch.
 */
object Display {

    /**
     * Trainer badge: green = mirrored and ready; orange = was ready while we were
     * connected, now not; grey = mirrored, not ready yet; hidden = no trainer set up,
     * paused, or not mirrored this boot.
     */
    fun trainerBadge(s: HomewindState): Badge {
        val t = s.trainer ?: return Badge.HIDDEN
        return when {
            !t.mirror -> Badge.HIDDEN
            t.ready -> Badge.GREEN
            s.trainerWasReady -> Badge.ORANGE
            else -> Badge.GREY
        }
    }

    /** APP badge: green = an app is connected; orange = it dropped; grey = none yet. */
    fun appBadge(s: HomewindState): Badge = when {
        s.apps == null -> Badge.HIDDEN
        s.apps > 0 -> Badge.GREEN
        s.appWasConnected -> Badge.ORANGE
        else -> Badge.GREY
    }

    private fun sensor(s: HomewindState, type: SensorType): Sensor? = s.sensors?.firstOrNull { it.type == type }

    private fun trainerName(s: HomewindState): String = s.trainer?.name?.ifBlank { null } ?: "Trainer"

    private fun mirrored(s: HomewindState): Boolean = s.trainer?.mirror == true

    fun heartRate(s: HomewindState): SensorRow {
        val hr = sensor(s, SensorType.HR) ?: return SensorRow(RowStatus.NOT_CONFIGURED, null, null)
        return if (hr.connected) SensorRow(RowStatus.ACTIVE, hr.name, s.heartRate ?: 0)
        else SensorRow(RowStatus.DISCONNECTED, hr.name, null)
    }

    /** Power: the power meter while it delivers, otherwise the trainer (BIKE_METRICS bit 3). */
    fun power(s: HomewindState): SensorRow {
        val pm = sensor(s, SensorType.PWR)
        if (pm == null && !mirrored(s)) return SensorRow(RowStatus.NOT_CONFIGURED, null, null)
        val b = s.bike
        val name = if (b?.powerFromTrainer == true || pm == null) trainerName(s) else pm.name
        return if (b != null && b.powerValid) SensorRow(RowStatus.ACTIVE, name, b.powerW)
        else SensorRow(RowStatus.DISCONNECTED, name, null)
    }

    /**
     * Cadence: CSC sensor, else power meter (once it delivered cadence), else trainer.
     * The row exists while one of these sources exists.
     */
    fun cadence(s: HomewindState): SensorRow {
        val csc = sensor(s, SensorType.CSC)
        val pm = sensor(s, SensorType.PWR)
        val name = when {
            csc != null -> csc.name
            pm != null && (s.cadenceFromPowerMeterSeen || s.bike?.cadenceFromPowerMeter == true) -> pm.name
            mirrored(s) -> trainerName(s)
            else -> return SensorRow(RowStatus.NOT_CONFIGURED, null, null)
        }
        val b = s.bike
        return if (b != null && b.cadenceValid) SensorRow(RowStatus.ACTIVE, name, b.cadenceRpm)
        else SensorRow(RowStatus.DISCONNECTED, name, null)
    }
}
