// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import app.tentacle.music.Tailscale.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleTest {

    @Test
    fun tailnetAddressesAreRecognised() {
        listOf("100.64.0.1", "100.100.100.100", "100.127.255.255", "pi.tail1234.ts.net", "PI.TAIL1234.TS.NET.")
            .forEach { assertTrue(it, Tailscale.isTailnetAddress(it)) }
    }

    @Test
    fun otherAddressesAreNot() {
        listOf("100.63.255.255", "100.128.0.1", "192.168.1.10", "10.0.0.5", "jellyfin.example.com", "ts.net.example.com", "", "100.64.0", "100.64.0.300")
            .forEach { assertFalse(it, Tailscale.isTailnetAddress(it)) }
    }

    @Test
    fun settingOffMeansNothingHappens() {
        assertEquals(Outcome.OFF, Tailscale.precheck(TailscaleMode.OFF, force = false, installed = true, vpnActive = false, coolingDown = false))
    }

    @Test
    fun connectNowIgnoresTheSettingAndTheWait() {
        assertNull(Tailscale.precheck(TailscaleMode.OFF, force = true, installed = true, vpnActive = false, coolingDown = true))
    }

    @Test
    fun needsTheTailscaleApp() {
        assertEquals(Outcome.NOT_INSTALLED, Tailscale.precheck(TailscaleMode.ALWAYS, force = true, installed = false, vpnActive = false, coolingDown = false))
    }

    @Test
    fun doesNothingWhenAVpnIsAlreadyUp() {
        assertEquals(Outcome.ALREADY_CONNECTED, Tailscale.precheck(TailscaleMode.ALWAYS, force = false, installed = true, vpnActive = true, coolingDown = false))
    }

    @Test
    fun automaticAttemptsWaitAfterAFailure() {
        assertEquals(Outcome.WAITING, Tailscale.precheck(TailscaleMode.WHEN_NEEDED, force = false, installed = true, vpnActive = false, coolingDown = true))
        assertNull(Tailscale.precheck(TailscaleMode.WHEN_NEEDED, force = false, installed = true, vpnActive = false, coolingDown = false))
    }

    @Test
    fun unknownStoredModeFallsBackToOff() {
        assertEquals(TailscaleMode.OFF, TailscaleMode.parse(null))
        assertEquals(TailscaleMode.OFF, TailscaleMode.parse("SOMETIMES"))
        assertEquals(TailscaleMode.WHEN_NEEDED, TailscaleMode.parse("WHEN_NEEDED"))
    }
}
