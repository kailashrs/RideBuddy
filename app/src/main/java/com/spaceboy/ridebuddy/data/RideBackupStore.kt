package com.spaceboy.ridebuddy.data

import android.content.Context
import java.io.File

/**
 * Holds the ride-summary snapshot that Android's backup service backs up and restores.
 *
 * The app never uploads anything itself. It keeps this one file current, and the platform
 * copies it to the rider's backup — encrypted with their device PIN on Android 9 and above,
 * free, and outside their Drive quota — as part of the app's 25 MB allowance. The live
 * database is deliberately not what gets backed up: it carries the sample series, which is
 * megabytes per riding hour and would exhaust that allowance within a day's riding.
 *
 * Writing through a temporary file and renaming means the snapshot is never observed half
 * written, which matters because the backup service reads it on its own schedule with no
 * coordination with this app.
 */
class RideBackupStore(context: Context, directory: File? = null) {
    private val directory = directory ?: File(context.applicationContext.filesDir, DirectoryName)
    private val snapshot get() = File(this.directory, FileName)
    private val pending get() = File(this.directory, "$FileName.tmp")

    fun write(rides: List<Ride>) {
        directory.mkdirs()
        val pendingFile = pending
        pendingFile.writeText(encodeRideBackup(rides))
        if (!pendingFile.renameTo(snapshot)) {
            // Rename can fail if the destination exists on some filesystems. Falling back to
            // a delete-then-rename keeps the snapshot current at the cost of a brief window
            // where it is absent, which a restore reads as "nothing to restore".
            snapshot.delete()
            if (!pendingFile.renameTo(snapshot)) pendingFile.delete()
        }
    }

    /** The restored snapshot, or nothing when there is no readable one. */
    fun read(): List<Ride> {
        val file = snapshot
        if (!file.isFile) return emptyList()
        return runCatching { decodeRideBackup(file.readText()) }.getOrDefault(emptyList())
    }

    private companion object {
        /**
         * Its own directory so the backup rules can name exactly this file. Everything else
         * the app writes stays outside the backup set by construction rather than by a rule
         * someone has to remember to add.
         */
        const val DirectoryName = "backup"
        const val FileName = "rides.backup"
    }
}
