/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Sun Mar 10 11:37:11 CET 2024                                                 */


package tk.glucodata

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission.Companion.getReadPermission
import androidx.health.connect.client.permission.HealthPermission.Companion.getWritePermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Device.Companion.TYPE_UNKNOWN
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tk.glucodata.HealthActivityImportPolicy.ImportedRow
import tk.glucodata.HealthActivityImportPolicy.Interval
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalIntensity
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.Log.doLog
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class HealthConnection(private val client: HealthConnectClient) {
	var active= AtomicBoolean(false)
    private val activityImportActive = AtomicBoolean(false)


  private var scope = CoroutineScope(Dispatchers.IO+SupervisorJob())
//TODO: test not already active
@OptIn(ExperimentalStdlibApi::class)
private  fun writeAllIns(sensorptr:Long, sensorName:String) {
    // 0: the driver has no native record (yet), so there is nothing to export
    if (sensorptr == 0L) {
        if(doLog) {Log.i(LOG_ID, "writeAll $sensorName: no sensorptr");}
        return
    }
    if (active.getAndSet(true)) {
        if(doLog) {Log.i(LOG_ID, "writeAll already active");}
        return
    }
    scope.launch {
        try {
            Log.i(LOG_ID, "writeAll 0x${sensorptr.toHexString()} $sensorName")
            // A reading never brings up the permission dialog: it only notices a grant made since.
            if (!hasGlucosePermission) {
                refreshPermissions()
                if (!hasGlucosePermission) {
                    if(doLog) {Log.i(LOG_ID, "No permission");}
                    return@launch
                }
            }
            val endstart = Natives.healthConnectfromSensorptr(sensorptr)

            val end = endstart ushr 16
            var start = endstart and 0xFFFF
           if(start==end)
               return@launch
            Log.i(LOG_ID,"endstart=$endstart start=$start end=$end len=${end-start}")
            val device=Device(TYPE_UNKNOWN,"Libre", sensorName)
            while (start < end) {
                val take = min(end - start, 500)
                Log.i(LOG_ID,"start=$start take=$take")
                // Empty slots are left out, so a batch can hold nothing to send.
                val records = GlucoseList(device, sensorName, sensorptr, start, take).records()
                if (records.isNotEmpty()) {
                    val siz = client.insertRecords(records).recordIdsList.size
                    if (siz == 0) {
                        Log.e(LOG_ID, "insertRecors $siz==0")
                        return@launch
                      }
                    Log.i(LOG_ID,"siz=$siz")
                }
                if (!Natives.healthConnectWritten(sensorptr, start, start + take)) {
                    // A late reading moved the cursor back below this batch: the next export starts there.
                    Log.i(LOG_ID, "cursor moved back while writing from $start")
                    return@launch
                }
                start += take;
            }
        } catch (se: SecurityException) {
            // Revoked in Health Connect: look again before the next write.
            hasGlucosePermission = false
            Log.stack(LOG_ID, "writeAll", se);
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "writeAll", th);
        } finally {
            active.set(false)
        }
    }
}

/** What Health Connect has granted, read without asking for anything. */
private suspend fun refreshPermissions() {
        val granted = client.permissionController.getGrantedPermissions()
        Log.i(LOG_ID,"refreshPermissions granted=$granted")
        hasGlucosePermission = granted.containsAll(GLUCOSE_PERMISSIONS)
        hasActivityPermission = granted.containsAll(ACTIVITY_PERMISSIONS)
    }

/**
 * Brings up the dialog for what a switched-on feature lacks, each feature's own permissions
 * only: once per process, or again when the user has just turned that feature's switch on.
 * Serialised, so that two callers at start-up make one dialog for both features, not two.
 */
