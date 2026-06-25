package com.galaxy.standbymode;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ContentUris;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GalaxyStandby";
    private static final int REQUEST_CALENDAR_PERMISSION = 100;
    private static final int REQUEST_NOTIFICATION_LISTENER = 101;

    private WebView webView;

    // 미디어 정보 브로드캐스트 수신기
    private BroadcastReceiver mediaReceiver;

    // Handler for periodic updates
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable calendarUpdateRunnable;

    // 현재 미디어 상태 캐시
    private String currentTitle = "";
    private String currentArtist = "";
    private boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 화면 상시 켜짐 + 잠금 화면 위에 표시
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_main);

        // 2. 몰입 모드 (풀스크린 - 상하단 바 완전 숨김)
        setupImmersiveMode();

        // 3. WebView 초기화
        setupWebView();

        // 4. 권한 확인 및 요청
        checkAndRequestPermissions();

        // 5. 알림 리스너 권한 확인 및 요청
        checkNotificationListenerPermission();

        // 6. 미디어 정보 BroadcastReceiver 등록
        setupMediaReceiver();

        // 7. 캘린더 주기적 업데이트 설정 (1분마다)
        setupCalendarUpdater();
    }

    // ─────────────────────────────────────────────────
    //  몰입 모드 설정
    // ─────────────────────────────────────────────────
    private void setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
        }
    }

    // ─────────────────────────────────────────────────
    //  WebView 설정
    // ─────────────────────────────────────────────────
    private void setupWebView() {
        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // JavascriptInterface 등록
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 페이지 로드 완료 후 데이터 전송
                updateHandler.postDelayed(() -> {
                    sendCalendarDataToWeb();
                    sendMediaDataToWeb(currentTitle, currentArtist, isPlaying);
                }, 500);
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ─────────────────────────────────────────────────
    //  JavascriptInterface (Android → Web)
    // ─────────────────────────────────────────────────
    public class AndroidBridge {

        /**
         * JS에서 호출: 캘린더 데이터 요청
         */
        @JavascriptInterface
        public String getCalendarEvents() {
            return fetchCalendarEventsJson();
        }

        /**
         * JS에서 호출: 현재 미디어 정보 요청
         */
        @JavascriptInterface
        public String getMediaInfo() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("title", currentTitle.isEmpty() ? "재생 중인 곡 없음" : currentTitle);
                obj.put("artist", currentArtist.isEmpty() ? "" : currentArtist);
                obj.put("isPlaying", isPlaying);
                return obj.toString();
            } catch (JSONException e) {
                return "{\"title\":\"재생 중인 곡 없음\",\"artist\":\"\",\"isPlaying\":false}";
            }
        }

        /**
         * JS에서 호출: 알림 리스너 권한 설정 열기
         */
        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            });
        }
    }

    // ─────────────────────────────────────────────────
    //  미디어 정보 BroadcastReceiver
    // ─────────────────────────────────────────────────
    private void setupMediaReceiver() {
        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (MediaNotificationListenerService.ACTION_MEDIA_UPDATE.equals(action)) {
                    currentTitle = intent.getStringExtra(MediaNotificationListenerService.EXTRA_TITLE);
                    currentArtist = intent.getStringExtra(MediaNotificationListenerService.EXTRA_ARTIST);
                    isPlaying = intent.getBooleanExtra(MediaNotificationListenerService.EXTRA_IS_PLAYING, false);

                    if (currentTitle == null) currentTitle = "";
                    if (currentArtist == null) currentArtist = "";

                    sendMediaDataToWeb(currentTitle, currentArtist, isPlaying);
                }
            }
        };

        IntentFilter filter = new IntentFilter(MediaNotificationListenerService.ACTION_MEDIA_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mediaReceiver, filter);
        }
    }

    // ─────────────────────────────────────────────────
    //  캘린더 주기적 업데이트 (60초마다)
    // ─────────────────────────────────────────────────
    private void setupCalendarUpdater() {
        calendarUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                sendCalendarDataToWeb();
                updateHandler.postDelayed(this, 60 * 1000);
            }
        };
        updateHandler.postDelayed(calendarUpdateRunnable, 2000);
    }

    // ─────────────────────────────────────────────────
    //  캘린더 데이터 조회 (CalendarContract API)
    // ─────────────────────────────────────────────────
    private String fetchCalendarEventsJson() {
        JSONArray events = new JSONArray();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "캘린더 권한 없음");
            return events.toString();
        }

        try {
            Calendar cal = Calendar.getInstance();

            // 이번 달 시작 ~ 끝
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long startMillis = cal.getTimeInMillis();

            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
            cal.set(Calendar.HOUR_OF_DAY, 23);
            cal.set(Calendar.MINUTE, 59);
            cal.set(Calendar.SECOND, 59);
            long endMillis = cal.getTimeInMillis();

            Uri.Builder eventsUriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(eventsUriBuilder, startMillis);
            ContentUris.appendId(eventsUriBuilder, endMillis);

            String[] projection = {
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.DESCRIPTION
            };

            String selection = CalendarContract.Instances.BEGIN + " >= ? AND " +
                               CalendarContract.Instances.BEGIN + " <= ?";
            String[] selectionArgs = { String.valueOf(startMillis), String.valueOf(endMillis) };

            Cursor cursor = getContentResolver().query(
                    eventsUriBuilder.build(),
                    projection,
                    null,
                    null,
                    CalendarContract.Instances.BEGIN + " ASC"
            );

            if (cursor != null) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

                while (cursor.moveToNext()) {
                    JSONObject event = new JSONObject();
                    String title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE));
                    long begin = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN));
                    long end = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.END));
                    int allDay = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY));

                    Date beginDate = new Date(begin);
                    event.put("id", cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)));
                    event.put("title", title != null ? title : "(제목 없음)");
                    event.put("date", dateFormat.format(beginDate));
                    event.put("startTime", allDay == 1 ? "종일" : timeFormat.format(beginDate));
                    event.put("endTime", allDay == 1 ? "" : timeFormat.format(new Date(end)));
                    event.put("allDay", allDay == 1);

                    events.put(event);

                    if (events.length() >= 50) break; // 최대 50개
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "캘린더 조회 오류: " + e.getMessage());
        }

        return events.toString();
    }

    // ─────────────────────────────────────────────────
    //  Web으로 데이터 전송 (runOnUiThread 필수)
    // ─────────────────────────────────────────────────
    private void sendCalendarDataToWeb() {
        if (webView == null) return;
        String json = fetchCalendarEventsJson();
        String escapedJson = json.replace("\\", "\\\\").replace("'", "\\'");
        runOnUiThread(() ->
            webView.evaluateJavascript(
                "if(typeof receiveCalendarData === 'function') receiveCalendarData('" + escapedJson + "');",
                null
            )
        );
    }

    private void sendMediaDataToWeb(String title, String artist, boolean playing) {
        if (webView == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("title", title.isEmpty() ? "재생 중인 곡 없음" : title);
            obj.put("artist", artist);
            obj.put("isPlaying", playing);
            String json = obj.toString().replace("\\", "\\\\").replace("'", "\\'");
            runOnUiThread(() ->
                webView.evaluateJavascript(
                    "if(typeof receiveMediaData === 'function') receiveMediaData('" + json + "');",
                    null
                )
            );
        } catch (JSONException e) {
            Log.e(TAG, "미디어 JSON 오류: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────
    //  권한 요청
    // ─────────────────────────────────────────────────
    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CALENDAR},
                    REQUEST_CALENDAR_PERMISSION);
        }
    }

    private void checkNotificationListenerPermission() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean enabled = flat != null && flat.contains(getPackageName());
        if (!enabled) {
            Log.w(TAG, "알림 리스너 권한이 없습니다. 설정 화면으로 안내하세요.");
            // 앱 첫 실행 시 Web에서 버튼으로 유도하거나, 아래 코드로 직접 열 수 있음
            // Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            // startActivity(intent);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CALENDAR_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                sendCalendarDataToWeb();
            }
        }
    }

    // ─────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        setupImmersiveMode(); // 화면 복귀 시 다시 몰입 모드
        sendCalendarDataToWeb();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaReceiver != null) {
            unregisterReceiver(mediaReceiver);
        }
        if (calendarUpdateRunnable != null) {
            updateHandler.removeCallbacks(calendarUpdateRunnable);
        }
        if (webView != null) {
            webView.destroy();
        }
    }
}
