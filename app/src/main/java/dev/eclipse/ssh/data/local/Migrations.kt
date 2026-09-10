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

    /**
     * The advanced per-host settings: compression, keep-alive, timeouts, reconnect policy, pty and
     * terminal geometry, authentication methods, algorithm set and host-key policy.
     *
     * Every default here is chosen to reproduce what the engine did *before* the column existed, so
     * an upgraded profile connects exactly as it did on the previous build and nothing changes until
     * the user opens the form:
     *
     *  - `compression` 0, because the client never offered zlib.
     *  - `keepAliveEnabled` 1 with `serverAliveCountMax` 3, which is the heartbeat that was always
     *    armed and the missed-reply limit that was hard-coded.
     *  - `authTimeoutSeconds` 30: authentication used to share the connect timeout, whose own default
     *    is 15, but that number was never *about* authentication - it bounded the socket. Thirty is
     *    Apache MINA's own `AUTH_TIMEOUT` default, so a host that has never been told otherwise keeps
     *    the timeout MINA was already applying internally.
     *  - `autoReconnect` 1, `maxReconnectAttempts` 5, `reconnectBackoffSeconds` 0 (inherit): the values the
     *    ladder used for every host.
     *  - `usePty` 1 and `terminalType` 'xterm-256color': the pty request was unconditional and the
     *    TERM string was hard-coded to exactly this.
     *  - `terminalColumns`/`terminalRows` 0, the "match the screen" sentinel, which is what the app
     *    has always done - the geometry came from the view.
     *  - `keyboardInteractiveAuth` 1, because the client offered MINA's full default method list.
     *  - `legacyAlgorithms` NULL, meaning "follow the global switch" - the only behaviour that existed.
     *  - `hostKeyPolicy` 'ASK', which is what the verifier did: emit a challenge and wait.
     */
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN compression INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN keepAliveEnabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN serverAliveCountMax INTEGER NOT NULL DEFAULT 3")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN authTimeoutSeconds INTEGER NOT NULL DEFAULT 30")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN autoReconnect INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN maxReconnectAttempts INTEGER NOT NULL DEFAULT 5")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN reconnectBackoffSeconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN usePty INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN terminalType TEXT NOT NULL DEFAULT 'xterm-256color'")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN terminalColumns INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN terminalRows INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN keyboardInteractiveAuth INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN legacyAlgorithms INTEGER")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN hostKeyPolicy TEXT NOT NULL DEFAULT 'ASK'")
        }
    }

    /**
     * The rest of the per-host settings: four algorithm lists, a startup command, an environment and
     * the saved port-forwarding rules.
     *
     * Same rule as [MIGRATION_11_12] - every default is what the engine did *before* the column
     * existed, so an upgraded row connects identically:
     *
     *  - `ciphers`, `kexAlgorithms`, `macs`, `hostKeyAlgorithms` NULL, meaning "whatever MINA
     *    negotiates". Nullable rather than an empty string because an empty *list* would be a real
     *    instruction - offer nothing, which no session can be built from - and NULL is the only value
     *    that can mean "this host has no opinion". Existing rows have none.
     *  - `startupCommand` and `environment` '', which is nothing to send: the app opened every shell
     *    with no typed command and no `env` requests.
     *  - `savedForwards` '', because forwards existed only for the life of a session and were never
     *    stored, so no host can have had one.
     *
     * No new table. The rules live in a text column in `ssh`'s own syntax, one per line, the way
     * `tags` already lives in this table - so a host and its tunnels are one row that one Save writes
     * atomically, one delete removes entirely, and one backup carries without a second serialiser.
     * See `encodeForwardRules`.
     */
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN ciphers TEXT")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN kexAlgorithms TEXT")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN macs TEXT")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN hostKeyAlgorithms TEXT")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN startupCommand TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN environment TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN savedForwards TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * Why the last transfer attempt failed, so a FAILED row on the Transfers tab can say more than
     * that it failed.
     *
     * Nullable rather than NOT NULL DEFAULT '' for the same reason the algorithm lists are: an empty
     * string would be a claim that the failure had a reason and the reason was "", while NULL means
     * "no failure is recorded" - which is true for every row that predates the column, because the
     * reason used to be discarded at the catch site and never stored at all.
     */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transfer_queue ADD COLUMN errorMessage TEXT")
        }
    }

    /**
     * The per-host remote-desktop endpoints (VNC now, RDP reserved) as one packed text column -
     * the same shape [MIGRATION_11_12]'s advanced-settings columns and the savedForwards column
     * before it use, and for the same reasons: one endpoint per protocol is all a host has.
     *
     * NOT NULL DEFAULT '' because empty already means "nothing configured", so an upgraded row
     * needs no special case downstream.
     */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN remoteDesktop TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * The per-host Wake-on-LAN MAC address, as one text column holding whatever spelling the user
     * typed - the same shape the other optional per-host text columns use.
     *
     * NOT NULL DEFAULT '' like [MIGRATION_12_13]'s text columns rather than nullable like the
     * algorithm lists: an empty string already means "this host has no address to wake", which is the
     * only thing a null could add here, so nullable would hand every read site a second absence to
     * handle. An upgraded row therefore configures nothing, which is what every host that predates
     * the column means.
     */
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE host_profiles ADD COLUMN wakeOnLanMac TEXT NOT NULL DEFAULT ''")
        }
    }
}
