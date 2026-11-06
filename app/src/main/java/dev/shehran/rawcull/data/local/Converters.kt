package dev.shehran.rawcull.data.local

import androidx.room.TypeConverter
import dev.shehran.rawcull.data.model.ColorLabel
import dev.shehran.rawcull.data.model.CullStatus
import dev.shehran.rawcull.data.model.PairMode
import dev.shehran.rawcull.data.model.PhotoFormat
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
