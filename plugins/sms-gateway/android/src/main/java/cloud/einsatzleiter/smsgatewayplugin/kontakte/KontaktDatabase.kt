package cloud.einsatzleiter.smsgatewayplugin.kontakte

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        KontaktEntity::class,
        TelefonEntity::class,
        ZuordnungEntity::class,
        KontaktSyncStatusEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KontaktDatabase : RoomDatabase() {
    abstract fun kontaktDao(): KontaktDao
    abstract fun syncStatusDao(): KontaktSyncStatusDao

    companion object {
        @Volatile private var instance: KontaktDatabase? = null

        fun get(context: Context): KontaktDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KontaktDatabase::class.java,
                "kontakte-offline.db",
            ).build().also { instance = it }
        }
    }
}
