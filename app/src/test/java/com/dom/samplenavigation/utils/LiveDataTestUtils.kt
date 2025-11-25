package com.dom.samplenavigation.utils

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * LiveData 테스트를 위한 유틸리티 함수들
 */

/**
 * LiveData의 값을 가져오는 헬퍼 함수
 * 테스트에서 LiveData의 현재 값을 동기적으로 가져올 수 있습니다.
 */
fun <T> LiveData<T>.getOrAwaitValue(
    time: Long = 2,
    timeUnit: TimeUnit = TimeUnit.SECONDS
): T {
    // 이미 값이 있는 경우 즉시 반환
    val currentValue = this.value
    if (currentValue != null) {
        @Suppress("UNCHECKED_CAST")
        return currentValue as T
    }
    
    var data: T? = null
    val latch = CountDownLatch(1)
    val observer = object : Observer<T> {
        override fun onChanged(value: T) {
            data = value
            latch.countDown()
            this@getOrAwaitValue.removeObserver(this)
        }
    }

    this.observeForever(observer)

    // 값이 설정될 때까지 대기
    if (!latch.await(time, timeUnit)) {
        this.removeObserver(observer)
        throw TimeoutException("LiveData value was never set. Current value: ${this.value}")
    }

    @Suppress("UNCHECKED_CAST")
    return data as T
}

/**
 * LiveData의 모든 값을 수집하는 헬퍼 함수
 * 여러 번 값이 변경되는 경우를 테스트할 때 사용
 */
fun <T> LiveData<T>.observeForTesting(
    block: (T) -> Unit
) {
    val observer = Observer<T> { value ->
        block(value)
    }
    this.observeForever(observer)
}

