package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ClaudeReasoning"

/**
 * Secondary "brain" reasoning layer powered by Anthropic Claude API when ANTHROPIC_API_KEY
 * is configured, falling back automatically to Gemini 3.8 Flash via Firebase AI Logic
 * so ARC never breaks if Claude is unavailable.
 */
class ClaudeReasoningClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val geminiFallbackModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel("gemini-3.8-flash")
    }

    suspend fun reason(
        prompt: String,
        systemInstruction: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val claudeKey = BuildConfig.ANTHROPIC_API_KEY
        if (claudeKey.isNotBlank() && !claudeKey.startsWith("MY_")) {
            val claudeResult = callClaude(prompt, systemInstruction, claudeKey)
            if (claudeResult.isSuccess) {
                return@withContext Result.success(claudeResult.getOrThrow() to "Claude")
            }
            Log.w(TAG, "Claude unavailable (${claudeResult.exceptionOrNull()?.message}); falling back to Gemini.")
        }

        try {
            val resp = withTimeout(45_000L) {
                geminiFallbackModel.generateContent("$systemInstruction\n\nUser request:\n$prompt")
            }
            val text = resp.text?.trim()
            if (!text.isNullOrBlank()) {
                Result.success(text to "Gemini")
            } else {
                Result.failure(IllegalStateException("Reasoning model returned empty text."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fallback Gemini reasoning failed", e)
            Result.failure(e)
        }
    }

    private fun callClaude(prompt: String, systemPrompt: String, apiKey: String): Result<String> {
        return try {
            val bodyJson = JSONObject().apply {
                put("model", "claude-3-5-sonnet-latest")
                put("max_tokens", 700)
                put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IllegalStateException("Claude HTTP ${resp.code}"))
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                val content = root.optJSONArray("content") ?: return Result.failure(IllegalStateException("Empty content"))
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") {
                        sb.append(block.optString("text"))
                    }
                }
                val out = sb.toString().trim()
                if (out.isBlank()) Result.failure(IllegalStateException("Empty Claude response"))
                else Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
