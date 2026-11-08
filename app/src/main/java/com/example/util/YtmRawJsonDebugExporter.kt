package com.example.util

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.network.GlobalMusicSearchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TEMPORARY DEBUG FIXTURE: YouTube Music InnerTube Raw JSON Response Exporter.
 *
 * Strictly active ONLY when `BuildConfig.DEBUG == true`.
 *
 * Guarantees:
 * 1. Debug-Only Execution: No-ops completely in release builds (`!BuildConfig.DEBUG`).
 * 2. Memory-Safe Non-Destructive Capture: Uses `response.peekBody(maxCaptureBytes + 1)` with a
 *    configurable cap (`DEFAULT_MAX_CAPTURE_BYTES = 10 MB`) so the original response body is
 *    never consumed, modified, or loaded unbounded into memory.
 * 3. Explicit Truncation Detection: Detects when a response exceeds `maxCaptureBytes` and
 *    marks both `ExportStatus` and the output JSON metadata as truncated rather than complete.
 * 4. Stale-File Prevention: Deletes any prior `ytm_raw_response.json` before starting a new export
 *    and tracks whether the current run actually wrote a fresh file.
 * 5. Escape-Safe Redaction: Recursively sanitizes parsed `JSONObject`/`JSONArray` structures and
 *    applies escape-aware regex (`(?:\\.|[^"\\])*`) so escaped quotes (`\"`) or backslashes (`\\`)
 *    cannot leak sensitive values.
 */
object YtmRawJsonDebugExporter {

    private const val TAG = "YtmRawJsonDebug"
    const val DEFAULT_DEBUG_QUERY = "Shape of You"
    const val OUTPUT_FILE_NAME = "ytm_raw_response.json"
    const val DEFAULT_MAX_CAPTURE_BYTES: Long = 10L * 1024L * 1024L // 10 MB

    private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialCaptureTriggered = AtomicBoolean(false)

    data class ExportStatus(
        val query: String = DEFAULT_DEBUG_QUERY,
        val filePath: String = "",
        val httpCode: Int = 0,
        val isSuccess: Boolean = false,
        val isCompleteRawJson: Boolean = false,
        val isTruncated: Boolean = false,
        val byteSize: Long = 0L,
        val maxCaptureBytes: Long = DEFAULT_MAX_CAPTURE_BYTES,
        val message: String = ""
    )

    private val _lastExportStatus = MutableStateFlow<ExportStatus?>(null)
    val lastExportStatus: StateFlow<ExportStatus?> = _lastExportStatus.asStateFlow()

    private val SENSITIVE_KEYS: Set<String> = setOf(
        "visitordata",
        "apikey",
        "api_key",
        "key",
        "access_token",
        "refresh_token",
        "id_token",
        "authorization",
        "cookie",
        "set-cookie",
        "sapisid",
        "hsid",
        "ssid",
        "apisid",
        "sid",
        "oauth_token",
        "datasyncid",
        "serializedexperimentflags",
        "rollouttoken"
    )