private suspend fun requestMissing(act: MainActivity?, glucoseTurnedOn: Boolean, activityTurnedOn: Boolean): Unit = permissionLock.withLock {
        refreshPermissions()
        val askGlucose = HealthConnectPermissionPolicy.shouldRequest(
            switchOn = glucoseExportOn(), granted = hasGlucosePermission,
            userTurnedOn = glucoseTurnedOn, askedThisProcess = glucoseAsked.get())
        val askActivity = HealthConnectPermissionPolicy.shouldRequest(
            switchOn = activityImportOn(), granted = hasActivityPermission,
            userTurnedOn = activityTurnedOn, askedThisProcess = activityAsked.get())
        if (!askGlucose && !askActivity)
            return@withLock
        val request = act?.permHealth
        if (request == null) {
            Log.i(LOG_ID,"no act?.permHealth, not requested")
            return@withLock
        }
        val wanted = HashSet<String>()
        if (askGlucose) {
            glucoseAsked.set(true)
            wanted += GLUCOSE_PERMISSIONS
        }
        if (askActivity) {
            activityAsked.set(true)
            wanted += ACTIVITY_PERMISSIONS
        }
        withContext(Dispatchers.Main) {
            request.request(wanted)
        }
        Log.i(LOG_ID,"requested $wanted")
    }

/**
 * [userTurnedOn]: the import switch was just turned on, which may bring up the dialog again.
 * A run that stops for lack of permission is not a run: only a finished one sets
 * [lastActivityImportMillis], which spaces the foreground runs.
 */
private fun importActivityIns(daysBack: Int, userTurnedOn: Boolean) {
    if (activityImportActive.getAndSet(true)) {
        if(doLog) {Log.i(LOG_ID, "activity import already active");}
        return
    }
    scope.launch {
        try {
            if (!hasActivityPermission) {
                requestMissing(MainActivity.thisone, glucoseTurnedOn = false, activityTurnedOn = userTurnedOn)
                if (!hasActivityPermission) {
                    if(doLog) {Log.i(LOG_ID, "activity import: no permission");}
                    return@launch
                }
            }
            val now = Instant.now()
            val start = now.minusSeconds(daysBack.coerceIn(1, 30) * 24L * 60L * 60L)
            val range = TimeRangeFilter.between(start, now)
            val repository = JournalRepository()
            var imported = 0

            val sessions = HealthActivityImportPolicy.readAllPages { token ->
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = range,
                        pageToken = token
                    )
                ).let { it.records to it.pageToken }
            }
            val sessionIntervals = sessions.map { Interval(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
            sessions.forEach { session ->
                val startMillis = session.startTime.toEpochMilli()
                val endMillis = session.endTime.toEpochMilli()
                val durationMinutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1)
                repository.upsertEntry(
                    JournalEntryInput(
                        timestamp = startMillis,
                        type = JournalEntryType.ACTIVITY,
                        title = session.title?.takeIf { it.isNotBlank() } ?: "Health activity",
                        note = session.notes,
                        durationMinutes = durationMinutes,
                        intensity = durationMinutes.inferredHealthIntensity(),
                        source = JournalEntrySource.HEALTH_CONNECT,
                        sourceRecordId = session.stableHealthRecordId("exercise", startMillis, endMillis)
                    )
                )
                imported++
            }

            val steps = HealthActivityImportPolicy.readAllPages { token ->
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = range,
                        pageToken = token
                    )
                ).let { it.records to it.pageToken }
            }
            // Every step record's interval by its name, for the clean-up below.
            val stepIntervals = HashMap<String, Interval>()
            steps.forEach { record ->
                    val startMillis = record.startTime.toEpochMilli()
                    val endMillis = record.endTime.toEpochMilli()
                    val interval = Interval(startMillis, endMillis)
                    val sourceRecordId = record.stableHealthRecordId("steps", startMillis, endMillis)
                    stepIntervals[sourceRecordId] = interval
                    // Steps taken during an exercise session are that session's.
                    if (!HealthActivityImportPolicy.importsSteps(record.count, interval, sessionIntervals))
                        return@forEach
                    val durationMinutes = ((endMillis - startMillis) / 60_000L).toInt().coerceAtLeast(1)
                    repository.upsertEntry(
                        JournalEntryInput(
                            timestamp = startMillis,
                            type = JournalEntryType.ACTIVITY,
                            title = "Steps",
                            note = "${record.count} steps",
                            amount = record.count.toFloat(),
                            durationMinutes = durationMinutes,
                            intensity = record.count.inferredStepIntensity(durationMinutes),
                            source = JournalEntrySource.HEALTH_CONNECT,
                            sourceRecordId = sourceRecordId
                        )
                    )
                    imported++
                }
            // Step rows an earlier import wrote next to the session they belong to.
            val rows = repository.entriesFromSourceBetween(
                JournalEntrySource.HEALTH_CONNECT, start.toEpochMilli(), now.toEpochMilli()
            ).map { ImportedRow(it.id, it.sourceRecordId, it.timestamp, it.durationMinutes) }
            val doubled = HealthActivityImportPolicy.stepRowsToRemove(rows, sessionIntervals, stepIntervals)
            doubled.forEach { repository.deleteEntry(it) }
            lastActivityImportMillis = System.currentTimeMillis()
            Log.i(LOG_ID, "Imported $imported Health Connect activity records, removed ${doubled.size} steps within sessions")
        } catch (se: SecurityException) {
            hasActivityPermission = false
            Log.stack(LOG_ID, "importActivity", se)
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "importActivity", th)
        } finally {
            activityImportActive.set(false)
        }
    }
}


