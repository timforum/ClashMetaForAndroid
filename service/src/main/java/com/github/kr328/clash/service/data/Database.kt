package com.github.kr328.clash.service.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.service.data.migrations.LEGACY_MIGRATION
import com.github.kr328.clash.service.data.migrations.MIGRATIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import androidx.room.Database as DB

@DB(
    version = 2,
    entities = [Imported::class, Pending::class, Selection::class],
    exportSchema = false,
)
abstract class Database : RoomDatabase() {
    abstract fun openImportedDao(): ImportedDao
    abstract fun openPendingDao(): PendingDao
    abstract fun openSelectionProxyDao(): SelectionDao

    companion object {
        val database: Database
            @Synchronized get() {
                return softDatabase.get() ?: open(Global.application).apply {
                    softDatabase = SoftReference(this)
                }
            }

        private var softDatabase: SoftReference<Database?> = SoftReference(null)

        /**
         * Drops the open connection so the next lookup reads whatever is on
         * disk.
         *
         * A restore replaces profiles.db underneath a process that has held it
         * open since the app started; without this the restored rows would stay
         * invisible for the rest of that process's life. The reference is
         * cleared under the same lock the getter takes, so a concurrent reader
         * cannot be handed a database that is already closing.
         */
        @Synchronized
        fun reset() {
            val stale = softDatabase.get()

            softDatabase = SoftReference(null)
            stale?.close()
        }

        private fun open(context: Context): Database {
            return Room.databaseBuilder(
                context.applicationContext,
                Database::class.java,
                "profiles"
            ).addMigrations(*MIGRATIONS).build()
        }

        init {
            Global.launch(Dispatchers.IO) {
                LEGACY_MIGRATION(Global.application)
            }
        }
    }
}