    // Escape-aware JSON key-value redaction regex:
    // Matches `\\.` (any escaped char including `\"` and `\\`) or `[^"\\]` inside the JSON string value
    private val escapedJsonKeyValueRedactionRegex = Regex(
        pattern = """("(?:visitorData|apiKey|api_key|key|access_token|refresh_token|id_token|authorization|cookie|set-cookie|sapisid|hsid|ssid|apisid|sid|oauth_token|datasyncId|serializedExperimentFlags|rolloutToken)"\s*:\s*)"((?:\\.|[^"\\])*)"""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val urlKeyParamRedactionRegex = Regex(
        pattern = """([?&](?:key|api_key|access_token|refresh_token|id_token|token|oauth_token)=)([^&"\s\\]+)""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val bearerTokenRedactionRegex = Regex(
        pattern = """(Bearer\s+)[A-Za-z0-9\-._~+/]+=*""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Triggers the automatic one-shot InnerTube raw JSON export for `"Shape of You"`
     * on `Dispatchers.IO`. Strictly no-ops in release builds (`!BuildConfig.DEBUG`).
     */
    fun triggerShapeOfYouDebugExportOnce(
        context: Context,
        maxCaptureBytes: Long = DEFAULT_MAX_CAPTURE_BYTES
    ) {
        if (!BuildConfig.DEBUG) return
        if (!initialCaptureTriggered.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        debugScope.launch {
            exportRawSearchResponse(
                context = appContext,
                query = DEFAULT_DEBUG_QUERY,
                maxCaptureBytes = maxCaptureBytes
            )
        }
    }

    /**
     * Creates an [OkHttpClient] for debug export.
     * In release builds (`!BuildConfig.DEBUG`), returns a standard client without the debug interceptor.
     */
    fun createDebugOkHttpClient(
        context: Context,
        maxCaptureBytes: Long = DEFAULT_MAX_CAPTURE_BYTES,
        query: String = DEFAULT_DEBUG_QUERY,
        freshWriteTracker: AtomicBoolean? = null
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                createRawCaptureInterceptor(
                    context = context.applicationContext,
                    maxCaptureBytes = maxCaptureBytes,
                    query = query,
                    freshWriteTracker = freshWriteTracker
                )
            )
        }
        return builder.build()
    }

    /**
     * Non-destructive OkHttp [Interceptor] that peeks up to `maxCaptureBytes + 1` bytes
     * from the response without consuming or modifying the original `ResponseBody`.
     * Any internal capture or disk error is isolated and never interrupts `response`.
     */
    fun createRawCaptureInterceptor(
        context: Context,
        maxCaptureBytes: Long = DEFAULT_MAX_CAPTURE_BYTES,
        query: String = DEFAULT_DEBUG_QUERY,
        freshWriteTracker: AtomicBoolean? = null
    ): Interceptor {
        val appContext = context.applicationContext
        val safeMaxBytes = maxCaptureBytes.coerceAtLeast(1024L)

        return Interceptor { chain ->
            val response = chain.proceed(chain.request())

            if (!BuildConfig.DEBUG) {
                return@Interceptor response
            }

            val urlString = chain.request().url.toString()
            if (!urlString.contains("music.youtube.com/youtubei/v1/search") || chain.call().isCanceled()) {
                return@Interceptor response
            }

            // Isolate all capture and I/O errors so the normal search response is never broken
            try {
                val peekLimit = if (safeMaxBytes < Long.MAX_VALUE) safeMaxBytes + 1L else Long.MAX_VALUE
                val peekedBytes = response.peekBody(peekLimit).bytes()
                val isTruncated = peekedBytes.size.toLong() > safeMaxBytes

                val capturedBytes = if (isTruncated) {
                    peekedBytes.copyOf(safeMaxBytes.toInt())
                } else {
                    peekedBytes
                }

                val capturedText = capturedBytes.toString(Charsets.UTF_8)

                val wroteFile = writeCapturedPayloadToDisk(
                    context = appContext,
                    rawPayload = capturedText,
                    httpCode = response.code,
                    httpMessage = response.message,
                    query = query,
                    isTruncated = isTruncated,
                    maxCaptureBytes = safeMaxBytes
                )
                if (wroteFile) {
                    freshWriteTracker?.set(true)
                }
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Isolated debug capture failure (search flow unaffected): ${t.javaClass.simpleName}"
                )
            }

            response
        }
    }

    /**
     * Executes a one-shot search via `GlobalMusicSearchEngine` on `Dispatchers.IO`,
     * deletes any stale export file beforehand, and writes a fresh `ytm_raw_response.json`.
     */
    suspend fun exportRawSearchResponse(
        context: Context,
        query: String = DEFAULT_DEBUG_QUERY,
        maxCaptureBytes: Long = DEFAULT_MAX_CAPTURE_BYTES
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!BuildConfig.DEBUG) {
            return@withContext Result.failure(
                IllegalStateException("YtmRawJsonDebugExporter is disabled in non-debug builds")
            )
        }

