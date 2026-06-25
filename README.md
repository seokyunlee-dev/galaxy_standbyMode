# galaxy_standbyMode

[역할] Expert Android (Java) & Frontend Developer

[목표]
시스템 '자동 회전'이 꺼져 있어도 실행 즉시 가로 모드로 강제 구동되며, 폰 내부의 실시간 음악 정보와 달력 일정을 동적으로 가져와 보여주는 자바(Java) 기반 안드로이드 WebView '스탠바이 대시보드 앱' 전체 프로젝트 코드를 생성해줘.

[핵심 요구사항]

1. 안드로이드 네이티브 기능 (Java)
   - 가로 고정 및 풀스크린: 'android:screenOrientation="landscape"'를 적용하고, 상하단 바를 모두 숨기는 몰입 모드(Immersive Mode) 및 화면 상시 켜짐(FLAG_KEEP_SCREEN_ON)을 설정해줘.
   - 미디어 정보 가져오기: MediaSessionManager 또는 NotificationListenerService를 활용해 현재 재생 중인 음악의 곡 제목과 아티스트명을 실시간으로 가져오는 로직을 구현해줘. (필요한 권한 및 Manifest 설정 포함)
   - 캘린더 일정 가져오기: CalendarContract API를 사용하여 스마트폰에 등록된 오늘/이번 달 일정을 쿼리해오는 로직을 구현해줘. (READ_CALENDAR 권한 포함)
   - 자바-웹 인터페이스: 웹뷰의 JavascriptInterface를 구축하여, 자바에서 가져온 미디어 정보와 캘린더 데이터를 JSON 형태로 assets/index.html의 자바스크립트 함수로 전달해줘.

2. 웹 화면 구성 (assets/index.html)
   - 구조: 화면을 정확히 좌우 50:50으로 나눈 분할 레이아웃.
   - 왼쪽: 실시간 디지털시계(HH:MM) 및 현재 날씨 정보 배치.
   - 오른쪽: CSS scroll-snap을 활용한 상하 스크롤 영역.
     - 첫 번째 페이지: 자바에서 받아온 실제 일정이 표시되는 미니 달력 및 일정 리스트.
     - 아래로 스크롤 시: 자바에서 받아온 실제 재생 중인 곡 제목과 아티스트명이 실시간 연동되는 미디어 컨트롤러 UI.

3. 스타일 및 디자인 지침 (중요)
   - 전체적인 스타일은 애플 스탠바이 모드처럼 아주 모던하고, 깔끔하며, '예쁘게' 디자인해줘.
   - 완전 블랙(#050505) 계열의 다크모드 테마를 기본으로 하고, 세련된 타이포그래피, 여백(Padding/Gap), 그리고 부드러운 전환 애니메이션을 아낌없이 적용해줘.

이 프로젝트를 바로 빌드할 수 있도록 AndroidManifest.xml, MainActivity.java, activity_main.xml, assets/index.html 코드를 폴더 구조와 함께 완성도 높게 작성해줘.
