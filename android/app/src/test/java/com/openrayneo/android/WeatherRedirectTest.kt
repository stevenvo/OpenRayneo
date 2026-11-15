// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WeatherRedirectTest {
    private val original = "https://api.weather.gov/points/47.61038,-122.20068".toHttpUrl()
    @Test fun allowsCanonicalNwsCoordinatesAndRelativeLocations() {
        val canonical = "https://api.weather.gov/points/47.6104,-122.2007"
        assertEquals(canonical, WeatherRedirect.resolve(original, canonical).toString())
        assertEquals(canonical, WeatherRedirect.resolve(original, "/points/47.6104,-122.2007").toString())
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsCrossHostRedirects() { WeatherRedirect.resolve(original, "https://example.com/forecast") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTlsDowngrades() { WeatherRedirect.resolve(original, "http://api.weather.gov/points/47.6104,-122.2007") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialBearingUrls() { WeatherRedirect.resolve(original, "https://user:password@api.weather.gov/forecast") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonstandardPorts() { WeatherRedirect.resolve(original, "https://api.weather.gov:8443/forecast") }
}
