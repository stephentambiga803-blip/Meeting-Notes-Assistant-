package com.example.data.remote

import android.util.Log
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}

object GeminiHelper {
    private const val TAG = "GeminiHelper"

    suspend fun summarizeMeeting(transcript: String): SummaryResult {
        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API key is not configured or contains the default placeholder!")
            return SummaryResult.Error("API Key is missing or invalid. Please set your GEMINI_API_KEY in the AI Studio Secrets panel.")
        }

        val prompt = transcript.ifBlank {
            return SummaryResult.Error("Transcript is empty. Please record or input some meeting content first.")
        }

        val systemPrompt = """
            You are an expert AI meeting notes assistant.
            You must analyze the transcript and generate an executive summary and a separate action items list.
            
            Return your response in two clear sections, separated exactly by this line:
            ======DIVISION======
            
            Section 1: Summary & Core Highlights (in elegant Markdown text)
            Section 2: Checkbox Action Items (formatted text as: [ ] Task description - Owner (Deadline))
            
            Be concise, clear, and action-oriented. Do not include any other conversational preamble.
        """.trimIndent()

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt)))),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt)))
        )

        return try {
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val fullResponseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            
            if (fullResponseText.isNullOrBlank()) {
                SummaryResult.Error("Gemini returned an empty response. Let's try and generate again.")
            } else {
                val parts = fullResponseText.split("======DIVISION======")
                val summary = parts.firstOrNull()?.trim() ?: "No summary generated."
                val actionItemsList = parts.getOrNull(1)?.trim() ?: "No action items extracted."
                SummaryResult.Success(summary, actionItemsList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating meeting summary", e)
            SummaryResult.Error("Failed to reach Gemini: ${e.localizedMessage ?: "Unknown network error"}")
        }
    }
}

sealed class SummaryResult {
    data class Success(val summary: String, val actionItems: String) : SummaryResult()
    data class Error(val message: String) : SummaryResult()
}
