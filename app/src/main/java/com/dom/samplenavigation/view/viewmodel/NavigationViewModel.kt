package com.dom.samplenavigation.view.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.dom.samplenavigation.api.navigation.repo.NavigationRepository
import com.dom.samplenavigation.api.telemetry.model.VehicleLocationPayload
import com.dom.samplenavigation.api.telemetry.repo.TelemetryRepository
import com.dom.samplenavigation.base.BaseViewModel
import com.dom.samplenavigation.navigation.mapper.NavigationMapper
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.naver.maps.geometry.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationRepository: NavigationRepository,
    private val telemetryRepository: TelemetryRepository
) : BaseViewModel() {

    private val _navigationRoute = MutableLiveData<NavigationRoute?>()
    val navigationRoute: LiveData<NavigationRoute?> = _navigationRoute

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private var startLocation: LatLng? = null
    private var destinationAddress: String? = null

    fun startNavigation() {
        val start = startLocation
        val destination = destinationAddress
        
        if (start == null || destination == null) {
            _errorMessage.value = "출발지 또는 목적지 정보가 없습니다."
            return
        }
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                
                Timber.d("🚀 Starting navigation from $start to $destination")
                
                navigationRepository.getPathWithCoordinates(
                    start.latitude,
                    start.longitude,
                    destination
                ).collect { result ->
                    result.onSuccess { resultPath ->
                        val navigationRoute = NavigationMapper.mapToNavigationRoute(resultPath)
                        if (navigationRoute != null) {
                            _navigationRoute.value = navigationRoute
                            Timber.d("Navigation route loaded successfully")
                            Timber.d("Route info: ${navigationRoute.instructions.size} instructions, ${navigationRoute.summary.totalDistance}m total distance")
                        } else {
                            _errorMessage.value = "경로 데이터를 처리할 수 없습니다."
                            Timber.e("❌ Failed to map route data")
                        }
                    }.onFailure { exception ->
                        _errorMessage.value = "경로를 찾을 수 없습니다: ${exception.message}"
                        Timber.e("❌ Navigation failed: ${exception.message}")
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "네비게이션 시작 중 오류가 발생했습니다: ${e.message}"
                Timber.e("💥 Exception in startNavigation: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun stopNavigation() {
        _navigationRoute.value = null
        Timber.d("🛑 Navigation stopped")
    }

    fun setRoute(start: LatLng, destination: String) {
        startLocation = start
        destinationAddress = destination
        Timber.d("Route set: $start -> $destination")
    }

    /**
     * 경로 재검색 (현재 위치에서 목적지로 새 경로 검색)
     */
    fun reroute(currentLocation: LatLng) {
        val destination = destinationAddress
        
        if (destination == null) {
            _errorMessage.value = "목적지 정보가 없습니다."
            return
        }
        
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                
                Timber.d("🔄 Rerouting from $currentLocation to $destination")
                
                navigationRepository.getPathWithCoordinates(
                    currentLocation.latitude,
                    currentLocation.longitude,
                    destination
                ).collect { result ->
                    result.onSuccess { resultPath ->
                        val navigationRoute = NavigationMapper.mapToNavigationRoute(resultPath)
                        if (navigationRoute != null) {
                            _navigationRoute.value = navigationRoute
                            // 재검색 시 시작 위치 업데이트
                            startLocation = currentLocation
                            Timber.d("Route rerouted successfully")
                            Timber.d("New route info: ${navigationRoute.instructions.size} instructions, ${navigationRoute.summary.totalDistance}m total distance")
                        } else {
                            _errorMessage.value = "경로 데이터를 처리할 수 없습니다."
                            Timber.e("❌ Failed to map rerouted route data")
                        }
                    }.onFailure { exception ->
                        _errorMessage.value = "경로를 다시 찾을 수 없습니다: ${exception.message}"
                        Timber.e("❌ Reroute failed: ${exception.message}")
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "경로 재검색 중 오류가 발생했습니다: ${e.message}"
                Timber.e("💥 Exception in reroute: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun sendTelemetry(vehicleId: Int, payload: VehicleLocationPayload) {
        viewModelScope.launch {
            telemetryRepository.sendLocation(vehicleId, payload)
                .onFailure { Timber.w("Telemetry send failed: ${it.message}") }
                .onSuccess { Timber.d("Telemetry sent") }
        }
    }
}