companion object {
    // Each feature asks for its own permissions only.
    val GLUCOSE_PERMISSIONS =
        if(Build.VERSION.SDK_INT < 28) setOf("") else
            setOf(
                getWritePermission(
                    BloodGlucoseRecord::class
                )
            )
    val ACTIVITY_PERMISSIONS =
        if(Build.VERSION.SDK_INT < 28) setOf("") else
            setOf(
                getReadPermission(ExerciseSessionRecord::class),
                getReadPermission(StepsRecord::class)
            )
    @Volatile
    var hasGlucosePermission = false
    @Volatile
    var hasActivityPermission = false
    // Whether this process has brought up the dialog for a feature already.
    private val glucoseAsked = AtomicBoolean(false)
    private val activityAsked = AtomicBoolean(false)
    private val permissionLock = Mutex()
    // When the activity import last finished: the foreground runs keep 15 minutes from it.
    @Volatile
    private var lastActivityImportMillis = 0L
    private const val ACTIVITY_DAYS_BACK = 14
    private const val LOG_ID = "HealthConnection"
   @Volatile
        private var instance:HealthConnection? = null

    private fun glucoseExportOn(): Boolean = Natives.gethealthConnect()

    /**
     * The journal's "Import Health Connect activity" switch, which the journal switch hides
     * (DashboardViewModel's JOURNAL_HEALTH_CONNECT_ACTIVITY_KEY and dashboard_journal_enabled).
     */
    private fun activityImportOn(): Boolean {
        val prefs = Applic.app.getSharedPreferences("tk.glucodata_preferences", Context.MODE_PRIVATE)
        return prefs.getBoolean("dashboard_journal_enabled", true) &&
            prefs.getBoolean("dashboard_journal_health_connect_activity_enabled", false)
    }

    private fun googleplay(context: ComponentActivity) {
        val playstr =
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect://onboarding"
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage("com.android.vending")
        intent.setData(Uri.parse(playstr))
        intent.putExtra("overlay", true)
        intent.putExtra("callerId", context.packageName)
        context.startActivity(intent)
    }
    /** The app started with the export switch on: asks for what is missing, once per process. */
   fun init(context:MainActivity)  {
	       GlobalScope.launch {
		  (instance ?: susinit(context, openStore = true))
		      ?.requestMissing(context, glucoseTurnedOn = false, activityTurnedOn = false)
		}
   }

    /** The export switch was just turned on: asks for its permission if it is missing. */
    fun glucoseSwitchedOn(context: MainActivity) {
        GlobalScope.launch {
            (instance ?: susinit(context, openStore = true))
                ?.requestMissing(context, glucoseTurnedOn = true, activityTurnedOn = false)
        }
    }

/** [openStore]: on a phone whose Health Connect needs an update, send the user to the Play Store. */
private fun susinit(context: MainActivity, openStore: Boolean): HealthConnection? {

           if (Build.VERSION.SDK_INT < 28) {
               return null

           }
           return try {
               var ret = HealthConnectClient.getSdkStatus(context)
               when (ret) {
                   HealthConnectClient.SDK_AVAILABLE -> {
                       Log.i(LOG_ID, "SDK_AVAILABLE")
                       val health = synchronized(this) {
                           instance ?: HealthConnection(HealthConnectClient.getOrCreate(context)).also { instance = it }
                       }
                       Log.i(LOG_ID, "after getOrCreate")
		       MainActivity.tryHealth=0;
                       health
                   }

                   HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                       Log.i(LOG_ID, "SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED")
                       if (openStore) {
                           googleplay(context)
                           Log.i(LOG_ID, "After googleplay")
                       }
                       null
                   }

                   HealthConnectClient.SDK_UNAVAILABLE -> {
                       Log.i(LOG_ID, "SDK_UNAVAILABLE")
                       null
                   }

                   else -> {
                       Log.e(LOG_ID, "unknown return value from getSdkStatus(context)")
                       null
                   }
               }
           } catch (th: Throwable) {
               Log.stack(LOG_ID, "exception ", th)
               null
           }
   }

