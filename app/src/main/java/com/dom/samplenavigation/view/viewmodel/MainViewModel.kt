package com.dom.samplenavigation.view.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.dom.samplenavigation.api.navigation.repo.NavigationRepository
import com.dom.samplenavigation.base.BaseViewModel
import com.dom.samplenavigation.navigation.mapper.NavigationMapper
import com.dom.samplenavigation.navigation.model.NavigationRoute
import com.naver.maps.geometry.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val navigationRepository: NavigationRepository
) : BaseViewModel() {

    private val _navigationRoute = MutableLiveData<NavigationRoute?>()
    val navigationRoute: LiveData<NavigationRoute?> = _navigationRoute

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    var destinationAddress: String? = null

    fun searchPath(startLocation: LatLng, destination: String) {
        Timber.d("🔍 Searching path from $startLocation to $destination")

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null
                _navigationRoute.value = null

                navigationRepository.getPathWithCoordinates(
                    startLocation.latitude,
                    startLocation.longitude,
                    destination
                ).collect { result ->
                    result.onSuccess { resultPath ->
                        val navigationRoute = NavigationMapper.mapToNavigationRoute(resultPath)
                        if (navigationRoute != null) {
                            _navigationRoute.value = navigationRoute
                            destinationAddress = destination
                            Timber.d("✅ Path searched successfully: ${navigationRoute.summary.totalDistance}m")
                        } else {
                            _errorMessage.value = "경로 데이터를 처리할 수 없습니다."
                            Timber.e("❌ Failed to map route data")
                        }
                    }.onFailure { exception ->
                        _errorMessage.value = "경로를 찾을 수 없습니다: ${exception.message}"
                        Timber.e("❌ Path search failed: ${exception.message}")
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "경로 검색 중 오류가 발생했습니다: ${e.message}"
                Timber.e("💥 Exception in searchPath: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}