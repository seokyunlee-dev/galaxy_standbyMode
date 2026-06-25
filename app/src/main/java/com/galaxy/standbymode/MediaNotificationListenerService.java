package com.galaxy.standbymode;

import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * NotificationListenerService를 활용하여 현재 재생 중인 음악의
 * 곡 제목과 아티스트명을 추출해 MainActivity에 브로드캐스트합니다.
 * 또한 일반 알림을 캡처하여 알림 센터 기능을 지원합니다.
 */
public class MediaNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "MediaListener";

    // BroadcastReceiver 액션 및 Extra 키 (미디어)
    public static final String ACTION_MEDIA_UPDATE = "com.galaxy.standbymode.MEDIA_UPDATE";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";
    public static final String EXTRA_MEDIA_SESSION_TOKEN = "extra_media_session_token";

    // BroadcastReceiver 액션 및 Extra 키 (일반 알림)
    public static final String ACTION_NOTI_POSTED = "com.galaxy.standbymode.NOTI_POSTED";
    public static final String ACTION_NOTI_REMOVED = "com.galaxy.standbymode.NOTI_REMOVED";
    public static final String EXTRA_NOTI_KEY = "extra_noti_key";
    public static final String EXTRA_NOTI_APP_NAME = "extra_noti_app_name";
    public static final String EXTRA_NOTI_TITLE = "extra_noti_title";
    public static final String EXTRA_NOTI_TEXT = "extra_noti_text";
    public static final String EXTRA_NOTI_ICON = "extra_noti_icon";

    // 미디어 스타일 알림에서 쓰이는 메타데이터 키 (표준)
    private static final String EXTRA_MEDIA_SESSION =
            "android.mediaSession";

    // 필터링할 패키지 및 키워드 설정
    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.samsung.android.app.routines",
        "com.samsung.android.oneconnect"
    ));
    
    private static final String[] IGNORED_KEYWORDS = {
        "충전", "USB", "절전", "업데이트", "Screenshot", "스크린샷", "모드 및 루틴"
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        processMediaNotification(sbn);
        processGeneralNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 미디어 알림 처리
        if (isMediaNotification(sbn)) {
            broadcastMediaInfo("", "", false, null);
        }
        
        // 일반 알림 제거 브로드캐스트
        Intent intent = new Intent(ACTION_NOTI_REMOVED);
        intent.putExtra(EXTRA_NOTI_KEY, sbn.getKey());
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.d(TAG, "알림 리스너 연결됨");
        // 이미 활성화된 알림들 스캔
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    processMediaNotification(sbn);
                    processGeneralNotification(sbn);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "활성 알림 스캔 오류: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────
    //  알림 처리 (미디어)
    // ─────────────────────────────────────────────────
    private void processMediaNotification(StatusBarNotification sbn) {
        if (!isMediaNotification(sbn)) return;

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence artist = extras.getCharSequence(Notification.EXTRA_TEXT);

        if (artist == null || artist.toString().trim().isEmpty()) {
            artist = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        }

        String titleStr = (title != null) ? title.toString().trim() : "";
        String artistStr = (artist != null) ? artist.toString().trim() : "";
        boolean playing = (notification.actions != null && notification.actions.length > 0);
        android.media.session.MediaSession.Token token = extras.getParcelable(EXTRA_MEDIA_SESSION);

        if (!titleStr.isEmpty()) {
            broadcastMediaInfo(titleStr, artistStr, playing, token);
        }
    }

    // ─────────────────────────────────────────────────
    //  알림 처리 (일반)
    // ─────────────────────────────────────────────────
    private void processGeneralNotification(StatusBarNotification sbn) {
        if (isMediaNotification(sbn)) return;
        
        String pkg = sbn.getPackageName();
        if (pkg == null) return;

        // 1. 패키지 기반 필터링
        if (IGNORED_PACKAGES.contains(pkg)) return;

        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if (extras == null) return;

        String title = "";
        Object titleObj = extras.get(Notification.EXTRA_TITLE);
        if (titleObj != null) title = titleObj.toString();

        String text = "";
        Object textObj = extras.get(Notification.EXTRA_TEXT);
        if (textObj != null) text = textObj.toString();

        // 2. 키워드 기반 필터링
        String fullContent = (title + " " + text).toLowerCase();
        for (String keyword : IGNORED_KEYWORDS) {
            if (fullContent.contains(keyword.toLowerCase())) return;
        }

        // 3. 진행 중인 알림 제외 (충전, 루틴, 음악 컨트롤 등은 대시보드에 부적절)
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return;
        }

        // 앱 이름 가져오기
        String appName = "";
        try {
            appName = getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(pkg, 0)
            ).toString();
        } catch (Exception e) {
            appName = pkg;
        }

        if (title.isEmpty() && text.isEmpty()) return;

        // 아이콘 추출 및 Base64 변환
        String iconBase64 = "";
        try {
            Drawable icon = getPackageManager().getApplicationIcon(sbn.getPackageName());
            iconBase64 = drawableToBase64(icon);
        } catch (Exception e) {
            Log.e(TAG, "아이콘 추출 오류: " + e.getMessage());
        }

        Intent intent = new Intent(ACTION_NOTI_POSTED);
        intent.putExtra(EXTRA_NOTI_KEY, sbn.getKey());
        intent.putExtra(EXTRA_NOTI_APP_NAME, appName);
        intent.putExtra(EXTRA_NOTI_TITLE, title);
        intent.putExtra(EXTRA_NOTI_TEXT, text);
        intent.putExtra(EXTRA_NOTI_ICON, iconBase64);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private String drawableToBase64(Drawable drawable) {
        if (drawable == null) return "";
        Bitmap bitmap;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
                bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            } else {
                bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            }
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }
        
        // 아이콘 크기 최적화 (너무 크면 전송 속도 및 메모리 문제)
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 64, 64, true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.PNG, 100, baos);
        byte[] b = baos.toByteArray();
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    /**
     * MediaStyle 알림 여부 판별
     */
    private boolean isMediaNotification(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;

        if (Notification.CATEGORY_TRANSPORT.equals(n.category)) {
            return true;
        }

        Bundle extras = n.extras;
        if (extras != null && extras.containsKey(EXTRA_MEDIA_SESSION)) {
            return true;
        }

        return false;
    }

    // ─────────────────────────────────────────────────
    //  BroadcastIntent 전송
    // ─────────────────────────────────────────────────
    private void broadcastMediaInfo(String title, String artist, boolean isPlaying, android.media.session.MediaSession.Token token) {
        Intent intent = new Intent(ACTION_MEDIA_UPDATE);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_ARTIST, artist);
        intent.putExtra(EXTRA_IS_PLAYING, isPlaying);
        if (token != null) {
            intent.putExtra(EXTRA_MEDIA_SESSION_TOKEN, token);
        }
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }
}