        val appContext = context.applicationContext
        val targetFile = resolveTargetFile(appContext)
        val safeMaxBytes = maxCaptureBytes.coerceAtLeast(1024L)

        // Prevent stale file success: remove any previous fixture file before starting a new export
        try {
            if (targetFile.exists() && !targetFile.delete()) {
                val failStatus = ExportStatus(
                    query = query,
                    filePath = targetFile.absolutePath,
                    httpCode = 0,
                    isSuccess = false,
                    isCompleteRawJson = false,
                    isTruncated = false,
                    byteSize = 0L,
                    maxCaptureBytes = safeMaxBytes,
                    message = "FAILURE: Could not remove stale debug file at ${targetFile.absolutePath}"
                )
                _lastExportStatus.value = failStatus
                Log.w(TAG, failStatus.message)
                return@withContext Result.failure(IOException(failStatus.message))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        val freshFileWritten = AtomicBoolean(false)

        try {
            val debugEngine = GlobalMusicSearchEngine(
                client = createDebugOkHttpClient(
                    context = appContext,
                    maxCaptureBytes = safeMaxBytes,
                    query = query,
                    freshWriteTracker = freshFileWritten
                )
            )

            val searchResult = debugEngine.searchStructured(query)

            // If no HTTP response was received (e.g., DNS/socket failure before HTTP response),
            // write a valid JSONObject fallback error payload and still return Result.failure
            if (searchResult.isFailure && !freshFileWritten.get()) {
                val ex = searchResult.exceptionOrNull()
                val fallbackJson = buildFallbackErrorJson(
                    query = query,
                    httpCode = -1,
                    errorType = ex?.javaClass?.simpleName ?: "UnknownException",
                    errorMessage = ex?.localizedMessage ?: "Network request failed before HTTP response"
                )
                writeFallbackErrorFile(
                    targetFile = targetFile,
                    fallbackJson = fallbackJson,
                    query = query,
                    maxCaptureBytes = safeMaxBytes
                )
                return@withContext Result.failure(
                    ex ?: IOException("Search request failed without HTTP response")
                )
            }

            if (freshFileWritten.get() && targetFile.exists() && targetFile.length() > 0L) {
                Result.success(targetFile)
            } else {
                val msg = "FAILURE: Export did not produce a new response file at ${targetFile.absolutePath}"
                Log.w(TAG, msg)
                Result.failure(IOException(msg))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Raw YTM JSON export failed for query -> ${targetFile.absolutePath} (${e.javaClass.simpleName})"
            )
            Result.failure(e)
        }
    }

    /**
     * Redacts sensitive credentials, tokens, API keys, cookies, and visitorData while
     * preserving JSON structure and handling escaped quotes (`\"`) and backslashes (`\\`).
     */
    fun sanitizeRawJson(rawJson: String): String {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return rawJson

        // 1. Primary path: Parse with JSONTokener and recursively redact JSONObject / JSONArray AST
        try {
            val parsed = JSONTokener(trimmed).nextValue()
            when (parsed) {
                is JSONObject -> {
                    redactJsonObjectInPlace(parsed)
                    return parsed.toString(2)
                }
                is JSONArray -> {
                    redactJsonArrayInPlace(parsed)
                    return parsed.toString(2)
                }
            }
        } catch (_: Exception) {
            // Fall through to escape-aware regex sanitization for truncated or non-JSON bodies
        }

        // 2. Fallback path for truncated or non-JSON error payloads:
        // Uses `((?:\\.|[^"\\])*)` so escaped quotes `\"` and backslashes `\\` cannot leak
        return applyRegexRedaction(rawJson)
    }

    private fun redactJsonObjectInPlace(jsonObject: JSONObject) {
        val keysIterator = jsonObject.keys()
        val keyList = mutableListOf<String>()
        while (keysIterator.hasNext()) {
            keyList.add(keysIterator.next())
        }

        for (key in keyList) {
            val normalizedKey = key.lowercase(Locale.ROOT)
            if (normalizedKey in SENSITIVE_KEYS) {
                jsonObject.put(key, "[REDACTED]")
                continue
            }

            val value = jsonObject.opt(key)
            when (value) {
                is JSONObject -> redactJsonObjectInPlace(value)
                is JSONArray -> redactJsonArrayInPlace(value)
                is String -> jsonObject.put(key, redactEmbeddedTokensInString(value))
            }
        }
    }

    private fun redactJsonArrayInPlace(jsonArray: JSONArray) {
        for (i in 0 until jsonArray.length()) {
            val element = jsonArray.opt(i)
            when (element) {
                is JSONObject -> redactJsonObjectInPlace(element)
                is JSONArray -> redactJsonArrayInPlace(element)
                is String -> jsonArray.put(i, redactEmbeddedTokensInString(element))
            }
        }
    }

    private fun redactEmbeddedTokensInString(value: String): String {
        return value
            .replace(urlKeyParamRedactionRegex) { matchResult ->
                "${matchResult.groupValues[1]}[REDACTED]"
            }
            .replace(bearerTokenRedactionRegex) { matchResult ->
                "${matchResult.groupValues[1]}[REDACTED]"
            }
    }

    private fun applyRegexRedaction(input: String): String {
        return input
            .replace(escapedJsonKeyValueRedactionRegex) { matchResult ->
                "${matchResult.groupValues[1]}\"[REDACTED]\""
            }
            .replace(urlKeyParamRedactionRegex) { matchResult ->
                "${matchResult.groupValues[1]}[REDACTED]"
            }
            .replace(bearerTokenRedactionRegex) { matchResult ->
                "${matchResult.groupValues[1]}[REDACTED]"
            }
    }

    private fun buildFallbackErrorJson(
        query: String,
        httpCode: Int,
        errorType: String,
        errorMessage: String
    ): String {
        val safeErrorMessage = redactEmbeddedTokensInString(applyRegexRedaction(errorMessage))
        return JSONObject().apply {
            put("debugExportStatus", "FAILURE_NO_COMPLETE_RESPONSE")
            put("isCompleteRawJson", false)
            put("isTruncated", false)
            put("query", query)
            put("httpStatus", httpCode)
            put("errorType", errorType)
            put("errorMessage", safeErrorMessage)
        }.toString(2)
    }

    private fun buildTruncatedWrapperJson(
        query: String,
        httpCode: Int,
        maxCaptureBytes: Long,
        capturedBytesCount: Int,
        sanitizedPartialBody: String
    ): String {
        return JSONObject().apply {
            put("debugExportStatus", "TRUNCATED_PARTIAL_RESPONSE")
            put("isCompleteRawJson", false)
            put("isTruncated", true)
            put("query", query)
            put("httpStatus", httpCode)
            put("maxCaptureBytes", maxCaptureBytes)
            put("capturedBytes", capturedBytesCount)
            put(
                "truncationNotice",
                "Response exceeded maxCaptureBytes ($maxCaptureBytes bytes). Raw JSON is incomplete and truncated."
            )
            put("truncatedRawBody", sanitizedPartialBody)
        }.toString(2)
    }

    /**
     * Resolves `<externalFilesDir>/ytm_raw_response.json` (with fallback to `context.filesDir`).
     */
    fun resolveTargetFile(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return File(baseDir, OUTPUT_FILE_NAME)
    }

    private fun writeCapturedPayloadToDisk(
        context: Context,
        rawPayload: String,
        httpCode: Int,
        httpMessage: String,
        query: String,
        isTruncated: Boolean,
        maxCaptureBytes: Long
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        val outputFile = resolveTargetFile(context)

        return try {
            val contentToWrite: String
            val isCompleteRawJson: Boolean

            if (rawPayload.isEmpty()) {
                isCompleteRawJson = false
                contentToWrite = JSONObject().apply {
                    put("debugExportStatus", "EMPTY_HTTP_RESPONSE_BODY")
                    put("isCompleteRawJson", false)
                    put("isTruncated", false)
                    put("query", query)
                    put("httpStatus", httpCode)
                    put("httpMessage", redactEmbeddedTokensInString(httpMessage))
                }.toString(2)
            } else if (isTruncated) {
                isCompleteRawJson = false
                val sanitizedPartial = applyRegexRedaction(rawPayload)
                contentToWrite = buildTruncatedWrapperJson(
                    query = query,
                    httpCode = httpCode,
                    maxCaptureBytes = maxCaptureBytes,
                    capturedBytesCount = rawPayload.toByteArray(Charsets.UTF_8).size,
                    sanitizedPartialBody = sanitizedPartial
                )
            } else {
                isCompleteRawJson = true
                contentToWrite = sanitizeRawJson(rawPayload)
            }

            outputFile.writeText(contentToWrite, Charsets.UTF_8)
            val sizeBytes = outputFile.length()

            val statusLabel = when {
                isTruncated -> "TRUNCATED_PARTIAL_CAPTURE (HTTP $httpCode, $sizeBytes bytes written, cap=$maxCaptureBytes bytes)"
                !isCompleteRawJson -> "EMPTY_BODY_CAPTURE (HTTP $httpCode, $sizeBytes bytes written)"
                httpCode in 200..299 -> "SUCCESS_COMPLETE_RAW_JSON (HTTP $httpCode, $sizeBytes bytes)"
                else -> "HTTP_ERROR_BODY_CAPTURED (HTTP $httpCode, $sizeBytes bytes)"
            }

            val fullMsg = "$statusLabel -> ${outputFile.absolutePath}"
            _lastExportStatus.value = ExportStatus(
                query = query,
                filePath = outputFile.absolutePath,
                httpCode = httpCode,
                isSuccess = true,
                isCompleteRawJson = isCompleteRawJson,
                isTruncated = isTruncated,
                byteSize = sizeBytes,
                maxCaptureBytes = maxCaptureBytes,
                message = fullMsg
            )
            Log.i(TAG, "Raw YTM JSON export: $fullMsg")
            true
        } catch (e: Exception) {
            val failMsg = "FAILURE writing to ${outputFile.absolutePath}: ${e.javaClass.simpleName}"
            _lastExportStatus.value = ExportStatus(
                query = query,
                filePath = outputFile.absolutePath,
                httpCode = httpCode,
                isSuccess = false,
                isCompleteRawJson = false,
                isTruncated = isTruncated,
                byteSize = 0L,
                maxCaptureBytes = maxCaptureBytes,
                message = failMsg
            )
            Log.w(TAG, "Raw YTM JSON export: $failMsg")
            false
        }
    }

    private fun writeFallbackErrorFile(
        targetFile: File,
        fallbackJson: String,
        query: String,
        maxCaptureBytes: Long
    ) {
        if (!BuildConfig.DEBUG) return
        try {
            targetFile.writeText(fallbackJson, Charsets.UTF_8)
            val sizeBytes = targetFile.length()
            val msg = "NETWORK_FAILURE_FALLBACK_WRITTEN ($sizeBytes bytes) -> ${targetFile.absolutePath}"
            _lastExportStatus.value = ExportStatus(
                query = query,
                filePath = targetFile.absolutePath,
                httpCode = -1,
                isSuccess = false,
                isCompleteRawJson = false,
                isTruncated = false,
                byteSize = sizeBytes,
                maxCaptureBytes = maxCaptureBytes,
                message = msg
            )
            Log.w(TAG, "Raw YTM JSON export: $msg")
        } catch (e: Exception) {
            Log.w(TAG, "Raw YTM JSON export fallback write failed: ${e.javaClass.simpleName}")
        }
    }
}
