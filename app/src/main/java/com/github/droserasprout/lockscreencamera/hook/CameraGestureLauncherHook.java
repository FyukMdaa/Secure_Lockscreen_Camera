package com.github.droserasprout.lockscreencamera.hook;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * 電源ボタン二度押し等の「カメラジェスチャー」ハンドラ (GestureLauncherService) をフックし、
 * ロック中でも Secure Camera Intent を明示的な Component 指定で起動する。
 * MIUI の Resolver 経由起動やサードパーティ製カメラアプリが優先されない問題を回避する。
 */
public final class CameraGestureLauncherHook {

    private static final String TAG = "LockscreenCamera";
    private static final String EXTRA_START_BY_KEYGUARD = "com.miui.camera.extra.START_BY_KEYGUARD";
    private static final int FLAG_ACTIVITY_MATCH_EXTERNAL = 0x00040000; // hidden flag
    private static final int BACKGROUND_ACTIVITY_START_ALLOWED = 2;

    private CameraGestureLauncherHook() {}

    public static void install(XposedModule module, SystemServerStartingParam param) {
        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class, int.class);

            module.hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());
                    launchSecureCamera(context);
                    return true;
                } catch (Throwable t) {
                    module.log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "System hook skipped", t);
        }
    }

    private static void launchSecureCamera(Context context) {
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean isLocked = km != null && km.isKeyguardLocked();

        ComponentName target = resolveCameraComponent(context);

        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
        if (target != null) {
            intent.setComponent(target);
        }

        int flags = FLAG_ACTIVITY_MATCH_EXTERNAL | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;

        if (isLocked) {
            flags |= Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            intent.putExtra(EXTRA_START_BY_KEYGUARD, true);
            intent.putExtra("StartActivityWhenLocked", true);
            intent.putExtra("is_secure_camera", true);
        } else {
            intent.putExtra(EXTRA_START_BY_KEYGUARD, false);
            intent.putExtra("StartActivityWhenLocked", false);
        }

        intent.addFlags(flags);
        intent.putExtra("android.intent.extra.CAMERA_OPEN_ONLY", true);
        intent.putExtra("com.android.systemui.camera_launch_source", "lockscreen_affordance");

        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentBackgroundActivityStartMode(BACKGROUND_ACTIVITY_START_ALLOWED);
        }

        // SessionManager.start() はカメラアプリ側の onCreate (CameraActivityLifecycleHook) で
        // 実行されるため、ここでは呼ばない
        context.startActivity(intent, options.toBundle());
    }

    private static ComponentName resolveCameraComponent(Context context) {
        PackageManager pm = context.getPackageManager();

        Intent resolveIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
        ResolveInfo info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);

        // SECURE アクションを解決できない場合は通常アクションにフォールバック
        if (info == null || info.activityInfo.name.contains("Resolver")) {
            resolveIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);
        }

        if (info != null && !info.activityInfo.name.contains("Resolver")) {
            return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        }
        // 解決に失敗した場合の最終フォールバック（AOSP 標準カメラ）
        return new ComponentName("com.android.camera", "com.android.camera.Camera");
    }
}
