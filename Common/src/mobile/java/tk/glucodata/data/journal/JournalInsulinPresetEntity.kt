package tk.glucodata.data.journal

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "journal_insulin_presets",
    indices = [Index(value = ["sortOrder"])]
)
data class JournalInsulinPresetEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val displayName: String,
    val onsetMinutes: Int,
    val durationMinutes: Int,
    val accentColor: Int,
    val curveJson: String,
    val isBuiltIn: Boolean,
    val isArchived: Boolean,
    val countsTowardIob: Boolean,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "1")
    val useForCalculation: Boolean = true,
    val curveProfileId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val curveModelVersion: Int = 0,
    @ColumnInfo(defaultValue = "'unverified'")
    val curveEvidence: String = JournalCurveEvidence.UNVERIFIED.storageValue,
    /**
     * Units the entry sheet's -/+ buttons move by: the dial step of the pen this insulin is in.
     * The column default is only what MIGRATION_32_33 adds the column with (then sets 0.5); a
     * row written by Room always carries its own step, [JournalInsulinDosing.DEFAULT_STEP] if unset.
     */
    @ColumnInfo(defaultValue = "1")
    val doseStep: Float = JournalInsulinDosing.DEFAULT_STEP,
    /** Filled in when this insulin is chosen in the entry sheet; null when none is set. */
    val defaultDose: Float? = null,
    /**
     * Times of day to be reminded of this dose, long-acting insulin only
     * ([JournalInsulinDosing.encodeReminderTimes]); empty when there are none.
     */
    @ColumnInfo(defaultValue = "''")
    val reminderTimes: String = ""
)
