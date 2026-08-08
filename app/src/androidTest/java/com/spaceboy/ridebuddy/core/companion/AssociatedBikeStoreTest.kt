package com.spaceboy.ridebuddy.core.companion

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spaceboy.ridebuddy.ble.BluetoothAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssociatedBikeStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy { context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE) }
    private val store by lazy { AssociatedBikeStore(context, PreferencesName) }

    @Before
    fun clearBeforeTest() = preferences.edit(commit = true) { clear() }

    @After
    fun clearAfterTest() = preferences.edit(commit = true) { clear() }

    @Test
    fun binaryAddressRoundTripPreservesAssociation() {
        val address = requireNotNull(BluetoothAddress.parse("CC:B3:1E:C1:E1:B7"))
        store.write(AssociatedBike(address, "RS457_IDE1B7", associationId = 11))

        val bike = store.read()

        assertEquals("CC:B3:1E:C1:E1:B7", bike?.address)
        assertEquals("RS457_IDE1B7", bike?.name)
        assertEquals(11, bike?.associationId)
        assertEquals(address.toLong(), preferences.getLong("address_value", -1L))
        assertFalse(preferences.contains("address"))
    }

    @Test
    fun invalidBinaryAddressIsIgnored() {
        preferences.edit(commit = true) {
            putLong("address_value", -1L)
            putString("name", "RS457_IDE1B7")
            putInt("association_id", 11)
        }

        assertNull(store.read())
    }

    @Test
    fun obsoleteStringAddressIsIgnored() {
        preferences.edit(commit = true) {
            putString("address", "cc:b3:1e:c1:e1:b7")
            putString("name", "RS457_IDE1B7")
            putInt("association_id", 11)
        }

        assertNull(store.read())
    }

    private companion object {
        const val PreferencesName = "associated_bike_store_test"
    }
}
