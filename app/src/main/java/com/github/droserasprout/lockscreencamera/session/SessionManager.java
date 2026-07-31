package com.github.droserasprout.lockscreencamera.session;

import android.net.Uri;
import android.util.Log;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SessionManager {
    private static final String TAG = "LockscreenCamera.Session";
    public static volatile boolean isActive = false;
    public static final List<Uri> SESSION_URIS = new CopyOnWriteArrayList<>();
    private static int refCount = 0;
    private SessionManager() {}

    public static void start() {
        if (!isActive) { SESSION_URIS.clear(); }
        isActive = true;
        refCount++;
        Log.i(TAG, "Secure Camera Session Started (refCount=" + refCount + ")");
    }

    public static void release() {
        if (refCount > 0) refCount--;
        if (refCount <= 0) {
            refCount = 0;
            isActive = false;
            SESSION_URIS.clear();
            Log.i(TAG, "Secure Camera Session Cleared");
        } else {
            Log.d(TAG, "Session release (refCount=" + refCount + "), stays active");
        }
    }

    public static void end() {
        refCount = 0;
        isActive = false;
        SESSION_URIS.clear();
        Log.i(TAG, "Secure Camera Session Cleared (forced)");
    }

    public static void add(Uri uri) {
        if (!isActive || uri == null) return;
        if (SESSION_URIS.contains(uri)) return;
        SESSION_URIS.add(uri);
    }
}
