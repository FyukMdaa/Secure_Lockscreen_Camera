package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import android.view.WindowManager;

import java.lang.reflect.Method;
import java.util.ArrayList;

import com.github.droserasprout.lockscreencamera.session.SessionManager;
import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;
import com.github.droserasprout.lockscreencamera.util.ModulePrefs;

import io.github.libxposed.api.XposedModule;

/**
 * カメラアプリが「ギャラリー確認画面」を開こうとした際、
 * 設定に応じて SecureViewerActivity にリダイレクトする。
 * <p>
 * GCam 等で SecureViewer が動作しない場合は設定画面で該当パッケージを
 * 「ビューアー除外」に追加することで、カメラアプリ標準のレビューアが使われる。
 */
public final class GalleryRedirectHook {

    private static final String TAG = "LockscreenCamera";
    private static final String VIEWER_PACKAGE = "com.github.droserasprout.lockscreencamera";
    private static final String VIEWER_CLASS = "com.github.droserasprout.lockscreencamera.SecureViewerActivity";

    private static final int FLAG_SHOW_WHEN_LOCKED = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
    private static final int FLAG_TURN_SCREEN_ON = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
    private static final int FLAG_ALLOW_LOCK_WHILE_SCREEN_ON = WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;

    private static SharedPreferences prefs;

    private GalleryRedirectHook() {}

    public static void install(XposedModule module, SharedPreferences prefs) {
        GalleryRedirectHook.prefs = prefs;
        try {
            Method startAct = Activity.class.getDeclaredMethod("startActivity", Intent.class);
            module.hook(startAct).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });

            Method startRes = Activity.class.getDeclaredMethod("startActivityForResult", Intent.class, int.class);
            module.hook(startRes).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });

            Method startCtx = ContextWrapper.class.getDeclaredMethod("startActivity", Intent.class);
            module.hook(startCtx).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook gallery redirect", t);
        }
    }

    private static void handleGalleryRedirect(Context ctx, Intent intent) {
        if (ctx == null || intent == null || intent.getAction() == null) return;
        if (!SessionManager.isActive) return;

        String pkg;
        try { pkg = ctx.getPackageName(); } catch (Exception e) { return; }
        if (!CameraPackageUtil.isCameraPackage(pkg, GalleryRedirectHook.prefs)) return;

        // 設定で SecureViewer が無効化されているパッケージならスキップ
        if (!ModulePrefs.shouldUseSecureViewer(GalleryRedirectHook.prefs, pkg)) {
            Log.d(TAG, "SecureViewer disabled for: " + pkg);
            return;
        }

        String action = intent.getAction();
        boolean isGallery = Intent.ACTION_VIEW.equals(action)
            || Intent.ACTION_PICK.equals(action)
            || action.contains("REVIEW");
        if (!isGallery) return;

        Log.i(TAG, "Redirecting to SecureViewer: Force hijacking intent");

        ArrayList<Uri> uriList = new ArrayList<>(SessionManager.SESSION_URIS);
        if (uriList.isEmpty() && intent.getData() != null) {
            uriList.add(intent.getData());
        }

        intent.setComponent(new ComponentName(VIEWER_PACKAGE, VIEWER_CLASS));
        intent.setPackage(null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            intent.setSelector(null);
        }

        if (!uriList.isEmpty()) {
            ClipData clipData = ClipData.newRawUri("Photos", uriList.get(0));
            for (int i = 1; i < uriList.size(); i++) {
                clipData.addItem(new ClipData.Item(uriList.get(i)));
            }
            intent.setClipData(clipData);
        }
        intent.putParcelableArrayListExtra("session_photos_list", uriList);

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | FLAG_SHOW_WHEN_LOCKED
                | FLAG_TURN_SCREEN_ON
                | FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);

        Log.d(TAG, "Intent modification complete. Proceeding with hijacked intent.");
    }
}
