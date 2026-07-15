package com.cyclewatch.companion.health

import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.reflect.KClass

/** The cycle phase inferred from recent Health Connect symptom data. */
enum class CyclePhase {
    PHASE_MENSTRUAL,
    PHASE_FERTILE,
    PHASE_LUTEAL,

    /** Not enough data in the lookback window to match any rule. */
    PHASE_UNKNOWN,
}

/**
 * Reads cycle-related symptom records from Health Connect and applies a simple
 * rule set to estimate the user's current cycle phase.
 *
 * Rules, evaluated in order:
 *  1. A [MenstruationFlowRecord] logged today -> [CyclePhase.PHASE_MENSTRUAL].
 *  2. A [CervicalMucusRecord] in the last 5 days with egg-white or wet/slippery
 *     quality -> [CyclePhase.PHASE_FERTILE].
 *  3. A sustained upward basal-body-temperature shift over the last 3 days
 *     versus the preceding baseline, with dry/dry-ish mucus ->
 *     [CyclePhase.PHASE_LUTEAL].
 *  4. Otherwise -> [CyclePhase.PHASE_UNKNOWN].
 *
 * Usage:
 * ```
 * val client = HealthConnectClient.getOrCreate(context)
 * val analyzer = SymptomAnalyzer(client)
 *
 * // In your Activity/Fragment, before analyzing:
 * val launcher = registerForActivityResult(SymptomAnalyzer.permissionRequestContract()) { granted ->
 *     if (granted.containsAll(SymptomAnalyzer.REQUIRED_PERMISSIONS)) { /* proceed */ }
 * }
 * if (!analyzer.hasAllPermissions()) launcher.launch(SymptomAnalyzer.REQUIRED_PERMISSIONS)
 *
 * val phase = analyzer.determineCurrentPhase()
 * ```
 */
class SymptomAnalyzer(
    private val healthConnectClient: HealthConnectClient,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * Returns true if all read permissions this analyzer needs have been granted.
     */
    suspend fun hasAllPermissions(): Boolean =
        healthConnectClient.permissionController
            .getGrantedPermissions()
            .containsAll(REQUIRED_PERMISSIONS)

    /**
     * Queries the last [LOOKBACK_DAYS] days of data and applies the phase rules.
     *
     * @throws IllegalStateException if the required Health Connect read
     *   permissions have not been granted; call [hasAllPermissions] and request
     *   [REQUIRED_PERMISSIONS] first.
     */
    suspend fun determineCurrentPhase(): CyclePhase {
        check(hasAllPermissions()) {
            "Missing Health Connect permissions. Request REQUIRED_PERMISSIONS before analyzing."
        }

        val now = ZonedDateTime.now(clock)
        val lookback = TimeRangeFilter.between(
            now.minusDays(LOOKBACK_DAYS).toInstant(),
            now.toInstant(),
        )

        val flowRecords = readAllRecords(MenstruationFlowRecord::class, lookback)
        if (isMenstruatingToday(flowRecords, now.toLocalDate())) {
            return CyclePhase.PHASE_MENSTRUAL
        }

        val mucusRecords = readAllRecords(CervicalMucusRecord::class, lookback)
        val fertileWindowStart = now.minusDays(FERTILE_WINDOW_DAYS).toInstant()
        val recentMucus = mucusRecords.filter { it.time >= fertileWindowStart }
        if (recentMucus.any { it.isFertileQuality() }) {
            return CyclePhase.PHASE_FERTILE
        }

        val temperatureRecords = readAllRecords(BasalBodyTemperatureRecord::class, lookback)
        if (hasSustainedTemperatureShift(temperatureRecords, now) && isMucusDryish(recentMucus)) {
            return CyclePhase.PHASE_LUTEAL
        }

        return CyclePhase.PHASE_UNKNOWN
    }

    /** True if any menstruation flow was logged on today's calendar date. */
    private fun isMenstruatingToday(
        records: List<MenstruationFlowRecord>,
        today: LocalDate,
    ): Boolean = records.any { record ->
        val recordDate = record.zoneOffset
            ?.let { record.time.atOffset(it).toLocalDate() }
            ?: record.time.atZone(clock.zone).toLocalDate()
        recordDate == today
    }

    /**
     * A sustained upward shift means: at least [MIN_SHIFT_READINGS] readings in
     * the last [TEMP_SHIFT_DAYS] days, every one of them at least
     * [TEMP_SHIFT_THRESHOLD_CELSIUS] above the average of the baseline readings
     * (readings from the rest of the lookback window).
     */
    private fun hasSustainedTemperatureShift(
        records: List<BasalBodyTemperatureRecord>,
        now: ZonedDateTime,
    ): Boolean {
        val shiftWindowStart = now.minusDays(TEMP_SHIFT_DAYS).toInstant()
        val (recent, baseline) = records.partition { it.time >= shiftWindowStart }
        if (recent.size < MIN_SHIFT_READINGS || baseline.size < MIN_BASELINE_READINGS) {
            return false
        }
        val baselineCelsius = baseline.map { it.temperature.inCelsius }.average()
        return recent.all {
            it.temperature.inCelsius >= baselineCelsius + TEMP_SHIFT_THRESHOLD_CELSIUS
        }
    }

    /**
     * Health Connect models mucus quality via [CervicalMucusRecord.appearance]:
     * "egg white" maps to APPEARANCE_EGG_WHITE and "wet/slippery" to
     * APPEARANCE_WATERY (the sensation field encodes amount, not wetness).
     */
    private fun CervicalMucusRecord.isFertileQuality(): Boolean =
        appearance == CervicalMucusRecord.APPEARANCE_EGG_WHITE ||
            appearance == CervicalMucusRecord.APPEARANCE_WATERY

    /**
     * Dry/dry-ish means the most recent observation is dry or sticky. With no
     * recent observations we treat mucus as dry-ish, since the fertile-quality
     * rule has already ruled out wet mucus in this window.
     */
    private fun isMucusDryish(recentMucus: List<CervicalMucusRecord>): Boolean {
        val latest = recentMucus.maxByOrNull { it.time } ?: return true
        return latest.appearance == CervicalMucusRecord.APPEARANCE_DRY ||
            latest.appearance == CervicalMucusRecord.APPEARANCE_STICKY
    }

    /** Reads every record of [recordType] in [timeRangeFilter], following pagination. */
    private suspend fun <T : Record> readAllRecords(
        recordType: KClass<T>,
        timeRangeFilter: TimeRangeFilter,
    ): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = recordType,
                    timeRangeFilter = timeRangeFilter,
                    pageToken = pageToken,
                )
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    companion object {
        /** Health Connect read permissions this analyzer requires. */
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(BasalBodyTemperatureRecord::class),
            HealthPermission.getReadPermission(CervicalMucusRecord::class),
            HealthPermission.getReadPermission(MenstruationFlowRecord::class),
        )

        /** Contract for launching the Health Connect permission request UI. */
        fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()

        private const val LOOKBACK_DAYS = 14L
        private const val FERTILE_WINDOW_DAYS = 5L
        private const val TEMP_SHIFT_DAYS = 3L
        private const val TEMP_SHIFT_THRESHOLD_CELSIUS = 0.2
        private const val MIN_BASELINE_READINGS = 3
        private const val MIN_SHIFT_READINGS = 3
    }
}
