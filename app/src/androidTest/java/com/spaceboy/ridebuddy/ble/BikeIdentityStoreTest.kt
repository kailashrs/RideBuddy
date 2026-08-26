package com.spaceboy.ridebuddy.ble

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spaceboy.ridebuddy.domain.BikeIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BikeIdentityStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy { context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE) }
    private val store by lazy { SharedPreferencesBikeIdentityStore(context, PreferencesName) }
    private val first = requireNotNull(BluetoothAddress.parse("CC:B3:1E:C1:E1:B7"))
    private val second = requireNotNull(BluetoothAddress.parse("AA:BB:CC:DD:EE:FF"))

    @Before
    fun clearBeforeTest() = preferences.edit(commit = true) { clear() }

    @After
    fun clearAfterTest() = preferences.edit(commit = true) { clear() }

    @Test
    fun identityIsScopedToItsMotorcycleAddress() {
        val identity = BikeIdentity(
            vin = "VIN12345678901234",
            clusterSoftwareVersion = "2.3.4",
            lastConnectedAtMillis = 123L,
        )

        store.write(first, identity)

        assertEquals(identity, store.read(first))
        assertEquals(BikeIdentity(), store.read(second))
    }

    @Test
    fun partialUpdatesRetainPreviouslyKnownFields() {
        store.write(first, BikeIdentity(vin = "VIN12345678901234", lastConnectedAtMillis = 123L))

        store.write(first, BikeIdentity(clusterSoftwareVersion = "2.3.4", lastConnectedAtMillis = 456L))

        assertEquals(
            BikeIdentity(
                vin = "VIN12345678901234",
                clusterSoftwareVersion = "2.3.4",
                lastConnectedAtMillis = 456L,
            ),
            store.read(first),
        )
    }

    @Test
    fun clearingAnotherAddressDoesNotRemoveTheStoredIdentity() {
        val identity = BikeIdentity(vin = "VIN12345678901234")
        store.write(first, identity)

        store.clear(second)

        assertEquals(identity, store.read(first))
    }

    private companion object {
        const val PreferencesName = "bike_identity_store_test"
    }
}
