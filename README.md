# CycleWatchApp

Android companion app for Wear OS that reads cycle-related symptoms from
[Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect),
estimates the user's current cycle phase, and shows it as a watch face
complication.

## Modules

| Module | What it is |
| --- | --- |
| `:app` | Phone app: Health Connect analysis (`SymptomAnalyzer`), Compose UI, and `PhaseSyncService`, which pushes the phase to the watch over the Data Layer |
| `:wear` | Watch app: `PhaseListenerService` receives the phase, `CyclePhaseComplicationService` serves it as a `RANGED_VALUE` complication (1–3) |
| `:shared` | `CyclePhase` enum (with wire IDs) and `PhaseSyncContract` (Data Layer path/keys) used by both apps |

## Phone → watch sync

1. After each analysis, `PhaseSyncService` writes a Data Layer item at
   `/cycle-phase` containing `phase_id` (0 unknown, 1 menstrual, 2 fertile,
   3 luteal) and an `updated_at` timestamp (so identical phases still sync).
2. On the watch, `PhaseListenerService` stores the received ID and calls
   `ComplicationDataSourceUpdateRequester.requestUpdateAll()`.
3. `CyclePhaseComplicationService` renders it as a ranged value from 1 to 3
   with a text label; an unknown phase renders as "no data".

Both apps must be installed with the **same applicationId and signing key**
(`com.cyclewatch.companion`) or the Data Layer will not deliver between them —
the `:wear` module is already configured this way.

## SymptomAnalyzer

`app/src/main/java/com/cyclewatch/companion/health/SymptomAnalyzer.kt` reads the
last 14 days of `BasalBodyTemperatureRecord`, `CervicalMucusRecord`, and
`MenstruationFlowRecord` data and returns a `CyclePhase`:

| Rule (evaluated in order) | Result |
| --- | --- |
| Menstruation flow logged today | `PHASE_MENSTRUAL` |
| Egg-white or wet/watery cervical mucus in the last 5 days | `PHASE_FERTILE` |
| Sustained BBT rise (≥ 0.2 °C above the 14-day baseline for 3 days, ≥ 3 readings) with dry/sticky mucus | `PHASE_LUTEAL` |
| Otherwise | `PHASE_UNKNOWN` |

## Setup

### Gradle dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0-alpha10")
}
```

### AndroidManifest.xml

Declare the read permissions and make the app visible to Health Connect:

```xml
<manifest ...>
    <uses-permission android:name="android.permission.health.READ_BASAL_BODY_TEMPERATURE" />
    <uses-permission android:name="android.permission.health.READ_CERVICAL_MUCUS" />
    <uses-permission android:name="android.permission.health.READ_MENSTRUATION" />

    <queries>
        <package android:name="com.google.android.apps.healthdata" />
    </queries>

    <application ...>
        <!-- Required: shown when the user taps your app's privacy policy link
             in the Health Connect permission dialog. -->
        <activity android:name=".PermissionsRationaleActivity" android:exported="true">
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

### Usage

```kotlin
class MainActivity : ComponentActivity() {

    private val analyzer by lazy {
        SymptomAnalyzer(HealthConnectClient.getOrCreate(this))
    }

    private val permissionLauncher = registerForActivityResult(
        SymptomAnalyzer.permissionRequestContract()
    ) { granted ->
        if (granted.containsAll(SymptomAnalyzer.REQUIRED_PERMISSIONS)) {
            analyzePhase()
        }
    }

    private fun analyzePhase() {
        lifecycleScope.launch {
            if (!analyzer.hasAllPermissions()) {
                permissionLauncher.launch(SymptomAnalyzer.REQUIRED_PERMISSIONS)
                return@launch
            }
            val phase = analyzer.determineCurrentPhase()
            // e.g. sync `phase` to the Wear OS app via the Data Layer API
        }
    }
}
```

> Check `HealthConnectClient.getSdkStatus(context)` before calling
> `getOrCreate` — Health Connect may be unavailable or need an update on some
> devices.

> **Note:** phase detection here is a heuristic based on user-logged symptoms
> and is not a medical or contraceptive tool.
