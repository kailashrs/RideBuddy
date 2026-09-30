package com.spaceboy.ridebuddy.ble

import android.net.MacAddress
import com.spaceboy.ridebuddy.InMemoryDataStore
import com.spaceboy.ridebuddy.domain.BikeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BikeIdentityRepositoryTest {
    private val address = requireNotNull(MacAddress.fromString("CC:B3:1E:C1:E1:B7"))
    private val other = requireNotNull(MacAddress.fromString("11:22:33:44:55:66"))

    private fun stored(address: MacAddress) = StoredBikeIdentity(
        address = address.toString(),
        vin = "OLDVIN12345678901",
        clusterSoftwareVersion = "1.0",
        lastConnectedAtMillis = 100L,
    )

    @Test
    fun `stored identity is restored and live values are merged into it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = InMemoryDataStore(LinkState(identity = stored(address)))
        val repository = BikeIdentityRepository(LinkStateStore(store, scope), scope)

        repository.select(address)
        repository.update(address) { it.copy(clusterSoftwareVersion = "2.0", lastConnectedAtMillis = 200L) }

        val expected = BikeIdentity("OLDVIN12345678901", "2.0", 200L)
        awaitEqual(expected) { repository.identity.value }
        assertEquals(expected, store.value.identity?.toBikeIdentity())
        scope.cancel()
    }

    @Test
    fun `another bike's stored identity reads as empty and updates for it are ignored`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = InMemoryDataStore(LinkState(identity = stored(other)))
        val repository = BikeIdentityRepository(LinkStateStore(store, scope), scope)

        repository.select(address)
        repository.update(other) { it.copy(vin = "IGNORED") }
        delay(50)

        assertEquals(BikeIdentity(), repository.identity.value)
        assertEquals("OLDVIN12345678901", store.value.identity?.vin)
        scope.cancel()
    }

    @Test
    fun `clearing the selected address clears visible and stored identity`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = InMemoryDataStore(LinkState(identity = stored(address)))
        val repository = BikeIdentityRepository(LinkStateStore(store, scope), scope)

        repository.select(address)
        repository.clear(address)

        awaitEqual(null) { store.value.identity }
        assertEquals(BikeIdentity(), repository.identity.value)
        scope.cancel()
    }

    private suspend fun <T> awaitEqual(expected: T, actual: () -> T) {
        withTimeout(2_000) { while (actual() != expected) delay(10) }
    }
}
