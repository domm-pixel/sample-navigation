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
    private val naverMapApi: NaverMapApi
) {
    companion object {
        private const val NAVER_ROUTE_OPTIONS = "trafast:traoptimal:traavoidtoll"
    }

    // Fetches the navigation path using coordinates directly
    fun getPathWithCoordinates(
        startLat: Double,
        startLng: Double,
        endAddress: String,
        routeOption: String? = null,  // 경로 옵션 (trafast, traoptimal, traavoidtoll)
        carType: Int = 1 // 차량 유형 (1: 소형, 2: 중형, 3: 대형)
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

                // 경로 옵션이 지정되지 않으면 기본값 사용
                val option = routeOption ?: NAVER_ROUTE_OPTIONS

                val response = naverDirectionApi.getPath(
                    startCoordsString,
                    endCoordsString,
                    option = option,
                    cartype = carType
                )
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
    fun getReverseGeocode(
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
    fun getAddressGeocode(
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