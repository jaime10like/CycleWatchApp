package com.cyclewatch.shared

/**
 * Wire format for the cycle-phase Data Layer item the phone app writes and
 * the watch app listens on.
 */
object PhaseSyncContract {
    /** Data Layer path for the current-phase item. */
    const val PATH_CYCLE_PHASE = "/cycle-phase"

    /** Int: [CyclePhase.id] (0 unknown, 1 menstrual, 2 fertile, 3 luteal). */
    const val KEY_PHASE_ID = "phase_id"

    /** Long: epoch millis of the analysis run that produced this phase. */
    const val KEY_UPDATED_AT = "updated_at"
}
