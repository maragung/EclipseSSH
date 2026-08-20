package dev.eclipse.ssh.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN proxyType TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN proxyJump TEXT")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN socksHost TEXT")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN socksPort INTEGER NOT NULL DEFAULT 1080")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN hostId TEXT")
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN remotePath TEXT")
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN localUri TEXT")
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN transferredBytes INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN totalBytes INTEGER")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN socksUsername TEXT")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN socksPassword TEXT")
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN scheduledAt INTEGER")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN repeatMinutes INTEGER")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN accentColor INTEGER")
        }
    }

    /**
     * Per-host connect timeout and keep-alive.
     *
     * connectTimeoutSeconds is NOT NULL with a default so existing rows land on the value the engine
     * used to hard-code, leaving every upgraded profile behaving exactly as it did before.
     * keepAliveSeconds stays nullable on purpose: null means "follow the global setting", which is
     * what every pre-existing host was already doing.
     */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN connectTimeoutSeconds INTEGER NOT NULL DEFAULT 15")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN keepAliveSeconds INTEGER")
        }
    }

    /**
     * Per-host "Auto Login SFTP".
     *
     * DEFAULT 1, not 0. Every profile that already exists was connecting with an SFTP login straight
     * afterwards — the connect path listed the remote home directory unconditionally — so defaulting
     * the column to off would silently take the Files tab away from every upgraded host and read as a
     * regression rather than as a new setting. Opting out is a decision the user makes per host.
     */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN autoLoginSftp INTEGER NOT NULL DEFAULT 1")
        }
    }
}
