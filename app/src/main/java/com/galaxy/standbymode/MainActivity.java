package com.galaxy.standbymode;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GalaxyStandby";
    private static final int REQUEST_CALENDAR_PERMISSION = 100;
    private static final int REQUEST_NOTIFICATION_LISTENER = 101;

    private WebView webView;

    // 미디어 및 일반 알림 수신기
    private BroadcastReceiver mediaReceiver;
    private BroadcastReceiver notiReceiver;
    private MediaController mediaController;
    private MediaController.Callback mediaCallback;

    // Handler for periodic updates
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private Runnable calendarUpdateRunnable;
    private Runnable mediaProgressRunnable;
    private boolean isUpdateRunning = false;

    // 현재 미디어 상태 캐시
    private String currentTitle = "";
    private String currentArtist = "";
    private boolean isPlaying = false;
    private long currentPosition = 0;
    private long totalDuration = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. 화면 켜짐 유지 & 잠금화면 위 표시
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_main);

        // 2. 몰입 모드
        setupImmersiveMode();

        // 3. WebView 초기화
        setupWebView();

        // 4. 권한 확인 및 요청
        checkAndRequestPermissions();

        // 5. 알림 리스너 권한 확인
        checkNotificationListenerPermission();

        // 6. 리시버 등록
        setupMediaReceiver();
        setupNotiReceiver();
        
        // 7. 업데이터 초기화
        setupMediaProgressUpdater();
        setupCalendarUpdater();
    }

    private void setupImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
        }
    }

    private void setupWebView() {
        webView = findViewById(R.id.webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class AndroidBridge {
        @JavascriptInterface
        public String getCalendarEvents() {
            return fetchCalendarEventsJson();
        }

        @JavascriptInterface
        public String getMediaInfo() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("title", currentTitle.isEmpty() ? "재생 중인 곡 없음" : currentTitle);
                obj.put("artist", currentArtist.isEmpty() ? "" : currentArtist);
                obj.put("isPlaying", isPlaying);
                obj.put("position", currentPosition);
                obj.put("duration", totalDuration);
                return obj.toString();
            } catch (JSONException e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void mediaControl(String action) {
            if (mediaController == null) return;
            MediaController.TransportControls controls = mediaController.getTransportControls();
            if (controls == null) return;
            switch (action) {
                case "play": controls.play(); break;
                case "pause": controls.pause(); break;
                case "next": controls.skipToNext(); break;
                case "prev": controls.skipToPrevious(); break;
            }
        }

        @JavascriptInterface
        public void seekTo(long position) {
            if (mediaController == null) return;
            MediaController.TransportControls controls = mediaController.getTransportControls();
            if (controls != null) controls.seekTo(position);
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            });
        }
    }

    private void setupMediaReceiver() {
        mediaCallback = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                if (state != null) {
                    isPlaying = (state.getState() == PlaybackState.STATE_PLAYING);
                    currentPosition = state.getPosition();
                    sendMediaDataToWeb(currentTitle, currentArtist, isPlaying);
                }
            }
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                if (metadata != null) {
                    currentTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                    currentArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    totalDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    sendMediaDataToWeb(currentTitle, currentArtist, isPlaying);
                }
            }
        };

        mediaReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MediaNotificationListenerService.ACTION_MEDIA_UPDATE.equals(intent.getAction())) {
                    currentTitle = Objects.requireNonNullElse(intent.getStringExtra(MediaNotificationListenerService.EXTRA_TITLE), "");
                    currentArtist = Objects.requireNonNullElse(intent.getStringExtra(MediaNotificationListenerService.EXTRA_ARTIST), "");
                    isPlaying = intent.getBooleanExtra(MediaNotificationListenerService.EXTRA_IS_PLAYING, false);
                    android.media.session.MediaSession.Token token = intent.getParcelableExtra(MediaNotificationListenerService.EXTRA_MEDIA_SESSION_TOKEN);
                    updateMediaController(token);
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

    private void setupNotiReceiver() {
        notiReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (MediaNotificationListenerService.ACTION_NOTI_POSTED.equals(action)) {
                    sendNotiToWeb("post", intent.getStringExtra(MediaNotificationListenerService.EXTRA_NOTI_KEY),
                            intent.getStringExtra(MediaNotificationListenerService.EXTRA_NOTI_APP_NAME),
                            intent.getStringExtra(MediaNotificationListenerService.EXTRA_NOTI_TITLE),
                            intent.getStringExtra(MediaNotificationListenerService.EXTRA_NOTI_TEXT),
                            intent.getStringExtra(MediaNotificationListenerService.EXTRA_NOTI_ICON));
                } else if (MediaNotificationListenerService.ACTION_NOTI_REMOVED.equals(action)) {
                    sendNotiToWeb("remove", intent.getStringExtra(MediaNotificationListenerService.EXTRA_NOTI_KEY), null, null, null, null);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(MediaNotificationListenerService.ACTION_NOTI_POSTED);
        filter.addAction(MediaNotificationListenerService.ACTION_NOTI_REMOVED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notiReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notiReceiver, filter);
        }
    }

    private void updateMediaController(android.media.session.MediaSession.Token token) {
        if (token == null) return;
        if (mediaController != null) mediaController.unregisterCallback(mediaCallback);
        try {
            mediaController = new MediaController(this, token);
            mediaController.registerCallback(mediaCallback);
            PlaybackState state = mediaController.getPlaybackState();
            if (state != null) {
                isPlaying = (state.getState() == PlaybackState.STATE_PLAYING);
                currentPosition = state.getPosition();
            }
            MediaMetadata metadata = mediaController.getMetadata();
            if (metadata != null) totalDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Exception e) { Log.e(TAG, "MediaController Error: " + e.getMessage()); }
    }

    private void setupMediaProgressUpdater() {
        mediaProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (isUpdateRunning && isPlaying && mediaController != null) {
                    PlaybackState state = mediaController.getPlaybackState();
                    if (state != null) {
                        currentPosition = state.getPosition();
                        sendMediaDataToWeb(currentTitle, currentArtist, isPlaying);
                    }
                }
                if (isUpdateRunning) updateHandler.postDelayed(this, 1000);
            }
        };
    }

    private void setupCalendarUpdater() {
        calendarUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isUpdateRunning) {
                    sendCalendarDataToWeb();
                    updateHandler.postDelayed(this, 60000);
                }
            }
        };
    }

    private void startPeriodicUpdates() {
        if (!isUpdateRunning) {
            isUpdateRunning = true;
            updateHandler.post(calendarUpdateRunnable);
            updateHandler.post(mediaProgressRunnable);
        }
    }

    private void stopPeriodicUpdates() {
        isUpdateRunning = false;
        updateHandler.removeCallbacks(calendarUpdateRunnable);
        updateHandler.removeCallbacks(mediaProgressRunnable);
    }

    private String fetchCalendarEventsJson() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return "[]";
        JSONArray arr = new JSONArray();
        try {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            long start = cal.getTimeInMillis();
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH));
            cal.set(Calendar.HOUR_OF_DAY, 23);
            long end = cal.getTimeInMillis();

            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(builder, start);
            android.content.ContentUris.appendId(builder, end);

            String[] proj = { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY };
            try (android.database.Cursor cursor = getContentResolver().query(builder.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        JSONObject obj = new JSONObject();
                        obj.put("title", Objects.requireNonNullElse(cursor.getString(0), "(제목 없음)"));
                        long b = cursor.getLong(1);
                        Calendar c = Calendar.getInstance(); c.setTimeInMillis(b);
                        obj.put("date", String.format("%d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH)+1, c.get(Calendar.DAY_OF_MONTH)));
                        obj.put("startTime", String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
                        obj.put("allDay", cursor.getInt(3) != 0);
                        arr.put(obj);
                    }
                }
            }
        } catch (Exception e) { Log.e(TAG, "Calendar Error: " + e.getMessage()); }
        return arr.toString();
    }

    private void sendCalendarDataToWeb() {
        if (webView == null) return;
        String json = fetchCalendarEventsJson().replace("\\", "\\\\").replace("'", "\\'");
        runOnUiThread(() -> webView.evaluateJavascript("if(typeof receiveCalendarData === 'function') receiveCalendarData('" + json + "');", null));
    }

    private void sendMediaDataToWeb(String title, String artist, boolean playing) {
        if (webView == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("title", title.isEmpty() ? "재생 중인 곡 없음" : title);
            obj.put("artist", artist);
            obj.put("isPlaying", playing);
            obj.put("position", currentPosition);
            obj.put("duration", totalDuration);
            String json = obj.toString().replace("\\", "\\\\").replace("'", "\\'");
            runOnUiThread(() -> webView.evaluateJavascript("if(typeof receiveMediaData === 'function') receiveMediaData('" + json + "');", null));
        } catch (JSONException e) { Log.e(TAG, "Media JSON Error: " + e.getMessage()); }
    }

    private void sendNotiToWeb(String type, String key, String appName, String title, String text, String icon) {
        if (webView == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", type); obj.put("key", key); obj.put("appName", appName); obj.put("title", title); obj.put("text", text); obj.put("icon", icon);
            String json = obj.toString().replace("\\", "\\\\").replace("'", "\\'");
            runOnUiThread(() -> webView.evaluateJavascript("if(typeof receiveNotification === 'function') receiveNotification('" + json + "');", null));
        } catch (Exception e) { Log.e(TAG, "Noti JSON Error: " + e.getMessage()); }
    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_CALENDAR}, REQUEST_CALENDAR_PERMISSION);
        }
    }

    private void checkNotificationListenerPermission() {
        String listeners = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (listeners == null || !listeners.contains(getPackageName())) {
            Log.d(TAG, "Notification Listener Permission Required");
        }
    }

    @Override
    protected void onResume() { super.onResume(); setupImmersiveMode(); startPeriodicUpdates(); }
    @Override
    protected void onPause() { super.onPause(); stopPeriodicUpdates(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPeriodicUpdates();
        if (mediaReceiver != null) unregisterReceiver(mediaReceiver);
        if (notiReceiver != null) unregisterReceiver(notiReceiver);
        if (mediaController != null) mediaController.unregisterCallback(mediaCallback);
        if (webView != null) webView.destroy();
    }
}
