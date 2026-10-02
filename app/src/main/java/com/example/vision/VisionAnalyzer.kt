package com.example.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.AssistantException
import com.example.AssistantLogger
import com.example.ErrorCategory
import com.example.data.AppSettingsManager
import com.example.network.Content
import com.example.network.GenerateContentRequest
import com.example.network.InlineData
import com.example.network.Part
import com.example.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream

enum class VisionTaskType(val label: String) {
    DESCRIBE("Describe Scene"),
    EXTRACT_TEXT("Extract Text (OCR)"),
    READ_QR("Read QR / Barcode"),
    CUSTOM("Custom Question")
}

data class VisionAnalysisResult(
    val explanation: String,
    val taskType: VisionTaskType,
    val detectedText: String? = null,
    val qrCodeContent: String? = null
)

object VisionAnalyzer {

    private const val TAG = "VisionAnalyzer"

    /**
     * Resizes a Bitmap to max dimensions (e.g. max 1024x1024) to keep memory footprint minimal,
     * speed up analysis, and ensure zero persistent storage.
     */
    fun scaleBitmap(bitmap: Bitmap, maxDimension: Int = 1024): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int
        if (width > height) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Converts bitmap into Base64 JPEG bytes directly in memory.
     * Note: As per strict privacy requirements, image bytes are strictly kept in transient memory
     * and never written to disk, databases, or device cache.
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Safe transient loader from content Uri into memory Bitmap.
     */
    fun decodeUriToBitmap(inputStream: InputStream?): Bitmap? {
        return try {
            inputStream?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            AssistantLogger.e(TAG, "Failed to decode image uri", e)
            null
        }
    }

    /**
     * Executes vision analysis using Gemini 2.5 Flash Multimodal.
     * Always explains what's visible in simple, accessible, friendly language.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        taskType: VisionTaskType,
        customPrompt: String? = null
    ): Result<VisionAnalysisResult> = withContext(Dispatchers.IO) {
        val taskId = "VISION_${System.currentTimeMillis()}"
        AssistantLogger.i(taskId, "Starting vision analysis for taskType: ${taskType.name}")

        val apiKey = AppSettingsManager.getActiveApiKey().trim()
        if (apiKey.isEmpty()) {
            AssistantLogger.w(taskId, "No Gemini API key configured for vision analysis")
            return@withContext Result.failure(
                AssistantException(
                    ErrorCategory.PERMISSION_ERROR,
                    "Gemini API key is required for vision analysis. Please configure your key in Settings.",
                    canRetry = false
                )
            )
        }

        // Downscale in-memory bitmap for fast transmission and privacy
        val scaledBitmap = scaleBitmap(bitmap)
        val base64Data = bitmapToBase64(scaledBitmap)

        val taskInstructions = when (taskType) {
            VisionTaskType.DESCRIBE -> """
                Analyze this visual image thoroughly.
                Explain what is visible in simple, friendly, easy-to-understand language.
                Highlight the main subjects, any important background context, colors, or noteworthy activities.
                Keep it concise, natural, and accessible.
            """.trimIndent()

            VisionTaskType.EXTRACT_TEXT -> """
                Look at this visual image and extract all readable text, signs, labels, documents, or titles.
                1. Provide a clear verbatim transcript of all visible text under a section titled 'Extracted Text:'.
                2. Then give a simple, 1-2 sentence plain-language summary of what the text says or means.
            """.trimIndent()

            VisionTaskType.READ_QR -> """
                Analyze this visual image specifically looking for QR codes, barcodes, URL links, Wi-Fi credentials, or digital code patterns.
                If any QR code or barcode is visible:
                - Read and decode its exact content, URL, text, or data string.
                - State clearly: 'QR / Barcode Content: <decoded content>'.
                - Explain in simple plain words what this QR code points to or does.
                If no QR code is found, state politely that no QR code or barcode was detected in this capture.
            """.trimIndent()

            VisionTaskType.CUSTOM -> """
                The user asked a specific question about this image: "${customPrompt ?: "What is this?"}".
                Answer the question accurately based solely on what's visible in the image.
                Explain clearly in simple, friendly language.
            """.trimIndent()
        }

        val systemPrompt = """
            You are MJ, the friendly and intelligent AI Assistant with Vision capabilities.
            Privacy & Trust Mandate: You are analyzing a single live frame explicitly consented to by the user.
            Always explain what's visible in simple, natural, polite language. Never use overly dry or complex technical jargon.
        """.trimIndent()

        val apiResult = com.example.network.safeApiCall {
            withTimeout(45000L) {
                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = taskInstructions),
                                Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Data))
                            ),
                            role = "user"
                        )
                    ),
                    systemInstruction = Content(
                        parts = listOf(Part(text = systemPrompt))
                    ),
                    generationConfig = com.example.network.GenerationConfig(
                        thinkingConfig = com.example.network.ThinkingConfig(thinkingLevel = "low")
                    )
                )

                RetrofitClient.service.generateVisionContent(
                    apiKey = apiKey,
                    request = request
                )
            }
        }

        when (apiResult) {
            is com.example.network.ApiResult.Success -> {
                val responseText = apiResult.data.candidates
                    ?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: return@withContext Result.failure(
                        AssistantException(ErrorCategory.AI_SERVICE_ERROR, "No response generated for this image.", canRetry = true)
                    )

                AssistantLogger.i(taskId, "Vision analysis completed successfully (${responseText.length} chars)")

                Result.success(
                    VisionAnalysisResult(
                        explanation = responseText.trim(),
                        taskType = taskType
                    )
                )
            }
            is com.example.network.ApiResult.Error -> {
                AssistantLogger.w(taskId, "Vision API request failed: ${apiResult.category}")
                com.example.GlobalErrorHandler.handleError(apiResult.category, apiResult.message)
                Result.failure(
                    AssistantException(apiResult.category, apiResult.message, canRetry = true)
                )
            }
        }
    }
}
