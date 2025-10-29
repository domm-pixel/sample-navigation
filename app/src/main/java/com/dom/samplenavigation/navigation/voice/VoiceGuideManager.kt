package com.dom.samplenavigation.navigation.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dom.samplenavigation.navigation.model.Instruction
import timber.log.Timber
import java.util.*

/**
 * 음성 안내를 관리하는 매니저
 */
class VoiceGuideManager(
    private val context: Context
) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isEnabled = true

    init {
        initializeTTS()
    }

    private fun initializeTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                if (isInitialized) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Timber.d("🔊 TTS started: $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            Timber.d("🔊 TTS completed: $utteranceId")
                        }

                        override fun onError(utteranceId: String?) {
                            Timber.e("🔊 TTS error: $utteranceId")
                        }
                    })
                }

                Timber.d("🔊 TTS initialized: $isInitialized")
            } else {
                Timber.e("🔊 TTS initialization failed")
            }
        }
    }

    /**
     * 안내 메시지 음성 출력
     */
    fun speakInstruction(instruction: Instruction) {
        if (!isInitialized || !isEnabled) return

        val message = formatInstructionMessage(instruction)
        speak(message)
    }

    /**
     * 커스텀 메시지 음성 출력
     */
    fun speak(message: String) {
        if (!isInitialized || !isEnabled) return

        tts?.speak(message + " 입니다", TextToSpeech.QUEUE_FLUSH, null, "navigation_guide")
        Timber.d("🔊 Speaking: $message" + "입니다")
    }

    /**
     * 안내 메시지 포맷팅
     */
    private fun formatInstructionMessage(instruction: Instruction): String {
        val distance = instruction.distance
        val message = instruction.message

        return when {
            distance >= 1000 -> {
                val km = distance / 1000
                "${km}킬로미터 후 $message"
            }

            distance >= 100 -> {
                val hm = distance / 100
                "${hm}백미터 후 $message"
            }

            distance >= 50 -> {
                "50미터 후 $message"
            }

            else -> message
        }
    }

    /**
     * 거리 기반 안내 메시지 생성
     */
    fun getDistanceBasedMessage(instruction: Instruction): String {
        val distance = instruction.distance

        return when {
            distance >= 1000 -> {
                val km = distance / 1000
                if (km >= 2) {
                    "앞으로 ${km}킬로미터 직진하세요"
                } else {
                    "앞으로 1킬로미터 직진하세요"
                }
            }

            distance >= 500 -> {
                "앞으로 500미터 직진하세요"
            }

            distance >= 300 -> {
                "앞으로 300미터 직진하세요"
            }

            distance >= 200 -> {
                "앞으로 200미터 직진하세요"
            }

            distance >= 100 -> {
                "앞으로 100미터 직진하세요"
            }

            distance >= 50 -> {
                "앞으로 50미터 직진하세요"
            }

            else -> instruction.message
        }
    }

    /**
     * 음성 안내 활성화/비활성화
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            stop()
        }
    }

    /**
     * 음성 출력 중지
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 리소스 해제
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * TTS 상태 확인
     */
    fun isReady(): Boolean = isInitialized && isEnabled
}
