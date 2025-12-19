package com.dom.samplenavigation.api.network

import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * 비어있는(length=0)인 Response를 받았을 경우 처리
 */
class NullOnEmptyConverterFactory : Converter.Factory() {
    fun converterFactory() = this

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ) = object : Converter<ResponseBody, Any?> {
        val nextResponseBodyConverter =
            retrofit.nextResponseBodyConverter<Any?>(converterFactory(), type, annotations)

        override fun convert(value: ResponseBody) = if (value.contentLength() != 0L) {
            try {
                nextResponseBodyConverter.convert(value)
            } catch (e: Exception) {
                // Log
//                val event =
//                    CommonTag.makeDefaultSentryEvent(CommonTag.SENTRY_TAG_VALUE_CALL_API)
//                event.level = SentryLevel.INFO
//                event.message =
//                    CommonTag.makeLogMessage("NullOnEmptyConverterFactory: ${e.message}")
//                event.setExtra("response body", value.toString())
//                Sentry.captureEvent(event)

                null
            }
        } else {
            null
        }
    }
}