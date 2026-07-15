package com.cyclewatch.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.cyclewatch.shared.CyclePhase

/**
 * Serves the current cycle phase as a RANGED_VALUE complication: value 1..3
 * where 1 = menstrual, 2 = fertile, 3 = luteal (matching [CyclePhase.id]).
 *
 * The value is read from [PhaseRepository]; [PhaseListenerService] refreshes
 * it whenever the phone pushes a new analysis, then requests an update, so
 * this data source needs no periodic schedule (UPDATE_PERIOD_SECONDS = 0).
 */
class CyclePhaseComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.RANGED_VALUE) {
            rangedPhaseData(CyclePhase.PHASE_FERTILE)
        } else {
            null
        }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.RANGED_VALUE) return null

        return when (val phase = PhaseRepository(this).currentPhase()) {
            CyclePhase.PHASE_UNKNOWN -> NoDataComplicationData()
            else -> rangedPhaseData(phase)
        }
    }

    private fun rangedPhaseData(phase: CyclePhase): ComplicationData {
        val label = phase.displayLabel()
        return RangedValueComplicationData.Builder(
            value = phase.id.toFloat().coerceIn(MIN_PHASE_VALUE, MAX_PHASE_VALUE),
            min = MIN_PHASE_VALUE,
            max = MAX_PHASE_VALUE,
            contentDescription = PlainComplicationText.Builder("Cycle phase: $label").build(),
        )
            .setText(PlainComplicationText.Builder(label).build())
            .build()
    }

    private fun CyclePhase.displayLabel(): String = when (this) {
        CyclePhase.PHASE_MENSTRUAL -> "Menstrual"
        CyclePhase.PHASE_FERTILE -> "Fertile"
        CyclePhase.PHASE_LUTEAL -> "Luteal"
        CyclePhase.PHASE_UNKNOWN -> "Unknown"
    }

    private companion object {
        const val MIN_PHASE_VALUE = 1f
        const val MAX_PHASE_VALUE = 3f
    }
}
