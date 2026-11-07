package dev.shehran.rawcull.data.local

import androidx.room.TypeConverter
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.ImportItemRole
import dev.shehran.rawcull.data.model.ImportItemState
import dev.shehran.rawcull.data.model.ImportJobStatus
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
import dev.shehran.rawcull.data.model.StructureMode
import dev.shehran.rawcull.data.model.VerifyMode
import dev.shehran.rawcull.data.model.XmpSyncStatus
import org.json.JSONArray

class Converters {
    @TypeConverter
    fun fromCullStatus(status: CullStatus): String = status.name

    @TypeConverter
    fun toCullStatus(value: String): CullStatus = runCatching {
        CullStatus.valueOf(value)
    }.getOrDefault(CullStatus.UNFLAGGED)

    @TypeConverter
    fun fromColorLabel(label: ColorLabel): String = label.name

    @TypeConverter
    fun toColorLabel(value: String): ColorLabel = runCatching {
        ColorLabel.valueOf(value)
    }.getOrDefault(ColorLabel.NONE)

    @TypeConverter
    fun fromPhotoFormat(format: PhotoFormat): String = format.name

    @TypeConverter
    fun toPhotoFormat(value: String): PhotoFormat = runCatching {
        PhotoFormat.valueOf(value)
    }.getOrDefault(PhotoFormat.RAW_OTHER)

    @TypeConverter
    fun fromPairMode(mode: PairMode): String = mode.name

    @TypeConverter
    fun toPairMode(value: String): PairMode = runCatching {
        PairMode.valueOf(value)
    }.getOrDefault(PairMode.RAW_ONLY)

    @TypeConverter
    fun fromXmpSyncStatus(status: XmpSyncStatus): String = status.name

    @TypeConverter
    fun toXmpSyncStatus(value: String): XmpSyncStatus = runCatching {
        XmpSyncStatus.valueOf(value)
    }.getOrDefault(XmpSyncStatus.SYNCED)

    @TypeConverter
    fun fromStructureMode(mode: StructureMode): String = mode.name

    @TypeConverter
    fun toStructureMode(value: String): StructureMode = runCatching {
        StructureMode.valueOf(value)
    }.getOrDefault(StructureMode.DATE)

    @TypeConverter
    fun fromImportJobStatus(status: ImportJobStatus): String = status.name

    /** An unknown status can't be resumed safely, so it reads as a job that needs the user. */
    @TypeConverter
    fun toImportJobStatus(value: String): ImportJobStatus = runCatching {
        ImportJobStatus.valueOf(value)
    }.getOrDefault(ImportJobStatus.PAUSED)

    @TypeConverter
    fun fromVerifyMode(mode: VerifyMode): String = mode.name

    @TypeConverter
    fun toVerifyMode(value: String): VerifyMode = runCatching {
        VerifyMode.valueOf(value)
    }.getOrDefault(VerifyMode.FULL)

    @TypeConverter
    fun fromImportItemState(state: ImportItemState): String = state.name

    @TypeConverter
    fun toImportItemState(value: String): ImportItemState = runCatching {
        ImportItemState.valueOf(value)
    }.getOrDefault(ImportItemState.QUEUED)

    @TypeConverter
    fun fromImportItemRole(role: ImportItemRole): String = role.name

    @TypeConverter
    fun toImportItemRole(value: String): ImportItemRole = runCatching {
        ImportItemRole.valueOf(value)
    }.getOrDefault(ImportItemRole.PRIMARY)

    @TypeConverter
    fun fromTagsList(tags: List<String>): String {
        val array = JSONArray()
        tags.forEach { array.put(it) }
        return array.toString()
    }

    @TypeConverter
    fun toTagsList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        }.getOrDefault(emptyList())
    }
}
