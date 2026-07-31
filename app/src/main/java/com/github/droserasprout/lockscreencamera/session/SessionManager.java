package com.github.droserasprout.lockscreencamera.session;

import android.net.Uri;
import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ロック画面から起動した「セキュアカメラセッション」中に撮影された写真の URI を追跡する。
 * カメラアプリのプロセス内でのみ動作する（アプリプロセスごとに独立したインスタンス）。
 */
public final class SessionManager {

    private static final String TAG = "LockscreenCamera.Session";

    public static volatile boolean isActive = false;
    public static final List<Uri> SESSION_URIS = new CopyOnWriteArrayList<>();

    private SessionManager() {}

    public static void start() {
        isActive = true;
        SESSION_URIS.clear();
        Log.i(TAG, "Secure Camera Session Started");
    }

    public static void end() {
        isActive = false;
        SESSION_URIS.clear();
        Log.i(TAG, "Secure Camera Session Cleared");
    }

    public static void add(Uri uri) {
        if (!isActive || uri == null) return;
        if (SESSION_URIS.contains(uri)) {
            Log.d(TAG, "Duplicate URI skipped: " + uri);
            return;
        }
        SESSION_URIS.add(uri);
        Log.d(TAG, "Added to Session: " + uri + " | Total: " + SESSION_URIS.size());
    }
}
