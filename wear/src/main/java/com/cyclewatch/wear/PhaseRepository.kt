package com.cyclewatch.wear

import android.content.Context
import androidx.core.content.edit
import com.cyclewatch.shared.CyclePhase

/**
 * On-watch persistence for the last phase received from the phone, so the
 * complication can render without waiting for a new Data Layer delivery.
 */
class PhaseRepository(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePhase(phaseId: Int, updatedAtMillis: Long) {
        prefs.edit {
            putInt(KEY_PHASE_ID, phaseId)
            putLong(KEY_UPDATED_AT, updatedAtMillis)
        }
    }

    fun currentPhase(): CyclePhase =
        CyclePhase.fromId(prefs.getInt(KEY_PHASE_ID, CyclePhase.PHASE_UNKNOWN.id))

    fun lastUpdatedAtMillis(): Long = prefs.getLong(KEY_UPDATED_AT, 0L)

    private companion object {
        const val PREFS_NAME = "cycle_phase"
        const val KEY_PHASE_ID = "phase_id"
        const val KEY_UPDATED_AT = "updated_at"
    }
}
