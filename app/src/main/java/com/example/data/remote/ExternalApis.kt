package com.example.data.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val TAG = "ExternalApis"

data class WeatherSnapshot(
    val city: String,
    val temperatureC: Double,
    val apparentC: Double,
    val condition: String,
    val windKmh: Double,
    val usedFallbackCity: Boolean
)

data class NewsArticle(
    val title: String,
    val source: String
)

data class CricketMatchInfo(
    val name: String,
    val status: String,
    val scoreSummary: String
)

data class YouTubeVideoHit(
    val videoId: String,
    val title: String,
    val channel: String,
    val watchUrl: String
)

data class CalendarMeetingInfo(
    val title: String,
    val startFormatted: String,
    val location: String,
    val description: String
)

data class ResolvedLocation(
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val fullAddress: String,
    val isFallback: Boolean
)

class ExternalApis(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves live GPS + Geocoder address; falls back only when permission is denied or GPS unavailable.
     */
    @SuppressLint("MissingPermission")
    suspend fun resolveLiveLocation(): ResolvedLocation = withContext(Dispatchers.IO) {
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCoarse && !hasFine) {
            return@withContext ResolvedLocation(
                latitude = 28.6139,
                longitude = 77.2090,
                city = "New Delhi (Default)",
                fullAddress = "Location permission denied",
                isFallback = true
            )
        }

        val fused = LocationServices.getFusedLocationProviderClient(context)
        val location = suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        cont.resume(loc)
                    } else {
                        fused.lastLocation
                            .addOnSuccessListener { last -> cont.resume(last) }
                            .addOnFailureListener { cont.resume(null) }
                    }
                }
                .addOnFailureListener {
                    fused.lastLocation
                        .addOnSuccessListener { last -> cont.resume(last) }
                        .addOnFailureListener { cont.resume(null) }
                }
            cont.invokeOnCancellation { cts.cancel() }
        }

        if (location == null) {
            return@withContext ResolvedLocation(
                latitude = 28.6139,
                longitude = 77.2090,
                city = "Location Unavailable",
                fullAddress = "Current GPS signal unavailable",
                isFallback = true
            )
        }

        val (city, address) = reverseGeocode(location.latitude, location.longitude)
        ResolvedLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            city = city,
            fullAddress = address,
            isFallback = false
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lon: Double): Pair<String, String> =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext "Local Area" to "$lat, $lon"
                val geocoder = Geocoder(context, Locale.getDefault())
                val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lon, 1) { list ->
                            cont.resume(list)
                        }
                    }
                } else {
                    geocoder.getFromLocation(lat, lon, 1).orEmpty()
                }
                val addr = results.firstOrNull()
                val city = addr?.locality ?: addr?.subAdminArea ?: addr?.adminArea ?: "Local Area"
                val line = addr?.getAddressLine(0) ?: city
                city to line
            } catch (e: Exception) {
                Log.w(TAG, "Geocoder error: ${e.message}")
                "Local Area" to "$lat, $lon"
            }
        }

    suspend fun fetchWeather(): Result<WeatherSnapshot> = withContext(Dispatchers.IO) {
        try {
            val loc = resolveLiveLocation()
            val url =
                "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Weather HTTP ${resp.code}"))
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val current = json.getJSONObject("current")
                val temp = current.getDouble("temperature_2m")
                val apparent = current.optDouble("apparent_temperature", temp)
                val code = current.optInt("weather_code", 0)
                val wind = current.optDouble("wind_speed_10m", 0.0)
                Result.success(
                    WeatherSnapshot(
                        city = loc.city,
                        temperatureC = temp,
                        apparentC = apparent,
                        condition = mapWmoCode(code),
                        windKmh = wind,
                        usedFallbackCity = loc.isFallback
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
            Result.failure(e)
        }
    }

    private fun mapWmoCode(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Light drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Rain showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Clear"
    }

    suspend fun fetchTopNews(query: String = "india", maxItems: Int = 5): Result<List<NewsArticle>> =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.NEWS_API_KEY
            if (apiKey.isBlank() || apiKey.startsWith("MY_")) {
                return@withContext Result.failure(
                    IllegalStateException("NEWS_API_KEY not configured in AI Studio Secrets.")
                )
            }
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                // Supports GNews format first; if NewsAPI key is used, fallback to NewsAPI endpoint
                val gnewsUrl = "https://gnews.io/api/v4/top-headlines?category=general&lang=en&country=in&max=$maxItems&apikey=$apiKey"
                val articles = mutableListOf<NewsArticle>()
                httpClient.newCall(Request.Builder().url(gnewsUrl).build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string().orEmpty())
                        val arr = root.optJSONArray("articles")
                        if (arr != null) {
                            for (i in 0 until minOf(arr.length(), maxItems)) {
                                val item = arr.getJSONObject(i)
                                val src = item.optJSONObject("source")?.optString("name") ?: "News"
                                articles.add(NewsArticle(item.getString("title"), src))
                            }
                            return@withContext Result.success(articles)
                        }
                    }
                }
                val newsApiUrl = "https://newsapi.org/v2/top-headlines?q=$q&pageSize=$maxItems&apiKey=$apiKey"
                httpClient.newCall(Request.Builder().url(newsApiUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("News API HTTP ${resp.code}"))
                    }
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val arr = root.optJSONArray("articles") ?: return@withContext Result.success(emptyList())
                    for (i in 0 until minOf(arr.length(), maxItems)) {
                        val item = arr.getJSONObject(i)
                        val src = item.optJSONObject("source")?.optString("name") ?: "News"
                        articles.add(NewsArticle(item.getString("title"), src))
                    }
                    Result.success(articles)
                }
            } catch (e: Exception) {
                Log.e(TAG, "News fetch failed", e)
                Result.failure(e)
            }
        }

    suspend fun fetchCricketScores(): Result<List<CricketMatchInfo>> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.CRIC_API_KEY
        if (apiKey.isBlank() || apiKey.startsWith("MY_")) {
            return@withContext Result.failure(
                IllegalStateException("CRIC_API_KEY not configured in AI Studio Secrets.")
            )
        }
        try {
            val url = "https://api.cricapi.com/v1/currentMatches?apikey=$apiKey&offset=0"
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("CricAPI HTTP ${resp.code}"))
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                val data = root.optJSONArray("data") ?: return@withContext Result.success(emptyList())
                val list = mutableListOf<CricketMatchInfo>()
                for (i in 0 until minOf(data.length(), 4)) {
                    val m = data.getJSONObject(i)
                    val scoresArr = m.optJSONArray("score")
                    val scoreSb = StringBuilder()
                    if (scoresArr != null) {
                        for (j in 0 until scoresArr.length()) {
                            val s = scoresArr.getJSONObject(j)
                            if (j > 0) scoreSb.append(" | ")
                            scoreSb.append("${s.optString("inning")}: ${s.optInt("r")}/${s.optInt("w")} (${s.optDouble("o")} ov)")
                        }
                    }
                    list.add(
                        CricketMatchInfo(
                            name = m.optString("name", "Match"),
                            status = m.optString("status", ""),
                            scoreSummary = scoreSb.toString().ifBlank { "Score updates pending" }
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cricket fetch failed", e)
            Result.failure(e)
        }
    }

    suspend fun searchTopYouTubeVideo(query: String): Result<YouTubeVideoHit> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.YOUTUBE_API_KEY
        if (apiKey.isBlank() || apiKey.startsWith("MY_")) {
            return@withContext Result.failure(
                IllegalStateException("YOUTUBE_API_KEY not configured in AI Studio Secrets.")
            )
        }
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url =
                "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=1&q=$encoded&key=$apiKey"
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("YouTube API HTTP ${resp.code}"))
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                val items = root.optJSONArray("items")
                if (items == null || items.length() == 0) {
                    return@withContext Result.failure(IllegalStateException("No matching YouTube video found."))
                }
                val first = items.getJSONObject(0)
                val videoId = first.getJSONObject("id").getString("videoId")
                val snippet = first.getJSONObject("snippet")
                Result.success(
                    YouTubeVideoHit(
                        videoId = videoId,
                        title = snippet.optString("title", query),
                        channel = snippet.optString("channelTitle", ""),
                        watchUrl = "https://www.youtube.com/watch?v=$videoId"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search failed", e)
            Result.failure(e)
        }
    }

    fun queryNextCalendarMeeting(): CalendarMeetingInfo? {
        val hasCal = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCal) return null

        val now = System.currentTimeMillis()
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DELETED} = 0"
        val args = arrayOf(now.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC LIMIT 1"

        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val title = cursor.getString(0) ?: "Untitled Meeting"
                    val startMs = cursor.getLong(1)
                    val location = cursor.getString(2) ?: ""
                    val desc = cursor.getString(3) ?: ""
                    val formatted = Instant.ofEpochMilli(startMs)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE, h:mm a"))
                    CalendarMeetingInfo(title, formatted, location, desc)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Calendar query failed: ${e.message}")
            null
        }
    }

    fun insertDeviceCalendarEvent(title: String, startMillis: Long, notes: String): Boolean {
        val hasWrite = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasWrite) return false
        return try {
            val calId = findPrimaryCalendarId() ?: return false
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, startMillis + 30 * 60_000L)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, notes)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        } catch (e: Exception) {
            Log.w(TAG, "Calendar insert failed: ${e.message}")
            false
        }
    }

    private fun findPrimaryCalendarId(): Long? {
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * On-device ML Kit translation (downloads model lazily on Wi-Fi/network if needed).
     */
    suspend fun translateOnDevice(text: String, targetLangCode: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val detected = suspendCancellableCoroutine { cont ->
                    LanguageIdentification.getClient().identifyLanguage(text)
                        .addOnSuccessListener { code -> cont.resume(if (code == "und") "en" else code) }
                        .addOnFailureListener { cont.resume("en") }
                }
                val sourceLang = TranslateLanguage.fromLanguageTag(detected) ?: TranslateLanguage.ENGLISH
                val targetLang = TranslateLanguage.fromLanguageTag(targetLangCode) ?: TranslateLanguage.HINDI
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLang)
                    .setTargetLanguage(targetLang)
                    .build()
                val translator = Translation.getClient(options)
                val downloaded = suspendCancellableCoroutine { cont ->
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener { cont.resume(true) }
                        .addOnFailureListener { cont.resume(false) }
                }
                if (!downloaded) {
                    translator.close()
                    return@withContext Result.failure(IllegalStateException("Translation model download failed"))
                }
                val translated = suspendCancellableCoroutine<Result<String>> { cont ->
                    translator.translate(text)
                        .addOnSuccessListener { out -> cont.resume(Result.success(out)) }
                        .addOnFailureListener { err -> cont.resume(Result.failure(err)) }
                }
                translator.close()
                translated
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
