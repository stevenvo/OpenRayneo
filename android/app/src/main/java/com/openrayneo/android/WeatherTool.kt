// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Public city-level forecast queries. Never sends device location or the model API key. */
internal class WeatherTool {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    fun fetch(args: JSONObject, completion: (Result<JSONObject>) -> Unit) {
        val city = args.optString("city").trim()
        val region = args.optString("region").trim()
        val country = args.optString("country_code").trim().uppercase()
        require(city.length in 2..100 && region.length <= 100 && country.matches(Regex("[A-Z]{2}"))) {
            "Specify a city, its full state/region name, and a two-letter country code."
        }
        worker.execute {
            val result = runCatching {
                val search = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                    .addQueryParameter("name", city).addQueryParameter("count", "20")
                    .addQueryParameter("countryCode", country).addQueryParameter("language", "en").build()
                val matches = get(search).optJSONArray("results") ?: JSONArray()
                val places = (0 until matches.length()).map { matches.getJSONObject(it) }
                    .filter { it.optString("country_code").equals(country, true) && it.optString("name").equals(city, true) }
                val selected = places.filter { region.isBlank() || it.optString("admin1").equals(region, true) }
                if (selected.size != 1) {
                    JSONObject().put("error", "Location is missing or ambiguous. Ask the user or use the full state name from the candidates.")
                        .put("candidates", JSONArray(places.map { "${it.optString("name")}, ${it.optString("admin1")}, ${it.optString("country_code")}" }))
                } else {
                    val place = selected.single()
                    if (country == "US") nationalWeatherService(place) else {
                    val forecast = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                        .addQueryParameter("latitude", place.getDouble("latitude").toString())
                        .addQueryParameter("longitude", place.getDouble("longitude").toString())
                        .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
                        .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                        .addQueryParameter("temperature_unit", "fahrenheit").addQueryParameter("wind_speed_unit", "mph")
                        .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "2").build()
                    val data = get(forecast)
                    val current = data.getJSONObject("current")
                    current.put("condition", WeatherCondition.description(current.optInt("weather_code", -1)))
                    JSONObject().put("source", "Open-Meteo").put("source_url", "https://open-meteo.com/")
                        .put("location", "${place.optString("name")}, ${place.optString("admin1")}, ${place.optString("country_code")}")
                        .put("timezone", data.optString("timezone"))
                        .put("data_kind", "Current model-based weather conditions and forecast, fetched now; not a weather alert service")
                        .put("current", current).put("current_units", data.getJSONObject("current_units"))
                        .put("daily", data.getJSONObject("daily")).put("daily_units", data.getJSONObject("daily_units"))
                    }
                }
            }
            main.post { completion(result) }
        }
    }
    private fun nationalWeatherService(place: JSONObject): JSONObject {
        val pointUrl = "https://api.weather.gov/points/${place.getDouble("latitude")},${place.getDouble("longitude")}".toHttpUrl()
        val point = get(pointUrl).getJSONObject("properties")
        val forecastUrl = point.getString("forecast").toHttpUrl()
        check(forecastUrl.scheme == "https" && forecastUrl.host == "api.weather.gov" && forecastUrl.username.isEmpty() && forecastUrl.password.isEmpty()) {
            "Weather service returned an unexpected forecast address."
        }
        val forecast = get(forecastUrl).getJSONObject("properties")
        val all = forecast.getJSONArray("periods")
        val periods = JSONArray()
        for (i in 0 until minOf(4, all.length())) {
            val p = all.getJSONObject(i)
            val compact = JSONObject()
            for (key in listOf("name", "startTime", "endTime", "temperature", "temperatureUnit", "windSpeed", "windDirection", "shortForecast", "detailedForecast", "probabilityOfPrecipitation"))
                if (p.has(key)) compact.put(key, p.get(key))
            periods.put(compact)
        }
        check(periods.length() > 0) { "Weather service returned no forecast periods." }
        return JSONObject().put("source", "National Weather Service").put("source_url", forecastUrl.toString())
            .put("location", "${place.optString("name")}, ${place.optString("admin1")}, US")
            .put("timezone", point.optString("timeZone")).put("issued_at", forecast.optString("updateTime", forecast.optString("generatedAt")))
            .put("data_kind", "Live forecast fetched now. Period temperatures are forecast highs/lows, not current measured temperature. Use period names and dates; never call tonight's low the current temperature.")
            .put("periods", periods)
    }
    private fun get(url: HttpUrl): JSONObject = http.newCall(Request.Builder().url(url)
        .header("User-Agent", "OpenRayneo-English (https://github.com/stevenvo/OpenRayneo)").build()).execute().use {
        check(it.isSuccessful) { "Weather service returned HTTP ${it.code}. Try again shortly." }
        val body = checkNotNull(it.body) { "Weather service returned no data." }
        val bytes = body.byteStream().readNBytes(1_000_001)
        check(bytes.size <= 1_000_000) { "Weather response was too large." }
        JSONObject(String(bytes, Charsets.UTF_8))
    }
    fun close() { http.dispatcher.cancelAll(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); http.connectionPool.evictAll() }
}

internal object WeatherCondition {
    fun description(code: Int) = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown weather condition"
    }
}
