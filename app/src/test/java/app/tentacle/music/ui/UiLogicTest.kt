// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import app.tentacle.music.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {

    // ---- artwork decoding bounds (decompression-bomb protection) ----

    @Test
    fun smallImagesAreNotDownsampled() {
        assertEquals(1, artworkSampleSize(512, 512, 1024))
        assertEquals(1, artworkSampleSize(1024, 800, 1024))
    }

    @Test
    fun largeImagesAreDownsampledToTheLimit() {
        assertEquals(2, artworkSampleSize(2048, 2048, 1024))
        assertEquals(4, artworkSampleSize(4000, 3000, 1024))
        assertEquals(16, artworkSampleSize(16_000, 200, 1024))
    }

    @Test
    fun absurdOrMissingDimensionsAreRejected() {
        assertNull(artworkSampleSize(0, 500, 1024))
        assertNull(artworkSampleSize(-1, -1, 1024))
        assertNull(artworkSampleSize(50_000, 50_000, 1024))
    }

    // ---- sign-in checks that run before any network request ----

    @Test
    fun preflightRequiresServerAndUser() {
        assertTrue(Account.preflight("", "me", false) is Account.SignInResult.Failed)
        assertTrue(Account.preflight("192.168.1.10:8096", " ", false) is Account.SignInResult.Failed)
    }

    @Test
    fun preflightWarnsOnlyForPublicPlainHttp() {
        val warning = Account.preflight("http://jellyfin.example.com", "me", allowInsecure = false)
        assertEquals(Account.SignInResult.InsecureWarning("jellyfin.example.com"), warning)
        assertNull(Account.preflight("http://jellyfin.example.com", "me", allowInsecure = true))
        assertNull(Account.preflight("http://192.168.1.10:8096", "me", allowInsecure = false))
        assertNull(Account.preflight("jellyfin.example.com", "me", allowInsecure = false)) // defaults to https
    }

    // ---- connection status shown in Settings ----

    @Test
    fun connectionSecurityDescribesEachKindOfAddress() {
        assertEquals(Account.ConnectionSecurity.HTTPS, Account.connectionSecurity("https://music.example.com"))
        assertEquals(Account.ConnectionSecurity.HTTPS, Account.connectionSecurity("https://192.168.1.10:8920"))
        assertEquals(Account.ConnectionSecurity.HTTP_TAILSCALE, Account.connectionSecurity("http://100.101.102.103:8096"))
        assertEquals(Account.ConnectionSecurity.HTTP_TAILSCALE, Account.connectionSecurity("http://media.tail1234.ts.net:8096"))
        assertEquals(Account.ConnectionSecurity.HTTP_HOME, Account.connectionSecurity("http://192.168.1.10:8096"))
        assertEquals(Account.ConnectionSecurity.HTTP_HOME, Account.connectionSecurity("http://10.0.0.5:8096"))
        assertEquals(Account.ConnectionSecurity.HTTP_HOME, Account.connectionSecurity("http://nas.local:8096"))
        assertEquals(Account.ConnectionSecurity.HTTP_PUBLIC, Account.connectionSecurity("http://jellyfin.example.com"))
        assertEquals(Account.ConnectionSecurity.HTTP_PUBLIC, Account.connectionSecurity("http://8.8.8.8:8096"))
        assertEquals(Account.ConnectionSecurity.UNKNOWN, Account.connectionSecurity(""))
    }

    // ---- time labels ----

    @Test
    fun formatsTimes() {
        assertEquals("0:00", formatTime(0))
        assertEquals("3:07", formatTime(187_000))
        assertEquals("1:02:03", formatTime(3_723_000))
    }
}
