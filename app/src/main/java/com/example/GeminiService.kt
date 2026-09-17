package com.example

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class QuotaExceededException(message: String) : Exception(message)

data class ApiKeyInfo(val key: String, val slotNumber: Int, val label: String)

data class AnalysisResponse(
    val text: String,
    val usedKeyLabel: String? = null,
    val success: Boolean = true
)

object GeminiService {
    private const val TAG = "GeminiService"
    const val TIMEOUT_SECONDS = 15L
    
    // Configured for fast mobile responses with strict 15-second total timeout
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun getActiveApiKeys(context: android.content.Context): List<ApiKeyInfo> {
        val prefs = context.getSharedPreferences("fruit_app_prefs", android.content.Context.MODE_PRIVATE)
        val list = mutableListOf<ApiKeyInfo>()
        for (i in 1..5) {
            val key = prefs.getString("custom_gemini_api_key_$i", "") ?: ""
            if (key.isNotBlank()) {
                list.add(ApiKeyInfo(key.trim(), i, "Key $i"))
            }
        }
        if (list.isEmpty()) {
            val defaultKey = BuildConfig.GEMINI_API_KEY.trim()
            list.add(ApiKeyInfo(defaultKey, 0, "Default Key"))
        }
        return list
    }

    suspend fun analyzeFruitImage(
        apiKeys: List<ApiKeyInfo>,
        base64Image: String
    ): AnalysisResponse = withContext(Dispatchers.IO) {
        val validKeys = apiKeys.filter {
            val key = it.key.trim()
            key.isNotEmpty() && key != "YOUR_GEMINI_API_KEY"
        }

        if (validKeys.isEmpty()) {
            return@withContext AnalysisResponse(
                text = "Lỗi: Chưa cấu hình khóa API (GEMINI_API_KEY). Vui lòng cấu hình khóa API trong ứng dụng.",
                usedKeyLabel = null,
                success = false
            )
        }

        var lastError: Throwable? = null
        var isAnyQuotaExceeded = false

        for (keyInfo in validKeys) {
            val trimmedKey = keyInfo.key.trim()
            try {
                val resultText = withTimeout(TIMEOUT_SECONDS * 1000L) {
                    performApiCallWithFallback(trimmedKey, base64Image)
                }
                return@withContext AnalysisResponse(
                    text = resultText,
                    usedKeyLabel = keyInfo.label,
                    success = true
                )
            } catch (e: QuotaExceededException) {
                Log.w(TAG, "${keyInfo.label} exceeded quota/rate limit. Falling back to next key...", e)
                lastError = e
                isAnyQuotaExceeded = true
                // Continue to next key
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Request timed out for ${keyInfo.label}", e)
                return@withContext AnalysisResponse(
                    text = "Lỗi: Quá thời gian chờ (15 giây) không nhận được phản hồi từ Gemini API. Vui lòng kiểm tra lại kết nối mạng của thiết bị hoặc thử lại.",
                    usedKeyLabel = keyInfo.label,
                    success = false
                )
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "Socket timeout for ${keyInfo.label}", e)
                return@withContext AnalysisResponse(
                    text = "Lỗi: Hết thời gian chờ phản hồi từ máy chủ (Timeout sau 15 giây). Vui lòng kiểm tra lại đường truyền Internet của bạn.",
                    usedKeyLabel = keyInfo.label,
                    success = false
                )
            } catch (e: InterruptedIOException) {
                Log.e(TAG, "Interrupted I/O timeout for ${keyInfo.label}", e)
                return@withContext AnalysisResponse(
                    text = "Lỗi: Quá thời gian phản hồi (15 giây). Vui lòng thử lại với mạng Wi-Fi hoặc 4G ổn định hơn.",
                    usedKeyLabel = keyInfo.label,
                    success = false
                )
            } catch (e: IOException) {
                Log.e(TAG, "Network IO error for ${keyInfo.label}", e)
                return@withContext AnalysisResponse(
                    text = "Lỗi mạng: Không thể kết nối tới máy chủ Google Gemini. Vui lòng kiểm tra kết nối Internet trên thiết bị.",
                    usedKeyLabel = keyInfo.label,
                    success = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error for ${keyInfo.label}", e)
                return@withContext AnalysisResponse(
                    text = "Đã xảy ra lỗi: ${e.localizedMessage ?: "Không xác định"}. Vui lòng thử lại.",
                    usedKeyLabel = keyInfo.label,
                    success = false
                )
            }
        }

        if (isAnyQuotaExceeded) {
            return@withContext AnalysisResponse(
                text = "Tất cả API key đã hết hạn mức sử dụng, vui lòng thử lại sau.",
                usedKeyLabel = null,
                success = false
            )
        }

        val errMsg = lastError?.localizedMessage ?: "Không thể kết nối đến API"
        return@withContext AnalysisResponse(
            text = "Đã xảy ra lỗi: $errMsg. Vui lòng thử lại.",
            usedKeyLabel = null,
            success = false
        )
    }

