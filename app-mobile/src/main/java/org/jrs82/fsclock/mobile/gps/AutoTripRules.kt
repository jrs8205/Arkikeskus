package org.jrs82.fsclock.mobile.gps

internal enum class CarEvent { FOUND, LOST, EXPIRED }
internal enum class CarAction { NONE, START, RESUME, REPLACE, WAIT, FINISH }
internal data class CarConnection(val connected: Set<String>, val event: CarEvent?)

internal fun carConnection(enabled: Boolean, cars: Set<String>, connected: Set<String>, address: String,
                           up: Boolean, transport: Int = 0): CarConnection {
    val known = selectedLinks(connected, cars)
    if (!enabled || address !in cars) return CarConnection(known, null)
    val link = if (transport == 0) address else "$address/$transport"
    val next = when {
        up -> known + link
        transport == 0 -> known.filterTo(mutableSetOf()) { it.substringBefore('/') != address }
        else -> known - link - address
    }
    val event = when {
        up && known.isEmpty() -> CarEvent.FOUND
        !up && next.isEmpty() -> CarEvent.LOST
        else -> null
    }
    return CarConnection(next, event)
}

internal fun selectedLinks(links: Set<String>, cars: Set<String>) =
    links.filterTo(mutableSetOf()) { it.substringBefore('/') in cars }

internal const val CAR_WAIT_MS = 60_000L
internal const val MAX_CAR_PAUSE_MS = 12 * 60 * 60_000L

internal data class CarTiming(
    val startedAuto: Boolean = false,
    val pausedWall: Long? = null,
    val userPaused: Boolean = false,
    val disconnectWall: Long? = null,
    val disconnectElapsed: Long = 0,
    val boot: Int = -1,
) {
    val waiting get() = disconnectWall != null

    fun remaining(wall: Long, elapsed: Long, currentBoot: Int): Long {
        val start = disconnectWall ?: return 0
        val age = if (boot >= 0 && currentBoot == boot) elapsed - disconnectElapsed else wall - start
        return (CAR_WAIT_MS - age.coerceAtLeast(0)).coerceAtLeast(0)
    }
}

internal fun carAction(phase: TripPhase, timing: CarTiming, event: CarEvent, wall: Long): CarAction = when (event) {
    CarEvent.FOUND -> when (phase) {
        TripPhase.NONE, TripPhase.FINISHED -> CarAction.START
        TripPhase.RUNNING -> CarAction.NONE
        TripPhase.PAUSED -> if (!timing.waiting && timing.pausedWall != null &&
            wall - timing.pausedWall > MAX_CAR_PAUSE_MS) CarAction.REPLACE else CarAction.RESUME
    }
    CarEvent.LOST -> if (phase == TripPhase.RUNNING || (phase == TripPhase.PAUSED && !timing.userPaused &&
        !timing.waiting)) CarAction.WAIT else CarAction.NONE
    CarEvent.EXPIRED -> if (phase == TripPhase.PAUSED && timing.waiting) CarAction.FINISH else CarAction.NONE
}

internal fun discardAutoTrip(startedAuto: Boolean, endedAuto: Boolean, distanceM: Double) =
    startedAuto && endedAuto && distanceM < 100.0
