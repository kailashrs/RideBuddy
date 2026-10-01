package com.spaceboy.ridebuddy.data.db

import android.content.Context
import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

// TEMPORARY. Moves the data of a development install from the pre-1.2 files into the renamed
// ones. Delete this file, its two calls in AppContainer and LegacyDatabaseBridgeTest once the
// development phone has run it (it runs once, at startup, on the main thread);
// no released build ever used the old names.

private const val LegacyRides = "ride_history.db"
private const val LegacySamples = "ride_samples.db"
private const val Tag = "LegacyDatabaseBridge"

/** Copies every ride from the old summaries file into [database], then deletes the old file. */
internal fun bridgeLegacyRides(context: Context, database: RideBuddyDatabase) =
    copyLegacyTable(context, LegacyRides, database, "rides")

/** Copies every sample series from the old telemetry file into [database], then deletes it. */
internal fun bridgeLegacyTelemetry(context: Context, database: RawTelemetryDatabase) =
    copyLegacyTable(context, LegacySamples, database, "ride_samples")

/**
 * Columns are matched by name, so a column-order difference between the two schemas cannot
 * misplace a value, and rows already present are left alone. On any failure the old file is
 * kept, so nothing is lost and the next start tries again.
 */
private fun copyLegacyTable(context: Context, legacyName: String, database: RoomDatabase, table: String) {
    val legacy = context.getDatabasePath(legacyName)
    if (!legacy.exists()) return
    val db = database.openHelper.writableDatabase
    try {
        db.execSQL("ATTACH DATABASE ? AS legacy", arrayOf(legacy.absolutePath))
        try {
            val columns = db.columns("main", table).intersect(db.columns("legacy", table).toSet())
                .joinToString(", ") { "`$it`" }
            db.execSQL("INSERT OR IGNORE INTO $table ($columns) SELECT $columns FROM legacy.$table")
        } finally {
            db.execSQL("DETACH DATABASE legacy")
        }
        context.deleteDatabase(legacyName)
    } catch (error: Exception) {
        Log.w(Tag, "Keeping $legacyName; copying from it failed", error)
    }
}

private fun SupportSQLiteDatabase.columns(schema: String, table: String): List<String> =
    query("PRAGMA $schema.table_info($table)").use { cursor ->
        val name = cursor.getColumnIndexOrThrow("name")
        buildList { while (cursor.moveToNext()) add(cursor.getString(name)) }
    }
