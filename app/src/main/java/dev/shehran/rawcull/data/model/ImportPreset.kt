package dev.shehran.rawcull.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class StructureMode {
    DATE,
    CUSTOM,
    PROJECT,
    FLAT,
}

/** A saved destination: where an import goes and how its folders are named. */
@Entity(
    tableName = "import_presets",
    foreignKeys = [
        ForeignKey(
            entity = LibraryLocation::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("locationId")],
)
data class ImportPreset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val locationId: Long? = null,
    val structureMode: StructureMode = StructureMode.DATE,
    val datePattern: String = DEFAULT_DATE_PATTERN,
    val customPattern: String = DEFAULT_CUSTOM_PATTERN,
    val projectName: String = "",
) {
    val isStandard: Boolean get() = name == STANDARD_NAME

    /** Same destination settings, whatever the row is called. */
    fun sameSettingsAs(other: ImportPreset): Boolean =
        locationId == other.locationId && structureMode == other.structureMode &&
            datePattern == other.datePattern && customPattern == other.customPattern &&
            projectName == other.projectName

    companion object {
        const val STANDARD_NAME = "Standard"
        const val DEFAULT_DATE_PATTERN = "{year}/{year}-{month}-{day}"
        const val DEFAULT_CUSTOM_PATTERN = "{year}/{month}/{day}"

        /** The four date folder formats on offer. */
        val DATE_PATTERNS = listOf(
            DEFAULT_DATE_PATTERN,
            "{year}/{month}/{day}",
            "{year}-{month}-{day}",
            "{year}/{year}-{month}",
        )
    }
}
