package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class BondPreparationTest {

    @Test
    fun `bonded device connects directly`() {
        assertEquals(
            BondPreparationAction.ConnectGatt,
            bondPreparationAction(BluetoothDevice.BOND_BONDED),
        )
    }

    @Test
    fun `bonding device waits for the system result`() {
        assertEquals(
            BondPreparationAction.ObserveBond,
            bondPreparationAction(BluetoothDevice.BOND_BONDING),
        )
    }

    @Test
    fun `unbonded device starts Android pairing`() {
        assertEquals(
            BondPreparationAction.StartBonding,
            bondPreparationAction(BluetoothDevice.BOND_NONE),
        )
    }

    @Test
    fun `unknown bond state is rejected`() {
        assertEquals(BondPreparationAction.Reject, bondPreparationAction(null))
    }

    @Test
    fun `createBond false while already bonded connects`() {
        assertEquals(
            BondStartFollowUp.ConnectGatt,
            bondStartFollowUp(started = false, currentBondState = BluetoothDevice.BOND_BONDED),
        )
    }

    @Test
    fun `createBond false while Android is bonding keeps observing`() {
        assertEquals(
            BondStartFollowUp.ContinueObserving,
            bondStartFollowUp(started = false, currentBondState = BluetoothDevice.BOND_BONDING),
        )
    }

    @Test
    fun `createBond false without pairing progress fails`() {
        assertEquals(
            BondStartFollowUp.Fail,
            bondStartFollowUp(started = false, currentBondState = BluetoothDevice.BOND_NONE),
        )
    }

    @Test
    fun `successful createBond keeps observing regardless of immediate state read`() {
        assertEquals(
            BondStartFollowUp.ContinueObserving,
            bondStartFollowUp(started = true, currentBondState = BluetoothDevice.BOND_NONE),
        )
    }

    @Test
    fun `bond event completes when reported and live states agree on bonded`() {
        assertEquals(
            BondEventOutcome.Complete,
            bondEventOutcome(
                reportedState = BluetoothDevice.BOND_BONDED,
                liveState = BluetoothDevice.BOND_BONDED,
                previousState = BluetoothDevice.BOND_BONDING,
            ),
        )
    }

    @Test
    fun `bond event ignores broadcasts while live state is still unreadable`() {
        assertEquals(
            BondEventOutcome.Ignore,
            bondEventOutcome(
                reportedState = BluetoothDevice.BOND_BONDED,
                liveState = null,
                previousState = BluetoothDevice.BOND_BONDING,
            ),
        )
    }

    @Test
    fun `bond event ignores broadcasts whose reported state has not caught up with live state`() {
        assertEquals(
            BondEventOutcome.Ignore,
            bondEventOutcome(
                reportedState = BluetoothDevice.BOND_BONDING,
                liveState = BluetoothDevice.BOND_BONDED,
                previousState = BluetoothDevice.BOND_BONDING,
            ),
        )
    }

    @Test
    fun `bond event fails when pairing transitions from bonding straight to none`() {
        assertEquals(
            BondEventOutcome.FailPairingAborted,
            bondEventOutcome(
                reportedState = BluetoothDevice.BOND_NONE,
                liveState = BluetoothDevice.BOND_NONE,
                previousState = BluetoothDevice.BOND_BONDING,
            ),
        )
    }

    @Test
    fun `bond event ignores a none-to-none transition that did not pass through bonding`() {
        assertEquals(
            BondEventOutcome.Ignore,
            bondEventOutcome(
                reportedState = BluetoothDevice.BOND_NONE,
                liveState = BluetoothDevice.BOND_NONE,
                previousState = BluetoothDevice.BOND_NONE,
            ),
        )
    }

    @Test
    fun `bond event ignores an in-progress bonding broadcast`() {
        assertEquals(
            BondEventOutcome.Ignore,
            bondEventOutcome(
                reportedState = BluetoothDevice.BOND_BONDING,
                liveState = BluetoothDevice.BOND_BONDING,
                previousState = BluetoothDevice.BOND_NONE,
            ),
        )
    }
}
