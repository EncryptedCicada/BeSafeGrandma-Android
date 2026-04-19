package com.citrushack.besafegrandma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around OkHttp for the single `/api/analyze` endpoint.
 *
 * Change BASE_URL to match your server:
 *   - Android Emulator  →  http://10.0.2.2:3001
 *   - Physical device on same LAN  →  http://192.168.x.x:3001
 *   - Deployed backend  →  https://your-domain.com
 */
object ApiService {

    var baseUrl: String = "http://10.0.2.2:3001"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends [transcript] to POST /api/analyze and returns a normalised [AnalysisResult].
     * Throws on network or HTTP errors so the caller (ViewModel) can catch and handle.
     */
    suspend fun analyze(transcript: String): AnalysisResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("transcript", transcript)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/api/analyze")
            .post(body)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("Server error ${response.code}: ${response.body?.string()}")
        }

        val json = JSONObject(response.body!!.string())

        AnalysisResult(
            scamLikelihoodScore = json.optInt("scam_likelihood_score", 0)
                .coerceIn(0, 100),
            criticalIndicators = buildList {
                val arr = json.optJSONArray("critical_indicators")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            },
            reasoningSummary = json.optString("reasoning_summary", "").trim()
                .ifEmpty { "No summary provided." },
        )
    }
}