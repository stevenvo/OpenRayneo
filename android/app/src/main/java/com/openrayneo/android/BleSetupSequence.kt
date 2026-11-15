// Copyright 2026 OpenRayneo English contributors
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Android may report an MTU before discovery, and may report it more than once. */
internal class BleSetupSequence {
    var servicesReady = false
        private set
    var mtu = 23
        private set
    var identityReadStarted = false
        private set

    fun servicesDiscovered(): Boolean {
        servicesReady = true
        return claimIdentityRead()
    }

    fun mtuChanged(value: Int): Boolean {
        mtu = value
        return claimIdentityRead()
    }

    private fun claimIdentityRead(): Boolean {
        if (!servicesReady || mtu < 160 || identityReadStarted) return false
        identityReadStarted = true
        return true
    }

    fun reset() {
        servicesReady = false
        mtu = 23
        identityReadStarted = false
    }
}
