package com.dom.samplenavigation.api.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

class NetworkInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val response: Response = chain.proceed(request)

        try {
                // 응답 바디 보존을 위해 peekBody 사용
                val peek = response.peekBody(1024 * 1024) // 최대 1MB까지 검사
                val bodyString = peek.string()
                
                Timber.tag("NetworkInterceptor").d("Checking auth for: ${request.url}")
                
                if (bodyString.isNotEmpty() && (bodyString.trim()
                        .startsWith("{") || bodyString.trim().startsWith("["))
                ) {
                    val jsonElement = JsonParser.parseString(bodyString)
                    val codeValue: String? = when {
                        jsonElement.isJsonObject -> {
                            val obj: JsonObject = jsonElement.asJsonObject
                            when {
                                obj.has("resCode") -> obj.get("resCode").asString
                                obj.has("resultCode") -> obj.get("resultCode").asString
                                else -> null
                            }
                        }

                        else -> null
                    }
                }
        } catch (e: Exception) {
            // 파싱 오류 등은 무시하고 원래 응답 반환
            Timber.tag("NetworkInterceptor").e(e, "Error parsing response body")
        }

        return response
    }
}