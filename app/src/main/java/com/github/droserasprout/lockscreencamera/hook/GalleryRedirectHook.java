package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;

import com.github.droserasprout.lockscreencamera.session.SessionManager;
import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;

import io.github.libxposed.api.XposedModule;

/**
 * カメラアプリがシステムの「ギャラリー確認画面」を開こうとした際、
 * 独自の SecureViewerActivity にリダイレクトすることでロック中でも写真プレビューを可能にする。
 */
public final class GalleryRedirectHook {

    private static final String TAG = "LockscreenCamera";
    private static final String VIEWER_PACKAGE = "com.github.droserasprout.lockscreencamera";
    private static final String VIEWER_CLASS = "com.github.droserasprout.lockscreencamera.SecureViewerActivity";

    // ロック画面上に表示するための隠しフラグ (SHOW_WHEN_LOCKED 系)
    private static final int FLAG_SHOW_WHEN_LOCKED_HIDDEN = 0x00080000 | 0x00400000 | 0x00200000;

    private GalleryRedirectHook() {}

    public static void install(XposedModule module) {
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

        try {
            if (!CameraPackageUtil.isCameraPackage(ctx.getPackageName())) return;
        } catch (Exception e) {
            return;
        }

        String action = intent.getAction();
        boolean isGallery = Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_PICK.equals(action)
                || action.contains("REVIEW")
                || action.contains("STILL_IMAGE_CAMERA");
        if (!isGallery) return;

        Log.i(TAG, "Redirecting to SecureViewer: Force hijacking intent");

        ArrayList<Uri> uriList = new ArrayList<>(SessionManager.SESSION_URIS);
        if (uriList.isEmpty() && intent.getData() != null) {
            uriList.add(intent.getData());
        }

        // 既存の intent を書き換えて再利用し、システムを騙す
        intent.setComponent(new ComponentName(VIEWER_PACKAGE, VIEWER_CLASS));
        intent.setPackage(null); // Google フォトなどが候補に並ぶのを防ぐ
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
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.addFlags(FLAG_SHOW_WHEN_LOCKED_HIDDEN);

        Log.d(TAG, "Intent modification complete. Proceeding with hijacked intent.");
    }
}
