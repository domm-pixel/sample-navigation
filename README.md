# Sample Navigation - 네비게이션 앱

네이버 지도 API와 네이버 길찾기 API를 활용한 안드로이드 네비게이션 앱입니다.

## 주요 기능

### 1. 경로 검색 및 표시
- 출발지: 현재 GPS 위치 또는 주소 입력
- 도착지: 주소 검색으로 설정
- 실시간 경로 검색 및 지도 표시
- 혼잡도 정보에 따른 경로 색상 구분 (MainActivity)

### 2. 네비게이션 안내
- **자동 경로 추적**: GPS 위치를 경로상 위치로 매칭하여 진행 상황 추적
- **실시간 지도 회전**: 진행 방향에 따라 지도 자동 회전 (진행 방향이 항상 위쪽)
- **지나온 경로 처리**: 지나온 경로는 자동으로 숨김 처리
- **자동 도착 감지**: 도착지 25m 이내 도착 시 자동 안내 종료
- **경로 재검색**: 경로 이탈 시 (60m 이상) 자동 경로 재검색

### 3. 사용자 인터페이스
- **MainActivity**: 경로 검색 및 전체 경로 확인 (혼잡도 표시)
- **NavigationActivity**: 실시간 네비게이션 안내
  - 좌상단: 주요 안내 메시지 (좌회전, 우회전 등)
  - 우상단: 음성 안내 토글 스위치
  - 하단: 남은 거리, 남은 시간, 안내 중지 버튼

### 4. 제스처 모드
- **교통량 확인 모드**: 지도 제스처 입력 시 혼잡도 색상으로 경로 재표시
- **자동 추적 중지**: 제스처 모드에서는 카메라 자동 추적 비활성화
- **현위치로 복귀**: 저장된 줌 레벨과 방향으로 원래 네비게이션 모드 복귀

### 5. 음성 안내
- TTS(Text-to-Speech)를 통한 음성 안내
- 중복 안내 메시지 방지
- 사용자가 음성 안내 ON/OFF 제어 가능

## 기술 스택

### 사용 라이브러리
- **Naver Maps SDK**: 네이버 지도 표시 및 지도 제어
- **Naver Direction API**: 경로 검색 및 길찾기
- **Naver Map Geocoding API**: 주소 ↔ 좌표 변환
- **Dagger Hilt**: 의존성 주입
- **ViewModel & LiveData**: MVVM 아키텍처
- **Coroutines & Flow**: 비동기 처리
- **Timber**: 로깅
- **Android Text-to-Speech**: 음성 안내

### 아키텍처
```
MVVM (Model-View-ViewModel) 패턴
├── View (Activity/Fragment)
│   ├── MainActivity - 경로 검색 화면
│   └── NavigationActivity - 네비게이션 화면
├── ViewModel
│   ├── MainViewModel - 경로 검색 로직
│   └── NavigationViewModel - 네비게이션 로직
├── Repository
│   └── NavigationRepository - API 통신
└── NavigationManager - 네비게이션 상태 관리
```

## 주요 구현 사항

### 1. 경로 매칭 및 추적
- GPS 위치를 경로의 선분(Line Segment)에 투영하여 가장 가까운 경로상 위치 찾기
- 오차 범위 내(15m)에서는 경로상 위치로 마커 이동
- 경로 이탈 시 경로 재검색 또는 원본 GPS 위치 사용

### 2. 지도 회전 및 카메라 제어
- 경로 기반 방향 계산 후 부드러운 지도 회전 (최대 30도/초, 60% 보간)
- 현재 위치를 지도 중앙에 유지
- 진행 방향이 항상 위쪽(북쪽)을 향하도록 설정

### 3. 경로 재검색 (Rerouting)
- 경로 이탈 감지: 현재 위치가 경로로부터 60m 이상 떨어질 때
- 최소 5초 간격으로 재검색 제한 (너무 자주 재검색 방지)
- 재검색 완료 후 새로운 경로로 자동 전환

