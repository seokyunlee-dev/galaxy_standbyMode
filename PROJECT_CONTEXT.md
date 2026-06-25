# 📱 Galaxy StandbyMode — 프로젝트 컨텍스트

> **이 파일은 새 세션에서 AI가 프로젝트를 빠르게 파악하기 위한 요약 문서입니다.**
> 터미널 재시작 후 이 파일을 AI에게 `@[PROJECT_CONTEXT.md]`로 첨부하세요.

---

## 🎯 프로젝트 개요

**앱 이름**: Galaxy StandbyMode  
**패키지명**: `com.galaxy.standbymode`  
**언어**: Java (Android Native) + HTML/CSS/JavaScript (WebView UI)  
**목적**: 시스템 자동 회전이 꺼져 있어도 **강제 가로 모드**로 실행되며, 폰 내부의 실시간 음악 정보 + 달력 일정을 보여주는 **스탠바이 대시보드 앱**  
**컨셉**: 애플 StandBy 모드 스타일의 모던 다크모드 UI

---

## 🏗️ 기술 스택

| 구분           | 기술                                    |
| -------------- | --------------------------------------- |
| Android Native | Java                                    |
| UI             | WebView (`assets/index.html`)           |
| 음악 정보      | `NotificationListenerService`           |
| 캘린더         | `CalendarContract API`                  |
| Java ↔ JS 통신 | `JavascriptInterface` (`AndroidBridge`) |
| 폰트           | Google Fonts — Inter                    |
| 빌드           | Gradle, `compileSdk 35`, `minSdk 26`    |

---

## 📁 파일 구조

```
galaxy_standbyMode/
├── PROJECT_CONTEXT.md              ← 이 파일 (AI용 컨텍스트)
├── README.md                       ← 원래 요구사항 명세
├── build.gradle                    ← 루트 빌드 설정
├── settings.gradle
└── app/
    ├── build.gradle                ← 앱 빌드 설정 (compileSdk 35, minSdk 26)
    └── src/main/
        ├── AndroidManifest.xml     ← 권한 선언 + 컴포넌트 등록
        ├── assets/
        │   └── index.html          ← 전체 UI (HTML+CSS+JS, 약 1447줄)
        ├── java/com/galaxy/standbymode/
        │   ├── MainActivity.java   ← 메인 액티비티 (415줄)
        │   └── MediaNotificationListenerService.java ← 음악 감지 서비스 (134줄)
        └── res/
            ├── layout/
            │   └── activity_main.xml ← 단순 풀스크린 WebView 레이아웃
            ├── drawable/
            ├── mipmap-anydpi-v26/
            └── values/
```

---

## ⚙️ 핵심 기능 상세

### 1. 가로 고정 & 풀스크린 몰입 모드