fun writeAll(sensorptr:Long,sensorname:String) {
	instance?.writeAllIns(sensorptr,sensorname);
    }

    /** The import switch was just turned on: imports now, asking for permission if it is missing. */
    fun importActivity(daysBack: Int = ACTIVITY_DAYS_BACK) {
        instance?.importActivityIns(daysBack, userTurnedOn = true) ?: MainActivity.thisone?.let { context ->
            GlobalScope.launch {
                susinit(context, openStore = true)?.importActivityIns(daysBack, userTurnedOn = true)
            }
        }
    }

    /** The app came to the foreground: imports again while the switch is on, at most every 15 minutes. */
    fun onForeground(context: MainActivity) {
        if (Build.VERSION.SDK_INT < 28 || !activityImportOn())
            return
        if (!HealthActivityImportPolicy.foregroundImportDue(System.currentTimeMillis(), lastActivityImportMillis))
            return
        GlobalScope.launch {
            // Not to the Play Store from here: opening the app should not keep doing that.
            (instance ?: susinit(context, openStore = false))
                ?.importActivityIns(ACTIVITY_DAYS_BACK, userTurnedOn = false)
        }
    }

    /** The dialog's answer: [granted] is what it granted of what it asked for. */
    fun onPermissionResult(granted: Set<String>) {
        if (granted.containsAll(GLUCOSE_PERMISSIONS))
            hasGlucosePermission = true
        if (granted.containsAll(ACTIVITY_PERMISSIONS)) {
            hasActivityPermission = true
            if (activityImportOn())
                instance?.importActivityIns(ACTIVITY_DAYS_BACK, userTurnedOn = false)
        }
    }
    public fun stop() {
        instance?.scope?.cancel()
        instance = null
        }
    }
}

private fun Int.inferredHealthIntensity(): JournalIntensity {
    return when {
        this >= 75 -> JournalIntensity.INTENSE
        this >= 25 -> JournalIntensity.MODERATE
        else -> JournalIntensity.LIGHT
    }
}

private fun Long.inferredStepIntensity(durationMinutes: Int): JournalIntensity {
    val stepsPerMinute = this.toFloat() / durationMinutes.coerceAtLeast(1)
    return when {
        stepsPerMinute >= 110f -> JournalIntensity.INTENSE
        stepsPerMinute >= 70f -> JournalIntensity.MODERATE
        else -> JournalIntensity.LIGHT
    }
}

private fun androidx.health.connect.client.records.Record.stableHealthRecordId(
    type: String,
    startMillis: Long,
    endMillis: Long
): String {
    val id = metadata.id.takeIf { it.isNotBlank() }
    return "health_connect:$type:${id ?: "$startMillis:$endMillis"}"
}
