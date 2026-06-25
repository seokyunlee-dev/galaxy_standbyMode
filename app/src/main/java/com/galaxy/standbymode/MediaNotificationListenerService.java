package com.galaxy.standbymode;

import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.media.app.NotificationCompat;

import android.app.Notification;
import android.os.Bundle;

/**
 * NotificationListenerService를 활용하여 현재 재생 중인 음악의
 * 곡 제목과 아티스트명을 추출해 MainActivity에 브로드캐스트합니다.
 *
 * ※ 이 서비스가 동작하려면 사용자가
 *    설정 → 알림 → 알림 접근 허용에서 앱을 활성화해야 합니다.
 */
public class MediaNotificationListenerService extends NotificationListenerService {

    private static final String TAG = "MediaListener";

    // BroadcastReceiver 액션 및 Extra 키
    public static final String ACTION_MEDIA_UPDATE = "com.galaxy.standbymode.MEDIA_UPDATE";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_IS_PLAYING = "extra_is_playing";

    // 미디어 스타일 알림에서 쓰이는 메타데이터 키 (표준)
    private static final String EXTRA_MEDIA_SESSION =
            "android.mediaSession";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        processNotification(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 미디어 알림이 제거되면 재생 중지 상태로 브로드캐스트
        if (isMediaNotification(sbn)) {
            broadcastMediaInfo("", "", false);
        }
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
                    if (isMediaNotification(sbn)) {
                        processNotification(sbn);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "활성 알림 스캔 오류: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────
    //  알림 처리
    // ─────────────────────────────────────────────────
    private void processNotification(StatusBarNotification sbn) {
        if (!isMediaNotification(sbn)) return;

        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) return;

        // MediaStyle 알림에서 제목(TITLE)과 아티스트(TEXT/SUB_TEXT) 추출
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence artist = extras.getCharSequence(Notification.EXTRA_TEXT);

        // 일부 앱은 EXTRA_SUB_TEXT에 아티스트를 넣기도 함
        if (artist == null || artist.toString().trim().isEmpty()) {
            artist = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        }

        String titleStr = (title != null) ? title.toString().trim() : "";
        String artistStr = (artist != null) ? artist.toString().trim() : "";

        // 재생 상태 확인 (Notification action의 존재로 간접 판별)
        boolean playing = (notification.actions != null && notification.actions.length > 0);

        Log.d(TAG, "미디어 정보: " + titleStr + " - " + artistStr + " (재생중: " + playing + ")");

        if (!titleStr.isEmpty()) {
            broadcastMediaInfo(titleStr, artistStr, playing);
        }
    }

    /**
     * MediaStyle 알림 여부 판별
     * - 알림 카테고리가 CATEGORY_TRANSPORT이거나
     * - extras에 미디어 세션 토큰이 존재하면 미디어 알림으로 판단
     */
    private boolean isMediaNotification(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null) return false;

        // CATEGORY_TRANSPORT: 미디어 재생 컨트롤 알림
        if (Notification.CATEGORY_TRANSPORT.equals(n.category)) {
            return true;
        }

        // extras에 미디어 세션이 있는 경우
        Bundle extras = n.extras;
        if (extras != null && extras.containsKey(EXTRA_MEDIA_SESSION)) {
            return true;
        }

        return false;
    }

    // ─────────────────────────────────────────────────
    //  BroadcastIntent 전송
    // ─────────────────────────────────────────────────
    private void broadcastMediaInfo(String title, String artist, boolean isPlaying) {
        Intent intent = new Intent(ACTION_MEDIA_UPDATE);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_ARTIST, artist);
        intent.putExtra(EXTRA_IS_PLAYING, isPlaying);
        intent.setPackage(getPackageName()); // 보안: 자신의 패키지만 수신
        sendBroadcast(intent);
    }
}
