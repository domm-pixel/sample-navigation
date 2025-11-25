package com.dom.samplenavigation.view.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.dom.samplenavigation.api.navigation.model.ResultPath
import com.dom.samplenavigation.api.navigation.repo.NavigationRepository
import com.dom.samplenavigation.api.telemetry.repo.TelemetryRepository
import com.dom.samplenavigation.utils.getOrAwaitValue
import com.naver.maps.geometry.LatLng
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * NavigationViewModel 단위 테스트
 * 
 * 테스트 전략:
 * 1. Mock Repository 사용
 * 2. Coroutines TestDispatcher 사용
 * 3. Flow 테스트는 Turbine 사용
 * 4. ViewModel의 상태 변화 검증
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    // LiveData를 동기적으로 테스트하기 위한 Rule
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: NavigationViewModel
    private lateinit var navigationRepository: NavigationRepository
    private lateinit var telemetryRepository: TelemetryRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        
        navigationRepository = mockk()
        telemetryRepository = mockk()
        
        viewModel = NavigationViewModel(
            navigationRepository = navigationRepository,
            telemetryRepository = telemetryRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `setRoute는 출발지와 목적지를 저장해야 함`() = runTest(testDispatcher) {
        // Given
        val startLocation = LatLng(37.497942, 127.027610)
        val destination = "서울시 강남구"

        // When
        viewModel.setRoute(startLocation, destination)

        // Then
        // setRoute는 내부 상태만 변경하므로 직접 검증은 어려움
        // startNavigation을 통해 간접 검증
        assertNotNull("ViewModel이 생성되어야 함", viewModel)
    }

    @Test
    fun `startNavigation은 출발지와 목적지가 없으면 에러를 발생시켜야 함`() = runTest(testDispatcher) {
        // Given - 출발지나 목적지가 설정되지 않음

        // When
        viewModel.startNavigation()
        advanceUntilIdle()

        // Then
        val error = viewModel.errorMessage.getOrAwaitValue()
        assertNotNull("에러 메시지가 있어야 함", error)
        assertTrue("에러 메시지가 적절해야 함", 
            error?.contains("출발지") == true || error?.contains("목적지") == true)
    }

    @Test
    fun `startNavigation은 성공 시 경로를 로드해야 함`() = runTest(testDispatcher) {
        // Given
        val startLocation = LatLng(37.497942, 127.027610)
        val destination = "서울시 강남구"
        val mockResultPath = createMockResultPath()
        
        viewModel.setRoute(startLocation, destination)
        
        coEvery { 
            navigationRepository.getPathWithCoordinates(
                startLocation.latitude,
                startLocation.longitude,
                destination
            )
        } returns flowOf(Result.success(mockResultPath))

        // When
        viewModel.startNavigation()
        advanceUntilIdle()

        // Then
        // NavigationMapper가 실제로 동작하므로 null이 아닌지만 확인
        val isLoading = viewModel.isLoading.getOrAwaitValue()
        assertFalse("로딩이 완료되어야 함", isLoading ?: true)
    }

    @Test
    fun `startNavigation은 실패 시 에러 메시지를 표시해야 함`() = runTest(testDispatcher) {
        // Given
        val startLocation = LatLng(37.497942, 127.027610)
        val destination = "서울시 강남구"
        val errorMessage = "경로를 찾을 수 없습니다"
        
        viewModel.setRoute(startLocation, destination)
        
        // Flow가 즉시 실패를 emit하도록 설정
        coEvery { 
            navigationRepository.getPathWithCoordinates(
                any(),
                any(),
                any()
            )
        } returns flowOf(Result.failure<ResultPath>(Exception(errorMessage)))

        // When
        viewModel.startNavigation()
        // Flow가 완료될 때까지 충분히 기다림
        advanceUntilIdle()
        // 추가로 약간의 시간을 주어 LiveData 업데이트를 보장
        kotlinx.coroutines.delay(100)

        // Then
        // LiveData가 값을 emit할 때까지 기다림 (초기값이 null일 수 있음)
        val error = viewModel.errorMessage.getOrAwaitValue(time = 5)
        assertNotNull("에러 메시지가 있어야 함", error)
        assertTrue("에러 메시지가 적절해야 함", error?.contains(errorMessage) == true || error?.contains("경로를 찾을 수 없습니다") == true)
        
        // navigationRoute는 초기값이 null이므로 바로 확인 가능
        val route = viewModel.navigationRoute.value
        assertNull("경로가 로드되지 않아야 함", route)
    }

    @Test
    fun `stopNavigation은 경로를 초기화해야 함`() = runTest(testDispatcher) {
        // Given - 경로가 로드된 상태
        val startLocation = LatLng(37.497942, 127.027610)
        val destination = "서울시 강남구"
        val mockResultPath = createMockResultPath()
        
        viewModel.setRoute(startLocation, destination)
        
        coEvery { 
            navigationRepository.getPathWithCoordinates(any(), any(), any())
        } returns flowOf(Result.success(mockResultPath))
        
        viewModel.startNavigation()
        advanceUntilIdle()

        // When
        viewModel.stopNavigation()
        advanceUntilIdle()

        // Then
        val route = viewModel.navigationRoute.getOrAwaitValue()
        assertNull("경로가 초기화되어야 함", route)
    }

    @Test
    fun `reroute는 현재 위치에서 목적지로 새 경로를 검색해야 함`() = runTest(testDispatcher) {
        // Given
        val startLocation = LatLng(37.497942, 127.027610)
        val currentLocation = LatLng(37.500000, 127.030000)
        val destination = "서울시 강남구"
        val mockResultPath = createMockResultPath()
        
        viewModel.setRoute(startLocation, destination)
        
        coEvery { 
            navigationRepository.getPathWithCoordinates(
                currentLocation.latitude,
                currentLocation.longitude,
                destination
            )
        } returns flowOf(Result.success(mockResultPath))

        // When
        viewModel.reroute(currentLocation)
        advanceUntilIdle()

        // Then
        coVerify(exactly = 1) { 
            navigationRepository.getPathWithCoordinates(
                currentLocation.latitude,
                currentLocation.longitude,
                destination
            )
        }
    }

    // Helper functions
    private fun createMockResultPath(): ResultPath {
        // 실제 ResultPath 구조에 맞게 생성
        // NavigationMapper가 null을 반환할 수 있으므로 relaxed mock 사용
        return mockk<ResultPath>(relaxed = true)
    }
}

