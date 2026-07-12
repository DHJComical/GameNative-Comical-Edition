package app.gamenative.db.converters

import androidx.room.TypeConverter
import app.gamenative.data.DownloadStore
import app.gamenative.data.StoreDownloadOperation
import app.gamenative.data.StoreDownloadState

class StoreDownloadTaskConverter {

    @TypeConverter
    fun toDownloadStore(value: String): DownloadStore =
        DownloadStore.entries.single { it.persistedValue == value }

    @TypeConverter
    fun fromDownloadStore(store: DownloadStore): String = store.persistedValue

    @TypeConverter
    fun toDownloadOperation(value: String): StoreDownloadOperation =
        StoreDownloadOperation.entries.single { it.persistedValue == value }

    @TypeConverter
    fun fromDownloadOperation(operation: StoreDownloadOperation): String = operation.persistedValue

    @TypeConverter
    fun toDownloadState(value: String): StoreDownloadState =
        StoreDownloadState.entries.single { it.persistedValue == value }

    @TypeConverter
    fun fromDownloadState(state: StoreDownloadState): String = state.persistedValue
}
