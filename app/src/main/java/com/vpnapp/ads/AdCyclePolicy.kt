package com.vpnapp.ads

data class AdCycleState(
    val successfulSession: Long = 0,
    val completedCycles: Int = 0,
    val lastAttemptMillis: Long = 0
)

/** Counts complete successful sessions, not button taps or repeated status broadcasts. */
object AdCyclePolicy {
    const val COOLDOWN_MILLIS = 120_000L
    /** null means wait for a cycle-change event; otherwise schedule exactly one cooldown. */
    fun delayUntilEligible(state: AdCycleState, now: Long): Long? {
        if (state.successfulSession != 0L || state.completedCycles < 2) return null
        if (state.lastAttemptMillis == 0L) return 0L
        return (COOLDOWN_MILLIS - (now - state.lastAttemptMillis)).coerceIn(0L, COOLDOWN_MILLIS)
    }
    fun connected(state: AdCycleState, session: Long): AdCycleState =
        if (session > 0) state.copy(successfulSession = session) else state

    fun disconnected(state: AdCycleState, session: Long): AdCycleState =
        if (session > 0 && state.successfulSession == session)
            state.copy(successfulSession = 0, completedCycles = (state.completedCycles + 1).coerceAtMost(2))
        else state

    fun eligible(state: AdCycleState, now: Long, foreground: Boolean, home: Boolean, disconnected: Boolean): Boolean =
        foreground && home && disconnected && state.successfulSession == 0L && state.completedCycles >= 2 &&
            (state.lastAttemptMillis == 0L || now - state.lastAttemptMillis >= COOLDOWN_MILLIS)
}
