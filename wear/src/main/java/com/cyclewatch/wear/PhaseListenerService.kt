package com.cyclewatch.wear

import android.content.ComponentName
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.cyclewatch.shared.CyclePhase
import com.cyclewatch.shared.PhaseSyncContract
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the cycle-phase Data Layer item pushed by the phone app's
 * PhaseSyncService, stores it locally, and asks the system to refresh the
 * cycle-phase complication on any watch face showing it.
 */
class PhaseListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PhaseSyncContract.PATH_CYCLE_PHASE) continue

            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            val phaseId = dataMap.getInt(
                PhaseSyncContract.KEY_PHASE_ID,
                CyclePhase.PHASE_UNKNOWN.id,
            )
            PhaseRepository(this).savePhase(
                phaseId = phaseId,
                updatedAtMillis = dataMap.getLong(PhaseSyncContract.KEY_UPDATED_AT),
            )
            Log.d(TAG, "Received phase id=$phaseId, requesting complication update")

            ComplicationDataSourceUpdateRequester.create(
                context = this,
                complicationDataSourceComponent = ComponentName(
                    this,
                    CyclePhaseComplicationService::class.java,
                ),
            ).requestUpdateAll()
        }
    }

    private companion object {
        const val TAG = "PhaseListenerService"
    }
}
