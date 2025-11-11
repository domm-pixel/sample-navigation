package com.dom.samplenavigation.navigation.manager

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.annotation.RequiresPermission
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.dom.samplenavigation.navigation.model.Instruction
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.dom.samplenavigation.navigation.model.NavigationState
import com.dom.samplenavigation.utils.PermissionUtils
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.*

/**
 * 네비게이션을 관리하는 매니저 클래스
 */
class NavigationManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    
    private val _navigationState = MutableLiveData(NavigationState())
    val navigationState: LiveData<NavigationState> = _navigationState
    
    private val _currentInstruction = MutableLiveData<Instruction?>()
    val currentInstruction: LiveData<Instruction?> = _currentInstruction
    
    // 음성 안내 트리거용 LiveData
    private val _shouldPlayVoice = MutableLiveData<Boolean>()
    val shouldPlayVoice: LiveData<Boolean> = _shouldPlayVoice
    
    // 안내 시작 알림 트리거용 LiveData
    private val _shouldPlayNavigationStart = MutableLiveData<Boolean>()
    val shouldPlayNavigationStart: LiveData<Boolean> = _shouldPlayNavigationStart
    
    private val _permissionRequired = MutableLiveData<Boolean>()
    val permissionRequired: LiveData<Boolean> = _permissionRequired
    
    private var currentRoute: NavigationRoute? = null
    private var currentLocation: LatLng? = null
    private var currentInstructionIndex = 0
    private var isNavigating = false
    private var lastAnnouncedInstruction: String? = null
    private var lastAnnouncedDistance: Int = -1  // 마지막으로 안내한 거리 구간
    
    // 안정적인 베어링 계산을 위한 변수들
    private var currentBearing: Float = 0f
    private var previousLocation: Location? = null
    
    companion object {
        private const val ACCURACY_BAD_M = 20f
        private const val MIN_MOVE_DISTANCE_M = 3f
        private const val TELEPORT_RESET_M = 100f
        private const val MAX_BEARING_JUMP_DEG = 45f
        private const val MAX_STEP_DEG = 15f
        private const val EMA_ALPHA_FAST = 0.3f
        private const val EMA_ALPHA_SLOW = 0.1f
        private const val SPEED_STATIONARY = 1.0f
    }
    
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val latLng = LatLng(location.latitude, location.longitude)
            currentLocation = latLng
            
            if (isNavigating) {
                updateNavigation(latLng, location)
            }
            
            // 이전 위치 저장
            previousLocation = location
        }
        
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    
    /**
     * 네비게이션 시작
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun startNavigation(route: NavigationRoute) {
        // 권한 체크
        if (!PermissionUtils.hasLocationPermission(context)) {
            _permissionRequired.value = true
            Timber.w("📍 Location permission required")
            return
        }
        
        currentRoute = route
        isNavigating = true
        currentInstructionIndex = 0
        lastAnnouncedDistance = -1  // 초기화
        
        _navigationState.value = NavigationState(
            isNavigating = true,
            currentRoute = route
        )
        
        // 위치 업데이트 시작
        startLocationUpdates()
        
        // 첫 번째 안내 메시지 설정
        updateCurrentInstruction()
        
        // 🔊 안내 시작 알림 트리거 ("경로 안내를 시작합니다" + 첫 안내)
        _shouldPlayNavigationStart.value = true
        Timber.d("🔊 Navigation start announcement triggered")
        
        Timber.d("🚀 Navigation started with ${route.instructions.size} instructions")
    }
    
    /**
     * 네비게이션 중지
     */
    fun stopNavigation() {
        isNavigating = false
        currentRoute = null
        currentInstructionIndex = 0
        lastAnnouncedInstruction = null
        lastAnnouncedDistance = -1
        currentBearing = 0f
        previousLocation = null
        
        _navigationState.value = NavigationState(isNavigating = false)
        _currentInstruction.value = null
        _permissionRequired.value = false
        
        // 위치 업데이트 중지
        stopLocationUpdates()
        
        Timber.d("🛑 Navigation stopped")
    }
    
    /**
     * 권한이 허용된 후 네비게이션 재시작
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun retryNavigation() {
        val route = currentRoute
        if (route != null && PermissionUtils.hasLocationPermission(context)) {
            _permissionRequired.value = false
            startNavigation(route)
        }
    }
    
    /**
     * 위치 업데이트 시작
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 1초마다 업데이트
                1f,    // 1미터 이동시 업데이트
                locationListener
            )
        } catch (e: SecurityException) {
            Timber.e("Location permission not granted: ${e.message}")
        }
    }
    
    /**
     * 위치 업데이트 중지
     */
    private fun stopLocationUpdates() {
        locationManager.removeUpdates(locationListener)
    }
    
    /**
     * 현재 위치 업데이트 (외부에서 호출)
     */
    fun updateCurrentLocation(latLng: LatLng) {
        currentLocation = latLng
        
        // 네비게이션 상태 업데이트
        _navigationState.value = _navigationState.value?.copy(currentLocation = latLng)
        
        if (isNavigating) {
            // 네비게이션 업데이트 (Location 객체 없이)
            val route = currentRoute ?: return
            
            // 현재 안내 지점까지의 거리 계산
            val currentInstruction = route.instructions.getOrNull(currentInstructionIndex)
            if (currentInstruction != null) {
                val distance = calculateDistance(latLng, currentInstruction.location).toInt()
                
                // 🔄 실시간 거리 업데이트 (항상 업데이트)
                _currentInstruction.value = currentInstruction.copy(distanceToInstruction = distance)
                
                // 🔊 단계별 음성 안내 (특정 구간 진입 시에만 음성 재생)
                when {
                    distance <= 50 && lastAnnouncedDistance > 50 -> {
                        lastAnnouncedDistance = 50
                        _shouldPlayVoice.value = true
                        Timber.d("📢 [즉시] 현재 거리: ${distance}m - VOICE TRIGGERED")
                    }
                    distance in 51..150 && lastAnnouncedDistance > 100 -> {
                        lastAnnouncedDistance = 100
                        _shouldPlayVoice.value = true
                        Timber.d("📢 [100m 구간] 현재 거리: ${distance}m - VOICE TRIGGERED")
                    }
                    distance in 151..400 && lastAnnouncedDistance > 300 -> {
                        lastAnnouncedDistance = 300
                        _shouldPlayVoice.value = true
                        Timber.d("📢 [300m 구간] 현재 거리: ${distance}m - VOICE TRIGGERED")
                    }
                    distance in 401..700 && lastAnnouncedDistance > 500 -> {
                        lastAnnouncedDistance = 500
                        _shouldPlayVoice.value = true
                        Timber.d("📢 [500m 구간] 현재 거리: ${distance}m - VOICE TRIGGERED")
                    }
                    distance > 700 && lastAnnouncedDistance == -1 -> {
                        // 최초 안내 시작 (700m 이상일 때)
                        lastAnnouncedDistance = 1000
                        _shouldPlayVoice.value = true
                        Timber.d("📢 [초기 안내] 현재 거리: ${distance}m - VOICE TRIGGERED")
                    }
                }
                
                // 다음 안내로 이동 (30미터 이내)
                if (distance <= 30) {
                    currentInstructionIndex++
                    lastAnnouncedDistance = -1
                    updateCurrentInstruction()
                }
            }
            
            // 진행률 계산
            val progress = calculateProgress(latLng, route)
            
            // 남은 거리 계산
            val remainingDistance = calculateRemainingDistance(latLng, route)
            
            // 네비게이션 상태 업데이트
            _navigationState.value = NavigationState(
                isNavigating = true,
                currentLocation = latLng,
                currentInstruction = currentInstruction,
                nextInstruction = route.instructions.getOrNull(currentInstructionIndex + 1),
                remainingDistance = remainingDistance,
                progress = progress,
                currentRoute = route
            )
            
            Timber.d("📍 Navigation updated with currentLocation: $latLng")
        }
    }
    
    /**
     * 네비게이션 업데이트
     */
    private fun updateNavigation(location: LatLng, locationObj: Location) {
        val route = currentRoute ?: return
        
        // 현재 안내 지점까지의 거리 계산
        val currentInstruction = route.instructions.getOrNull(currentInstructionIndex)
        if (currentInstruction != null) {
            val distance = calculateDistance(location, currentInstruction.location).toInt()
            
            // 🔄 실시간 거리 업데이트 (항상 업데이트)
            _currentInstruction.value = currentInstruction.copy(distanceToInstruction = distance)
            
            // 🔊 단계별 음성 안내 (특정 구간 진입 시에만 음성 재생)
            when {
                distance <= 50 && lastAnnouncedDistance > 50 -> {
                    lastAnnouncedDistance = 50
                    _shouldPlayVoice.value = true
                    Timber.d("📢 [즉시] 현재 거리: ${distance}m - VOICE TRIGGERED")
                }
                distance in 51..150 && lastAnnouncedDistance > 100 -> {
                    lastAnnouncedDistance = 100
                    _shouldPlayVoice.value = true
                    Timber.d("📢 [100m 구간] 현재 거리: ${distance}m - VOICE TRIGGERED")
                }
                distance in 151..400 && lastAnnouncedDistance > 300 -> {
                    lastAnnouncedDistance = 300
                    _shouldPlayVoice.value = true
                    Timber.d("📢 [300m 구간] 현재 거리: ${distance}m - VOICE TRIGGERED")
                }
                distance in 401..700 && lastAnnouncedDistance > 500 -> {
                    lastAnnouncedDistance = 500
                    _shouldPlayVoice.value = true
                    Timber.d("📢 [500m 구간] 현재 거리: ${distance}m - VOICE TRIGGERED")
                }
                distance > 700 && lastAnnouncedDistance == -1 -> {
                    // 최초 안내 시작 (700m 이상일 때)
                    lastAnnouncedDistance = 1000
                    _shouldPlayVoice.value = true
                    Timber.d("📢 [초기 안내] 현재 거리: ${distance}m - VOICE TRIGGERED")
                }
            }
            
            // 다음 안내로 이동 (30미터 이내)
            if (distance <= 30) {
                currentInstructionIndex++
                lastAnnouncedDistance = -1  // 다음 안내를 위해 초기화
                updateCurrentInstruction()
                Timber.d("✅ Moving to next instruction (${currentInstructionIndex})")
            }
        }
        
        // 진행률 계산
        val progress = calculateProgress(location, route)
        
        // 남은 거리 계산
        val remainingDistance = calculateRemainingDistance(location, route)
        
        // 디버깅 로그
        Timber.d("📍 Navigation Update:")
        Timber.d("   Current Location: $location")
        Timber.d("   Remaining Distance: ${remainingDistance}m")
        Timber.d("   Progress: ${(progress * 100).toInt()}%")
        Timber.d("   Total Distance: ${route.summary.totalDistance}m")
        
        // 네비게이션 상태 업데이트
        _navigationState.value = NavigationState(
            isNavigating = true,
            currentLocation = location,
            currentInstruction = currentInstruction,
            nextInstruction = route.instructions.getOrNull(currentInstructionIndex + 1),
            remainingDistance = remainingDistance,
            progress = progress,
            currentRoute = route
        )
        
        Timber.d("📍 Navigation state updated with currentLocation: $location")
    }
    
    /**
     * 현재 안내 메시지 업데이트
     */
    private fun updateCurrentInstruction() {
        val route = currentRoute ?: return
        val instruction = route.instructions.getOrNull(currentInstructionIndex)
        
        if (instruction != null) {
            // 현재 위치에서 안내 지점까지의 거리 계산
            val distance = if (currentLocation != null) {
                calculateDistance(currentLocation!!, instruction.location).toInt()
            } else {
                instruction.distance
            }
            
            // 거리 정보를 포함한 instruction 생성
            val updatedInstruction = instruction.copy(distanceToInstruction = distance)
            _currentInstruction.value = updatedInstruction
            
            Timber.d("📢 Instruction updated: ${instruction.message} (distance: ${distance}m)")
        } else {
            _currentInstruction.value = instruction
        }
    }
    
    /**
     * 두 지점 간의 거리 계산 (미터)
     */
    private fun calculateDistance(latLng1: LatLng, latLng2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            latLng1.latitude, latLng1.longitude,
            latLng2.latitude, latLng2.longitude,
            results
        )
        return results[0]
    }
    
    /**
     * 진행률 계산 (0.0 ~ 1.0)
     */
    private fun calculateProgress(location: LatLng, route: NavigationRoute): Float {
        val totalDistance = route.summary.totalDistance
        if (totalDistance <= 0) return 0f
        
        // 현재 위치에서 목적지까지의 거리
        val remainingDistance = calculateRemainingDistance(location, route)
        
        return (totalDistance - remainingDistance).toFloat() / totalDistance
    }
    
    /**
     * 남은 거리 계산 (간단한 방법)
     */
    private fun calculateRemainingDistance(location: LatLng, route: NavigationRoute): Int {
        // 목적지까지의 직선 거리 계산 (실제 네비게이션에서는 이 방법이 더 실용적)
        val destination = route.summary.endLocation
        val directDistance = calculateDistance(location, destination)
        
        // 직선 거리를 남은 거리로 사용 (실제 네비게이션에서는 이 방법이 더 정확함)
        return directDistance.toInt()
    }
    
    /**
     * 현재 위치에서 다음 안내까지의 방향 계산 (도)
     */
    fun getBearingToNextInstruction(): Float {
        val current = currentLocation ?: return 0f
        val route = currentRoute ?: return 0f
        
        val nextInstruction = route.instructions.getOrNull(currentInstructionIndex)
        if (nextInstruction == null) return 0f
        
        return calculateBearing(current, nextInstruction.location)
    }
    
    /**
     * 안정적인 베어링 계산
     */
    fun calculateStableBearing(location: Location): Float {
        val accuracy = location.accuracy
        val prev = previousLocation
        val distance = prev?.distanceTo(location) ?: Float.NaN
        
        Timber.d(" 베어링 계산 시작 - 정확도: ${accuracy}m, GPS베어링: ${location.bearing}도, speed=${location.speed}m/s")
        
        // 0) GPS 정확도가 너무 나쁘면 기존 베어링 유지
        if (accuracy.isFinite() && accuracy > ACCURACY_BAD_M) {
            Timber.d(" GPS 정확도 낮음 (${accuracy}m) → 베어링 유지: $currentBearing")
            previousLocation = location
            return currentBearing
        }
        
        // 1) 후보 베어링 계산: 가능한 경우 '이동 방향' 우선
        var candidate = location.bearing
        if (prev != null) {
            if (distance.isFinite() && distance >= MIN_MOVE_DISTANCE_M) {
                candidate = prev.bearingTo(location)
                Timber.d(" 이동 기반 실제 방향 사용: ${candidate}도 (distance=${distance}m)")
            } else {
                Timber.d(" 이동 거리 짧음 (< ${MIN_MOVE_DISTANCE_M}m) → 베어링 유지: $currentBearing")
                previousLocation = location
                return currentBearing
            }
        } else {
            // 첫 샷: 이전 위치가 없으면 GPS bearing 사용(없으면 유지)
            if (!candidate.isFinite() || candidate == 0f) {
                Timber.d(" 이전 위치 없음 & 유효한 GPS bearing 없음 → 베어링 유지: $currentBearing")
                previousLocation = location
                return currentBearing
            }
        }
        
        // 2) 텔레포트/대이동 감지 시 현재 베어링을 즉시 재설정
        if (distance.isFinite() && distance >= TELEPORT_RESET_M) {
            currentBearing = normalizeBearingDeg(candidate)
            Timber.d(" 텔레포트 감지 (distance=${distance}m ≥ ${TELEPORT_RESET_M}m) → 베어링 즉시 설정: $currentBearing")
            previousLocation = location
            return currentBearing
        }
        
        // 3) 최단 각도 차 (−180~+180)로 계산해 wrap-around 문제 방지
        val diff = shortestAngleDiffDeg(currentBearing, candidate)
        
        // 4) 급격한 점프 억제: '완전 차단' 대신 점진 회전으로 한 스텝만 이동
        if (abs(diff) > MAX_BEARING_JUMP_DEG) {
            val step = min(abs(diff), MAX_STEP_DEG)
            val signedStep = if (diff >= 0f) step else -step
            currentBearing = normalizeBearingDeg(currentBearing + signedStep)
            Timber.d(" 급격한 베어링 변화 감지 (${diff}도) → 점진 회전 적용(step=${signedStep}도) → $currentBearing")
            previousLocation = location
            return currentBearing
        }
        
        // 5) 속도 기반 EMA(지수이동평균)로 부드럽게 보정
        val alpha = if (location.hasSpeed() && location.speed > SPEED_STATIONARY) EMA_ALPHA_FAST else EMA_ALPHA_SLOW
        val smoothed = normalizeBearingDeg(currentBearing + alpha * diff)
        currentBearing = smoothed
        Timber.d(" 최종 베어링 업데이트: $currentBearing (target=$candidate, diff=$diff, alpha=$alpha)")
        previousLocation = location
        return currentBearing
    }
    
    /**
     * 현재→목표 각도의 최단 차이(−180~+180)를 반환
     */
    private fun shortestAngleDiffDeg(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return diff
    }
    
    /**
     * 각도를 0~360 범위로 정규화
     */
    private fun normalizeBearingDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }
    
    /**
     * 두 지점 간의 방향 계산 (도)
     */
    private fun calculateBearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLng = Math.toRadians(to.longitude - from.longitude)
        
        val y = sin(deltaLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLng)
        
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }
}
