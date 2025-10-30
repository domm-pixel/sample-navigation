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
    fun speak(message: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized || !isEnabled) return

        tts?.speak(message + " 입니다", queueMode, null, "navigation_guide")
        Timber.d("🔊 Speaking: $message 입니다")
    }
    
    /**
     * 안내 시작 알림 (순차 재생)
     * 1. "경로 안내를 시작합니다"
     * 2. 첫 번째 안내 메시지
     */
    fun speakNavigationStart(instruction: Instruction) {
        if (!isInitialized || !isEnabled) return
        
        // 1. 안내 시작 알림 (QUEUE_FLUSH로 즉시 재생)
        tts?.speak("경로 안내를 시작합니다", TextToSpeech.QUEUE_FLUSH, null, "nav_start")
        Timber.d("🔊 Speaking: 경로 안내를 시작합니다")
        
        // 2. 첫 번째 안내 메시지 (QUEUE_ADD로 순차 재생)
        val message = formatInstructionMessage(instruction)
        tts?.speak(message + " 입니다", TextToSpeech.QUEUE_ADD, null, "first_instruction")
        Timber.d("🔊 Speaking (queued): $message 입니다")
    }

    /**
     * 안내 메시지 포맷팅 (실시간 거리 사용)
     */
    private fun formatInstructionMessage(instruction: Instruction): String {
        // 실시간 거리 사용 (distanceToInstruction)
        val distance = instruction.distanceToInstruction
        
        // API 메시지에서 거리 정보 제거
        val cleanMessage = instruction.message
            .replace(Regex("\\d+\\s*킬로미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*미터\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\.?\\d*\\s*km\\s*(후|전방|앞)\\s*"), "")
            .replace(Regex("\\d+\\s*m\\s*(후|전방|앞)\\s*"), "")
            .trim()

        // 실시간 거리로 메시지 생성
        return when {
            distance >= 1000 -> {
                val km = distance / 1000.0
                "${String.format("%.1f", km)}킬로미터 후 $cleanMessage"
            }
            distance >= 500 -> {
                "500미터 후 $cleanMessage"
            }
            distance >= 300 -> {
                "300미터 후 $cleanMessage"
            }
            distance >= 100 -> {
                val hm = (distance / 100) * 100  // 100m 단위로 반올림
                "${hm}미터 후 $cleanMessage"
            }
            distance >= 50 -> {
                "곧 $cleanMessage"
            }
            else -> cleanMessage
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
