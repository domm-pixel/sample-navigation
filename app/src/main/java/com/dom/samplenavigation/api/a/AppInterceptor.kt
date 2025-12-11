package com.dom.samplenavigation.api.a

import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import com.dom.samplenavigation.NavApplication
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import timber.log.Timber
import java.io.IOException

class AppInterceptor : Interceptor {
    private val maxRetryCount = 3
    private val initialRetryDelayMillis = 1000L // 초기 재시도 간격 1초
    private val maxRetryDelayMillis = 5000L // 최대 재시도 간격 5초

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        var response: Response? = null
        var attemptCount = 0
        var retryDelay = initialRetryDelayMillis

        do {
            try {
                if (!isConnected()) {
                    val error = NoConnectivityException()
                    handleNetworkError(chain.request(), error)
                    throw error
                }

                val newRequest = buildRequest(chain.request())
                Timber.tag("Network").d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Timber.tag("Network").d("▶ Request: ${newRequest.method} ${newRequest.url}")
                Timber.tag("Network").d("▶ Headers: ${newRequest.headers}")

                // Request Body 로깅
                newRequest.body?.let { body ->
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    val bodyString = buffer.readUtf8()
                    if (bodyString.isNotEmpty() && bodyString.length < 10000) { // 너무 긴 body는 제외
                        Timber.tag("Network").d("▶ Request Body: $bodyString")
                    }
                }

                response = chain.proceed(newRequest)

                Timber.tag("Network").d("◀ Response: ${response.code} ${response.message}")

                // Response Body 로깅
                val responseBody = response.body
                val source = responseBody?.source()
                source?.request(Long.MAX_VALUE) // Buffer the entire body
                val buffer = source?.buffer
                val bodyString = buffer?.clone()?.readUtf8()
                if (!bodyString.isNullOrEmpty() && bodyString.length < 10000) { // 너무 긴 body는 제외
                    Timber.tag("Network").d("◀ Response Body: $bodyString")
                }
                Timber.tag("Network").d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

                when (response.code) {
                    500 -> {
                        // 서버 에러는 재시도하지 않음
                        handleServerError(response, chain.request())
                        return response
                    }

                    404 -> {
                        // 리소스를 찾을 수 없는 경우
                        handleResourceNotFound(response, chain.request())
                        return response
                    }

                    401, 403 -> {
                        // 인증/인가 에러는 재시도하지 않음
                        handleAuthError(response, chain.request())
                        return response
                    }
                }

                if (response.isSuccessful) {
                    return response
                }

                // 기타 에러의 경우 재시도
                attemptCount++
                if (attemptCount < maxRetryCount) {
                    Thread.sleep(retryDelay)
                    retryDelay = minOf(retryDelay * 2, maxRetryDelayMillis) // exponential backoff
                }
            } catch (e: Exception) {
                attemptCount++
                if (attemptCount >= maxRetryCount) {
                    handleNetworkError(chain.request(), e)
                    throw e
                }
                Thread.sleep(retryDelay)
                retryDelay = minOf(retryDelay * 2, maxRetryDelayMillis)
            }
        } while (attemptCount < maxRetryCount)

        throw IOException("Max retry attempts reached")
    }

    private fun buildRequest(originalRequest: Request): Request {
        return originalRequest.newBuilder()
            .addHeader("Content-Type", "application/json")
            .build()
    }

    private fun handleNetworkError(request: Request, error: Exception) {
        Timber.tag("Network").e(error, "Network Error: ${request.url}")
//        val activity = (appInstance.baseContext as? Activity)
//        val className = activity?.javaClass?.simpleName ?: "UnknownCaller"
//
//        // 로컬 DB에 에러 저장
//        mLocalController.putDataOneCase(
//            LocalNetworkError(
//                Index = 0,
//                className = className,
//                errorMessage = request.url.toString(),
//                count = 1
//            )
//        )
//
//        // Sentry에 에러 리포트
//        sentryCapture(
//            CommonTag.SENTRY_TAG_VALUE_NETWORK_LISTENER,
//            SentryLevel.ERROR,
//            "Network Error: ${error.message}",
//            className,
//            request.toString()
//        )
    }

    private fun handleServerError(response: Response, request: Request) {
        Timber.tag("Network").e("Server Error: ${response.code} - ${request.url}")
        // 서버 에러는 Sentry에만 기록하고 사용자에게는 보여주지 않음
//        sentryCapture(
//            CommonTag.SENTRY_TAG_VALUE_CALL_API,
//            SentryLevel.ERROR,
//            "Server Error: ${response.code} - ${response.message}",
//            (appInstance.baseContext as? Activity)?.javaClass?.simpleName ?: "UnknownCaller",
//            request.toString()
//        )
//
//        // 로컬 DB에 에러 기록
//        mLocalController.putDataOneCase(
//            LocalNetworkError(
//                Index = 0,
//                className = (appInstance.baseContext as? Activity)?.javaClass?.simpleName ?: "UnknownCaller",
//                errorMessage = "Server Error: ${response.code} - ${request.url}",
//                count = 1
//            )
//        )
    }

    private fun handleResourceNotFound(response: Response, request: Request) {
        Timber.tag("Network").w("Resource Not Found: ${response.code} - ${request.url}")
        // 404 에러는 Sentry에만 기록
//        sentryCapture(
//            CommonTag.SENTRY_TAG_VALUE_CALL_API,
//            SentryLevel.WARNING,
//            "Resource Not Found: ${response.code} - ${request.url}",
//            (appInstance.baseContext as? Activity)?.javaClass?.simpleName ?: "UnknownCaller",
//            request.toString()
//        )
    }

    private fun handleAuthError(response: Response, request: Request) {
        Timber.tag("Network").w("Authentication Error: ${response.code} - ${request.url}")
        // 인증 에러는 Sentry에만 기록
//        sentryCapture(
//            CommonTag.SENTRY_TAG_VALUE_CALL_API,
//            SentryLevel.WARNING,
//            "Authentication Error: ${response.code} - ${request.url}",
//            (appInstance.baseContext as? Activity)?.javaClass?.simpleName ?: "UnknownCaller",
//            request.toString()
//        )
    }

    private fun isConnected(): Boolean {
        val connectivityManager =
            ContextCompat.getSystemService(
                NavApplication.applicationContext(),
                ConnectivityManager::class.java
            ) ?: return false
        val currentNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(currentNetwork)
        return caps != null
    }
}