package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class BleSetupSequenceTest {
    @Test fun earlyMtuWaitsForDiscoveryAndStartsIdentityOnce() {
        val setup = BleSetupSequence()
        assertFalse(setup.mtuChanged(517))
        assertFalse(setup.identityReadStarted)
        assertTrue(setup.servicesDiscovered())
        assertFalse(setup.mtuChanged(517))
        assertFalse(setup.servicesDiscovered())
    }

    @Test fun normalDiscoveryThenMtuStartsIdentityOnce() {
        val setup = BleSetupSequence()
        assertFalse(setup.servicesDiscovered())
        assertTrue(setup.mtuChanged(512))
        assertFalse(setup.mtuChanged(517))
    }

    @Test fun defaultMtuCannotStartAuthentication() {
        val setup = BleSetupSequence()
        assertFalse(setup.mtuChanged(23))
        assertFalse(setup.servicesDiscovered())
        assertFalse(setup.mtuChanged(159))
        assertTrue(setup.mtuChanged(160))
    }

    @Test fun reconnectDoesNotReuseOldDiscoveryOrMtu() {
        val setup = BleSetupSequence()
        setup.servicesDiscovered()
        assertTrue(setup.mtuChanged(517))
        setup.reset()
        assertFalse(setup.servicesReady)
        assertFalse(setup.identityReadStarted)
        assertEquals(23, setup.mtu)
        assertFalse(setup.mtuChanged(517))
        assertTrue(setup.servicesDiscovered())
    }
}
