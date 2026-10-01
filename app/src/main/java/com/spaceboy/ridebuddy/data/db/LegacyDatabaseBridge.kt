package com.spaceboy.ridebuddy.data.db

import android.content.Context
import androidx.room.RoomDatabase

// TEMPORARY. Moves the data of a development install from the pre-1.2 files into the renamed
// ones. Delete this file, its two calls in AppContainer and LegacyDatabaseBridgeTest once the
// development phone has run it (it runs once, at startup, on the main thread);
// no released build ever used the old names.

private const val LegacyRides = "ride_history.db"
private const val LegacySamples = "ride_samples.db"

/** Copies every ride from the old summaries file into [database], then deletes the old file. */
internal fun bridgeLegacyRides(context: Context, database: RideBuddyDatabase) =
    copyLegacyTable(context, LegacyRides, database, "rides")

/** Copies every sample series from the old telemetry file into [database], then deletes it. */
internal fun bridgeLegacyTelemetry(context: Context, database: RawTelemetryDatabase) =
    copyLegacyTable(context, LegacySamples, database, "ride_samples")

/** Both schemas come from the same entity, so the columns line up as they are. */
private fun copyLegacyTable(context: Context, legacyName: String, database: RoomDatabase, table: String) {
    val legacy = context.getDatabasePath(legacyName)
    if (!legacy.exists()) return
    val db = database.openHelper.writableDatabase
    db.execSQL("ATTACH DATABASE ? AS legacy", arrayOf(legacy.absolutePath))
    db.execSQL("INSERT INTO $table SELECT * FROM legacy.$table")
    db.execSQL("DETACH DATABASE legacy")
    context.deleteDatabase(legacyName)
}
