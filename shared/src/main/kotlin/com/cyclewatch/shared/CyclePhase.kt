package com.cyclewatch.shared

/**
 * The cycle phase inferred from recent Health Connect symptom data.
 *
 * [id] is the wire value exchanged between the phone and watch apps over the
 * Data Layer, and the value displayed by the watch complication (1..3, with
 * 0 meaning unknown).
 */
enum class CyclePhase(val id: Int) {
    /** Not enough data in the lookback window to match any rule. */
    PHASE_UNKNOWN(0),
    PHASE_MENSTRUAL(1),
    PHASE_FERTILE(2),
    PHASE_LUTEAL(3),
    ;

    companion object {
        fun fromId(id: Int): CyclePhase =
            entries.firstOrNull { it.id == id } ?: PHASE_UNKNOWN
    }
}
