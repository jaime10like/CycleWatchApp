package com.cyclewatch.companion.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cyclewatch.companion.health.SymptomAnalyzer
import com.cyclewatch.shared.CyclePhase
import com.cyclewatch.shared.PhaseSyncContract
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Sends the analyzed cycle phase to the paired Wear OS device as a Data Layer
 * item, where [com.cyclewatch.shared.PhaseSyncContract.PATH_CYCLE_PHASE] is
 * picked up by the watch app's listener and surfaced in its complication.
 *
 * Start it via [start] with a phase that was just analyzed (e.g. from
 * MainActivity after a refresh), or with no phase to have the service run
 * [SymptomAnalyzer] itself — useful when triggered by WorkManager or an alarm.
 */
class PhaseSyncService : LifecycleService() {

    private val analyzer by lazy {
        SymptomAnalyzer(HealthConnectClient.getOrCreate(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val explicitPhaseId = intent?.getIntExtra(EXTRA_PHASE_ID, NO_PHASE) ?: NO_PHASE

        lifecycleScope.launch {
            try {
                val phase = if (explicitPhaseId == NO_PHASE) {
                    analyzePhase()
                } else {
                    CyclePhase.fromId(explicitPhaseId)
                }
                phase?.let { sendPhaseToWatch(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync cycle phase to wearable", e)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** Runs the analyzer, or returns null when permissions aren't granted. */
    private suspend fun analyzePhase(): CyclePhase? =
        if (analyzer.hasAllPermissions()) analyzer.determineCurrentPhase() else null

    private suspend fun sendPhaseToWatch(phase: CyclePhase) {
        val request = PutDataMapRequest.create(PhaseSyncContract.PATH_CYCLE_PHASE).apply {
            dataMap.putInt(PhaseSyncContract.KEY_PHASE_ID, phase.id)
            // The Data Layer only delivers items whose bytes changed, so the
            // timestamp guarantees every analysis run reaches the watch even
            // when the phase itself is unchanged.
            dataMap.putLong(PhaseSyncContract.KEY_UPDATED_AT, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(this).putDataItem(request).await()
        Log.d(TAG, "Synced ${phase.name} (id=${phase.id}) to wearable")
    }

    companion object {
        private const val TAG = "PhaseSyncService"
        private const val EXTRA_PHASE_ID = "extra_phase_id"
        private const val NO_PHASE = -1

        /**
         * Starts a one-shot sync. Pass [phase] when the caller has already
         * analyzed it; omit to let the service analyze before sending.
         */
        fun start(context: Context, phase: CyclePhase? = null) {
            val intent = Intent(context, PhaseSyncService::class.java)
            phase?.let { intent.putExtra(EXTRA_PHASE_ID, it.id) }
            context.startService(intent)
        }
    }
}
