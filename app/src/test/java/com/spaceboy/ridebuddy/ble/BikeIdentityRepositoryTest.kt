package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class BikeIdentityRepositoryTest {
    private val address = requireNotNull(BluetoothAddress.parse("CC:B3:1E:C1:E1:B7"))

    @Test
    fun `stored identity is restored and live values take precedence`() = runBlocking {
        val store = FakeBikeIdentityStore(
            mapOf(
                address to BikeIdentity(
                    vin = "OLDVIN12345678901",
                    clusterSoftwareVersion = "1.0",
                    lastConnectedAtMillis = 100L,
                ),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = BikeIdentityRepository(store, scope, Dispatchers.Unconfined)

        repository.select(address)
        repository.update(address) { identity ->
            identity.copy(clusterSoftwareVersion = "2.0", lastConnectedAtMillis = 200L)
        }
        yield()

        assertEquals(
            BikeIdentity(
                vin = "OLDVIN12345678901",
                clusterSoftwareVersion = "2.0",
                lastConnectedAtMillis = 200L,
            ),
            repository.identity.value,
        )
        assertEquals(repository.identity.value, store.read(address))
        scope.cancel()
    }

    @Test
    fun `clearing the selected address clears visible and stored identity`() = runBlocking {
        val storedIdentity = BikeIdentity(vin = "VIN12345678901234", lastConnectedAtMillis = 100L)
        val store = FakeBikeIdentityStore(mapOf(address to storedIdentity))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = BikeIdentityRepository(store, scope, Dispatchers.Unconfined)

        repository.select(address)
        repository.clear(address)
        yield()

        assertEquals(BikeIdentity(), repository.identity.value)
        assertEquals(BikeIdentity(), store.read(address))
        scope.cancel()
    }

    @Test
    fun `merge keeps current fields and latest successful connection`() {
        assertEquals(
            BikeIdentity(vin = "CURRENT", clusterSoftwareVersion = "stored", lastConnectedAtMillis = 20L),
            mergeBikeIdentity(
                current = BikeIdentity(vin = "CURRENT", lastConnectedAtMillis = 10L),
                stored = BikeIdentity(vin = "stored-vin", clusterSoftwareVersion = "stored", lastConnectedAtMillis = 20L),
            ),
        )
    }
}

private class FakeBikeIdentityStore(initial: Map<BluetoothAddress, BikeIdentity>) : BikeIdentityStore {
    private val identities = initial.toMutableMap()

    override fun read(address: BluetoothAddress): BikeIdentity = identities[address] ?: BikeIdentity()

    override fun write(address: BluetoothAddress, identity: BikeIdentity) {
        identities[address] = identity
    }

    override fun clear(address: BluetoothAddress) {
        identities.remove(address)
    }
}
