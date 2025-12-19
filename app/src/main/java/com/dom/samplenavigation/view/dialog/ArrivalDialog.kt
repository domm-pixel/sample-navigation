package com.dom.samplenavigation.view.dialog

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.dom.samplenavigation.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * 도착 다이얼로그
 * 5초 타이머와 함께 표시되며, 자동 종료 또는 확인 버튼으로 Activity 종료
 */
class ArrivalDialog(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val elapsedTimeMs: Long,
    private val onConfirm: () -> Unit
) {
    private var dialog: AlertDialog? = null
    private var timerJob: Job? = null

    fun show() {
        // 소요 시간 계산
        val elapsedMinutes = (elapsedTimeMs / 1000 / 60).toInt()
        val elapsedHours = elapsedMinutes / 60
        val remainingMinutes = elapsedMinutes % 60

        val timeString = if (elapsedHours > 0) {
            if (remainingMinutes > 0) {
                "${elapsedHours}시간 ${remainingMinutes}분"
            } else {
                "${elapsedHours}시간"
            }
        } else {
            "${elapsedMinutes}분"
        }

        // 커스텀 다이얼로그 레이아웃 로드
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_arrival, null)
        val tvArrivalTitle = dialogView.findViewById<TextView>(R.id.tvArrivalTitle)
        val tvArrivalTime = dialogView.findViewById<TextView>(R.id.tvArrivalTime)
        val progressArrivalTimer = dialogView.findViewById<ProgressBar>(R.id.progressArrivalTimer)
        val tvArrivalTimer = dialogView.findViewById<TextView>(R.id.tvArrivalTimer)
        val btnArrivalConfirm = dialogView.findViewById<Button>(R.id.btnArrivalConfirm)

        tvArrivalTime.text = "소요 시간: $timeString"

        // 다이얼로그 생성
        dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // 확인 버튼 클릭 시 종료
        btnArrivalConfirm.setOnClickListener {
            dismiss()
            onConfirm()
        }

        // 5초 타이머 시작
        timerJob = lifecycleScope.launch {
            var remainingSeconds = 5
            val totalSeconds = 5

            // 초기 상태 설정 (UI 스레드에서)
            activity.runOnUiThread {
                progressArrivalTimer.progress = 100
                tvArrivalTimer.text = "${remainingSeconds}초 후 자동 종료"
            }

            while (remainingSeconds > 0 && isActive) {
                delay(1000L) // 1초 대기
                remainingSeconds--

                // UI 업데이트는 UI 스레드에서 실행
                activity.runOnUiThread {
                    val progress = (remainingSeconds * 100 / totalSeconds)
                    progressArrivalTimer.progress = progress

                    if (remainingSeconds > 0) {
                        tvArrivalTimer.text = "${remainingSeconds}초 후 자동 종료"
                    } else {
                        tvArrivalTimer.text = "자동 종료 중..."
                    }
                }
            }

            // 타이머 종료 시 자동 종료
            if (isActive) {
                activity.runOnUiThread {
                    dismiss()
                    onConfirm()
                }
            }
        }

        // UI 스레드에서 다이얼로그 표시
        activity.runOnUiThread {
            dialog?.show()
        }
    }

    fun dismiss() {
        timerJob?.cancel()
        dialog?.dismiss()
        dialog = null
    }
}