- `AndroidManifest.xml`: `android:screenOrientation="landscape"` 적용
- `MainActivity.java`: `FLAG_KEEP_SCREEN_ON`, `FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`
- API 30+ : `WindowInsetsController`로 상하단 바 완전 숨김
- API 30 미만: `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 방식 적용
- `onResume()`에서도 몰입 모드 재적용 (다른 앱에서 돌아올 때)

### 2. 음악 정보 감지 (`MediaNotificationListenerService.java`)

- `NotificationListenerService` 상속
- `CATEGORY_TRANSPORT` 또는 `android.mediaSession` 키로 미디어 알림 판별
- `Notification.EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_SUB_TEXT`에서 곡/아티스트 추출
- 정보 변경 시 `BroadcastIntent`로 `MainActivity`에 전달
  - 액션: `com.galaxy.standbymode.MEDIA_UPDATE`
  - Extra 키: `extra_title`, `extra_artist`, `extra_is_playing`

### 3. 캘린더 일정 조회 (`MainActivity.java`)

- `CalendarContract.Instances` API 사용
- 이번 달 시작 ~ 끝 범위로 최대 50개 일정 쿼리
- 60초마다 주기적으로 WebView에 업데이트 (`Handler` + `Runnable`)
- 조회 필드: `EVENT_ID`, `TITLE`, `BEGIN`, `END`, `ALL_DAY`, `DESCRIPTION`

### 4. Java ↔ JavaScript 브릿지 (`AndroidBridge`)

- WebView에 `AndroidBridge`라는 이름으로 JavascriptInterface 등록
- **JS → Java 호출 가능 메서드**:
  - `AndroidBridge.getCalendarEvents()` → JSON 문자열 반환
  - `AndroidBridge.getMediaInfo()` → JSON 문자열 반환
  - `AndroidBridge.openNotificationSettings()` → 알림 접근 설정 화면 열기
- **Java → JS 호출**:
  - `receiveCalendarData(jsonString)` 함수 호출
  - `receiveMediaData(jsonString)` 함수 호출

---

## 📋 AndroidManifest 권한

| 권한                                 | 용도                    |
| ------------------------------------ | ----------------------- |
| `INTERNET`                           | 날씨 API 등 향후 확장용 |
| `READ_CALENDAR`                      | 캘린더 일정 조회        |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 음악 정보 감지          |
| `WAKE_LOCK`                          | 화면 상시 켜짐 보조     |

---

## 🎨 UI 구성 (`assets/index.html`)

### 레이아웃

- **좌우 50:50 분할** (`display: flex`)
- **좌측 패널** (`panel-left`): 시계 + 날씨 영역
- **우측 패널** (`panel-right`): CSS scroll-snap 상하 스크롤

### 우측 스크롤 구조

1. **첫 번째 섹션**: 미니 달력 + 일정 리스트 (Java에서 실제 데이터 연동)
2. **두 번째 섹션** (아래 스크롤): 미디어 컨트롤러 UI (재생 중인 곡/아티스트 실시간 표시)

### 디자인 토큰 (CSS 변수)

```css
--bg:
  #050505 /* 완전 블랙 배경 */ --surface: rgba(255, 255, 255, 0.04)
    --text-primary: #f5f5f7 --text-secondary: #a1a1a6 --accent-blue: #0a84ff
    --accent-green: #30d158 --accent-pink: #ff375f --accent-orange: #ff9f0a
    --accent-purple: #bf5af2 --font: 'Inter',
  -apple-system, BlinkMacSystemFont,
  sans-serif --transition: 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94);
```

---

## 🔄 데이터 흐름

```
[음악 앱] ──알림──▶ MediaNotificationListenerService
                          │ (BroadcastIntent)
                          ▼
                    MainActivity (BroadcastReceiver)
                          │ (evaluateJavascript)
                          ▼
                    index.html → receiveMediaData()
                          │
                          ▼
                    미디어 컨트롤러 UI 업데이트

[스마트폰 캘린더] ──▶ CalendarContract.Instances
                          │ (60초마다 + 앱 시작 시)
                          ▼
                    fetchCalendarEventsJson()
                          │ (evaluateJavascript)
                          ▼
                    index.html → receiveCalendarData()
                          │
                          ▼
                    달력 + 일정 리스트 UI 업데이트
```

---

## ⚠️ 주의사항 / 알아야 할 것

1. **알림 접근 권한 (필수)**: `NotificationListenerService`는 일반 런타임 권한이 아님.
   사용자가 **설정 → 알림 → 특별한 앱 접근 → 알림 접근 허용**에서 직접 활성화해야 함.
   JS에서 `AndroidBridge.openNotificationSettings()`를 호출하면 해당 설정 화면으로 이동.

2. **캘린더 권한**: 런타임 퍼미션 요청 (`READ_CALENDAR`). 앱 첫 실행 시 팝업으로 요청됨.

3. **WebView 보안 설정**: `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs` 활성화됨.
   로컬 `file://` 에셋 접근을 위한 설정이므로 보안상 의도된 것.

4. **날씨 기능**: 현재 UI에 날씨 영역이 있으나 실제 API 연동은 미구현 상태일 수 있음.
   `INTERNET` 권한은 선언되어 있어 향후 확장 가능.

5. **`usesCleartextTraffic="true"`**: HTTP 통신 허용 (날씨 API 연동 시 HTTPS 권장).

---

## 🚀 빌드 & 실행 방법

1. **Android Studio**에서 프로젝트 열기: `C:\Users\user\Desktop\personal_project\galaxy_standbyMode`
2. **Gradle Sync** 실행
3. 실기기에 APK 설치 후:
   - **알림 접근 허용** (설정에서 수동으로)
   - **캘린더 권한 허용** (팝업)
4. 앱 실행 시 자동으로 가로 모드 + 풀스크린으로 전환됨

---

## 📌 향후 개선 포인트 (아직 미구현)

- [ ] 날씨 API 실제 연동 (OpenWeatherMap 등)
- [ ] 미디어 재생/일시정지 컨트롤 버튼 실제 동작 (현재는 UI만)
- [ ] 앨범 아트 표시
- [ ] 다크모드 색상 커스터마이징 설정 화면
- [ ] 위젯 또는 Always-on Display 연동
