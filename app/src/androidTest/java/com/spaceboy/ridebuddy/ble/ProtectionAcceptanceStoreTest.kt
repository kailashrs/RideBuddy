package com.spaceboy.ridebuddy.ble

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectionAcceptanceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy { context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE) }
    private val store by lazy { SharedPreferencesProtectionAcceptanceStore(context, PreferencesName) }
    private val first = requireNotNull(BluetoothAddress.parse("CC:B3:1E:C1:E1:B7"))
    private val second = requireNotNull(BluetoothAddress.parse("AA:BB:CC:DD:EE:FF"))

    @Before
    fun clearBeforeTest() = preferences.edit(commit = true) { clear() }

    @After
    fun clearAfterTest() = preferences.edit(commit = true) { clear() }

    @Test
    fun acceptanceIsScopedToTheRecordedAddress() {
        store.markAccepted(first)

        assertTrue(store.isAccepted(first))
        assertFalse(store.isAccepted(second))
    }

    @Test
    fun clearingAnotherAddressPreservesAcceptance() {
        store.markAccepted(first)

        store.clear(second)

        assertTrue(store.isAccepted(first))
    }

    @Test
    fun clearingRecordedAddressRemovesAcceptance() {
        store.markAccepted(first)

        store.clear(first)

        assertFalse(store.isAccepted(first))
    }

    private companion object {
        const val PreferencesName = "protection_acceptance_store_test"
    }
}