### 4. 제스처 모드
- 지도 클릭/롱클릭/드래그 감지
- 제스처 감지 시 교통량 정보 표시 모드로 전환
- "현위치로" 버튼으로 원래 네비게이션 모드 복귀
- 복귀 시 저장된 줌 레벨(17.0)과 진행 방향 유지

### 5. 음성 안내
- 중복 안내 방지를 위한 메시지 추적
- 안내 메시지: "300m 후 좌회전", "100m 후 우회전" 등
- 사용자가 음성 안내 ON/OFF 가능

## 파일 구조

```
app/src/main/java/com/dom/samplenavigation/
├── api/
│   └── navigation/
│       ├── NaverDirectionApi.kt      # 경로 검색 API
│       ├── NaverMapApi.kt            # 지오코딩 API
│       └── repo/
│           └── NavigationRepository.kt
├── navigation/
│   ├── manager/
│   │   └── NavigationManager.kt      # 네비게이션 상태 관리
│   ├── mapper/
│   │   └── NavigationMapper.kt       # API 응답 → 도메인 모델 변환
│   ├── model/
│   │   ├── NavigationRoute.kt
│   │   ├── NavigationState.kt
│   │   └── Instruction.kt
│   └── voice/
│       └── VoiceGuideManager.kt      # TTS 관리
├── view/
│   ├── MainActivity.kt               # 경로 검색 화면
│   ├── NavigationActivity.kt         # 네비게이션 화면
│   └── viewmodel/
│       ├── MainViewModel.kt
│       └── NavigationViewModel.kt
└── di/
    └── NaverModule.kt                # Dagger Hilt 의존성 주입
```

## 사용 방법

### 1. 경로 검색 (MainActivity)
1. GPS 위치 권한 확인
2. 도착지 주소 입력
3. 검색 버튼 클릭
4. 경로가 지도에 표시됨 (혼잡도 색상 구분)
5. "안내 시작" 버튼 클릭 → NavigationActivity 시작

### 2. 네비게이션 안내 (NavigationActivity)
1. 자동으로 네비게이션 시작
2. GPS 위치를 따라 실시간 안내
3. 진행 방향에 따라 지도 자동 회전
4. 음성 안내로 다음 안내 사항 전달
5. 도착지 도착 시 자동 안내 종료

### 3. 제스처 모드 활용
1. 네비게이션 중 지도 터치/드래그
2. 교통량 정보 색상으로 경로 재표시
3. "현위치로" 버튼 클릭 시 원래 모드 복귀

## 설정

### API 키 설정
1. `local.properties` 파일에 네이버 클라우드 플랫폼 API 키 추가:
```properties
NAVER_CLIENT_ID=your_client_id
NAVER_CLIENT_SECRET=your_client_secret
NAVER_MAPS_API_KEY=your_maps_api_key
```

### 필수 권한
- `ACCESS_FINE_LOCATION`: GPS 위치 정보 접근
- `ACCESS_COARSE_LOCATION`: 네트워크 위치 정보 접근

## 주요 상수

```kotlin
// NavigationActivity.kt
OFF_ROUTE_THRESHOLD = 15f        // 경로 오차 범위 (미터)
ARRIVAL_THRESHOLD = 25f          // 도착 판정 거리 (미터)
REROUTE_THRESHOLD = 60f          // 경로 재검색 임계값 (미터)
GESTURE_TIMEOUT = 10000L         // 제스처 모드 자동 복귀 시간 (10초)
```

## 주의사항

1. **API 키**: 네이버 클라우드 플랫폼의 네비게이션 API와 지도 API 키가 필요합니다.
2. **위치 권한**: 네비게이션 사용을 위해 위치 권한이 필수입니다.
3. **GPS 정확도**: GPS 신호가 약한 실내에서는 정확도가 떨어질 수 있습니다.

## 향후 개선 사항

- [ ] 경로 옵션 선택 (최단거리, 최소시간, 우회전 회피 등)
- [ ] 네비게이션 이력 저장 및 재사용
- [ ] 오프라인 지도 지원
- [ ] 더 정교한 음성 안내 (거리별 안내 빈도 조절)
- [ ] 차량/도보/대중교통 모드 구분

## 라이선스

이 프로젝트는 개인 학습 목적으로 제작되었습니다.

