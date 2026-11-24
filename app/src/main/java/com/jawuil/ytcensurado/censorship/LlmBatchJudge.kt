package com.jawuil.ytcensurado.censorship

import com.jawuil.ytcensurado.config.ConfigManager
import com.jawuil.ytcensurado.model.FilterVerdict
import com.jawuil.ytcensurado.model.LlmProvider
import com.jawuil.ytcensurado.model.VideoItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LlmBatchJudge {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean {
        if (!ConfigManager.llmEnabled) return false
        return when (ConfigManager.llmProvider) {
            LlmProvider.GEMINI -> ConfigManager.geminiApiKey.isNotBlank()
            LlmProvider.GROQ -> ConfigManager.groqApiKey.isNotBlank()
            LlmProvider.OPENAI_COMPATIBLE -> ConfigManager.customOpenAiEndpoint.isNotBlank()
        }
    }

    /**
     * Evaluates a batch of surviving candidate videos using the active LLM provider (Gemini / Groq / OpenAI-compatible).
     * Returns a Map of videoId -> FilterVerdict.
     */
    fun evaluateBatch(videos: List<VideoItem>): Map<String, FilterVerdict> {
        if (videos.isEmpty() || !isConfigured()) return emptyMap()

        val itemsArray = JSONArray()
        videos.forEach { v ->
            val obj = JSONObject()
            obj.put("id", v.videoId)
            obj.put("title", v.title)
            obj.put("channel", v.channelName)
            obj.put("handle", v.channelHandle)
            obj.put("duration", v.durationText)
            obj.put("desc", v.descriptionSnippet.take(140))
            itemsArray.put(obj)
        }

        val prompt = buildString {
            append(ConfigManager.llmSystemPrompt.trim())
            append("\n\nAnaliza esta lista JSON de videos candidatos y devuelve ÚNICAMENTE un objeto JSON válido con la clave \"results\", donde cada elemento tenga: ")
            append("{\"id\": \"...\", \"allowed\": true/false, \"score\": 0-100, \"reason\": \"motivo breve\"}.\n")
            append("Videos a evaluar:\n")
            append(itemsArray.toString())
        }

        return try {
            val rawResponse = when (ConfigManager.llmProvider) {
                LlmProvider.GEMINI -> callGemini(prompt)
                LlmProvider.GROQ -> callOpenAiCompatible(
                    endpoint = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = ConfigManager.groqApiKey,
                    model = ConfigManager.groqModel,
                    prompt = prompt
                )
                LlmProvider.OPENAI_COMPATIBLE -> callOpenAiCompatible(
                    endpoint = ConfigManager.customOpenAiEndpoint,
                    apiKey = ConfigManager.customOpenAiApiKey,
                    model = ConfigManager.customOpenAiModel,
                    prompt = prompt
                )
            }
            parseLlmVerdicts(rawResponse)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun callGemini(prompt: String): String {
        val model = ConfigManager.geminiModel.trim().ifBlank { "gemini-2.0-flash" }
        val apiKey = ConfigManager.geminiApiKey.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                }
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }

        val req = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("Gemini HTTP ${resp.code}: $body")
            val json = JSONObject(body)
            return json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun callOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        model: String,
        prompt: String
    ): String {
        val payload = JSONObject().apply {
            put("model", model.trim())
            put("temperature", 0.1)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Responde únicamente en formato JSON válido.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val reqBuilder = Request.Builder()
            .url(endpoint.trim())
            .post(payload.toString().toRequestBody(JSON_MEDIA))

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("LLM HTTP ${resp.code}: $body")
            val json = JSONObject(body)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun parseLlmVerdicts(rawText: String): Map<String, FilterVerdict> {
        val result = mutableMapOf<String, FilterVerdict>()
        val clean = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val resultsArray = if (clean.startsWith("[")) {
            JSONArray(clean)
        } else {
            val obj = JSONObject(clean)
            obj.optJSONArray("results") ?: JSONArray()
        }

        for (i in 0 until resultsArray.length()) {
            val item = resultsArray.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            val allowed = item.optBoolean("allowed", true)
            val score = item.optInt("score", if (allowed) 10 else 90)
            val reason = item.optString("reason", if (allowed) "Aprobado por Juez IA" else "Bloqueado por Juez IA (Brainrot)")
            result[id] = FilterVerdict(
                allowed = allowed,
                score = score,
                layer = "LLM_AI (${ConfigManager.llmProvider.name})",
                reason = reason
            )
        }
        return result
    }
}