    private fun performApiCallWithFallback(apiKey: String, base64Image: String): String {
        // Models to try in order of priority
        val candidateModels = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-3.5-flash")
        var lastException: Throwable = Exception("Không thể nhận diện hình ảnh")

        for (model in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val responseResult = executeRequest(url, base64Image)
            
            if (responseResult.isSuccess) {
                return responseResult.getOrThrow()
            } else {
                val exception = responseResult.exceptionOrNull() ?: Exception("Lỗi gọi API")
                lastException = exception
                val error = exception.message ?: "Lỗi gọi API"
                Log.w(TAG, "Model $model failed: $error. Trying next model if available...")
                
                // If it's a quota, auth, or rate limit error, trying other models won't help, throw immediately
                if (exception is QuotaExceededException ||
                    error.contains("API key", ignoreCase = true) ||
                    error.contains("API_KEY_INVALID", ignoreCase = true) ||
                    error.contains("PERMISSION_DENIED", ignoreCase = true)
                ) {
                    throw exception
                }
            }
        }

        throw lastException
    }

    private fun executeRequest(url: String, base64Image: String): Result<String> {
        val prompt = "Đây là ảnh một loại trái cây hoặc rau củ. Hãy trả lời bằng tiếng Việt, ngắn gọn, dễ hiểu, theo đúng cấu trúc sau:\n" +
                "Tên loại quả/rau củ: ...\n" +
                "Thành phần dinh dưỡng chính: ...\n" +
                "Cách ăn/sử dụng tối ưu nhất: ...\n" +
                "Nếu ảnh không rõ là loại trái cây/rau củ nào, hãy nói rõ là không nhận diện được."

        return try {
            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            val partText = JSONObject().apply {
                                put("text", prompt)
                            }
                            val partImage = JSONObject().apply {
                                val inlineDataObj = JSONObject().apply {
                                    put("mimeType", "image/jpeg")
                                    put("data", base64Image)
                                }
                                put("inlineData", inlineDataObj)
                            }
                            put(partText)
                            put(partImage)
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)
            }

            val requestBody = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorStr = response.body?.string() ?: ""
                    Log.e(TAG, "API call failed HTTP ${response.code}: $errorStr")

                    var isQuota = response.code == 429
                    val userMessage = try {
                        val errorJson = JSONObject(errorStr).getJSONObject("error")
                        val status = errorJson.optString("status", "")
                        val message = errorJson.optString("message", "")
                        
                        if (status.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                            message.contains("quota", ignoreCase = true) ||
                            message.contains("limit", ignoreCase = true) ||
                            message.contains("exhausted", ignoreCase = true)
                        ) {
                            isQuota = true
                        }

                        when {
                            response.code == 400 && message.contains("API key not valid", ignoreCase = true) ->
                                "Khóa API không hợp lệ. Vui lòng kiểm tra lại GEMINI_API_KEY."
                            response.code == 403 ->
                                "Không có quyền truy cập API (Mã 403). Hãy đảm bảo Gemini API đã được kích hoạt cho khóa này."
                            response.code == 429 || isQuota ->
                                "Đã vượt quá giới hạn lượt gọi (Mã 429). Vui lòng đợi một lát rồi thử lại."
                            response.code == 404 ->
                                "Mô hình không khả dụng trên tài khoản này (Mã 404)."
                            else -> message.ifBlank { "Mã lỗi HTTP ${response.code}" }
                        }
                    } catch (e: Exception) {
                        "Mã lỗi HTTP ${response.code}"
                    }

                    if (isQuota) {
                        return Result.failure(QuotaExceededException(userMessage))
                    } else {
                        return Result.failure(Exception(userMessage))
                    }
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return Result.failure(Exception("Máy chủ trả về kết quả rỗng."))
                }

                val parsedText = parseGeminiResponse(responseBody)
                Result.success(parsedText)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseGeminiResponse(jsonStr: String): String {
        return try {
            val json = JSONObject(jsonStr)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return "Không tìm thấy kết quả nhận diện từ mô hình."
            }
            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts == null || parts.length() == 0) {
                return "Không có nội dung mô tả nào được trả về."
            }
            parts.getJSONObject(0).optString("text", "Không có dữ liệu văn bản.")
        } catch (e: Exception) {
            Log.e(TAG, "Parsing failed", e)
            "Không thể phân tích phản hồi từ Gemini: ${e.localizedMessage}"
        }
    }
}
