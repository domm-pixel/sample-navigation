package com.dom.samplenavigation.api.navigation.repo

import com.dom.samplenavigation.api.navigation.NaverDirectionApi
import com.dom.samplenavigation.api.navigation.NaverMapApi
import com.dom.samplenavigation.api.navigation.model.ResultPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class NavigationRepository @Inject constructor(
    private val naverDirectionApi: NaverDirectionApi,
    private val naverMapApi : NaverMapApi
) {
    companion object {
        private const val NAVER_ROUTE_OPTIONS = "trafast:traoptimal:traavoidtoll"
    }
    // Fetches the navigation path between two addresses
    fun getPath(
        startAddress: String,
        endAddress: String
    ): Flow<Result<ResultPath>> {
        return flow {
            try {
                // 1. 주소를 좌표로 변환 (지오코딩)
                val startCoords = if (startAddress == "현재 위치") {
                    // 현재 위치는 좌표로 직접 전달받아야 함
                    null // 이 경우는 다른 메서드 사용
                } else {
                    getCoordinatesFromAddress(startAddress)
                }
                val endCoords = getCoordinatesFromAddress(endAddress)

                if (startCoords == null) {
                    emit(Result.failure(Exception("출발지 주소를 찾을 수 없습니다: $startAddress")))
                    return@flow
                }

                if (endCoords == null) {
                    emit(Result.failure(Exception("도착지 주소를 찾을 수 없습니다: $endAddress")))
                    return@flow
                }

                // 2. 좌표로 경로 찾기
                val startCoordsString = "${startCoords.second},${startCoords.first}" // lng,lat 형식
                val endCoordsString = "${endCoords.second},${endCoords.first}" // lng,lat 형식

                val response = naverDirectionApi.getPath(startCoordsString, endCoordsString, NAVER_ROUTE_OPTIONS)
                if (response.isSuccessful) {
                    response.body()?.let {
                        emit(Result.success(it))
                    } ?: emit(Result.failure(Exception("Empty response body")))
                } else {
                    emit(Result.failure(Exception("Error: ${response.code()} ${response.message()}")))
                }
            } catch (e: Exception) {
                emit(Result.failure(e))
            }
        }.catch { e ->
            emit(Result.failure(e))
        }
    }
    
    // Fetches the navigation path using coordinates directly
    fun getPathWithCoordinates(
        startLat: Double,
        startLng: Double,
        endAddress: String
    ): Flow<Result<ResultPath>> {
        return flow {
            try {
                // 1. 도착지 주소를 좌표로 변환 (지오코딩)
                val endCoords = getCoordinatesFromAddress(endAddress)

                if (endCoords == null) {
                    emit(Result.failure(Exception("도착지 주소를 찾을 수 없습니다: $endAddress")))
                    return@flow
                }

                // 2. 좌표로 경로 찾기
                val startCoordsString = "$startLng,$startLat" // lng,lat 형식
                val endCoordsString = "${endCoords.second},${endCoords.first}" // lng,lat 형식

                val response = naverDirectionApi.getPath(startCoordsString, endCoordsString, NAVER_ROUTE_OPTIONS)
                if (response.isSuccessful) {
                    response.body()?.let {
                        emit(Result.success(it))
                    } ?: emit(Result.failure(Exception("Empty response body")))
                } else {
                    emit(Result.failure(Exception("Error: ${response.code()} ${response.message()}")))
                }
            } catch (e: Exception) {
                emit(Result.failure(e))
            }
        }.catch { e ->
            emit(Result.failure(e))
        }
    }
    
    // 주소를 좌표로 변환하는 헬퍼 메서드
    private suspend fun getCoordinatesFromAddress(address: String): Pair<Double, Double>? {
        return try {
            val response = naverMapApi.getAddressGeocode(address)
            if (response.isSuccessful) {
                val geocodeResponse = response.body()
                val addressInfo = geocodeResponse?.addresses?.firstOrNull()
                addressInfo?.let { 
                    Pair(it.y.toDouble(), it.x.toDouble()) // lat, lng
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // get reverse geocode
    fun getReverseGeocode  (
        apiKeyID: String,
        apiKey: String,
        coords: String,
        output: String = "json",
        orders: String = "roadaddr,addr"
    ): Flow<Result<String>> {
        return flow {
            val response = naverMapApi.getReverseGeocode(coords, output, orders)
            if (response.isSuccessful) {
                response.body()?.let {
                    emit(Result.success(it.toString()))
                } ?: emit(Result.failure(Exception("Empty response body")))
            } else {
                emit(Result.failure(Exception("Error: ${response.code()} ${response.message()}")))
            }
        }.catch { e ->
            emit(Result.failure(e))
        }
    }

    // get address geocode
    fun getAddressGeocode (
        apiKeyID: String,
        apiKey: String,
        query: String
    ): Flow<Result<String>> {
        return flow {
            val response = naverMapApi.getAddressGeocode(query)
            if (response.isSuccessful) {
                response.body()?.let {
                    emit(Result.success(it.toString()))
                } ?: emit(Result.failure(Exception("Empty response body")))
            } else {
                emit(Result.failure(Exception("Error: ${response.code()} ${response.message()}")))
            }
        }.catch { e ->
            emit(Result.failure(e))
        }
    }
}