package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    // 重複フィールドスキャン防止用キャッシュ
    private static final Set<Class<?>> patchedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (HyperOS 2.0 / SDK 36 Defense)");

        // 1. デバッグトラップ：setShowWhenLocked(false) の呼び出し元特定
        try {
            hook(Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class)).intercept(chain -> {
                // chain.getArgs() は List<Object> を返すため .get(0) を使用
                boolean val = (boolean) chain.getArgs().get(0);
                if (!val) {
                    log(Log.ERROR, TAG, "!!! ALERT !!! setShowWhenLocked(false) called: " + Log.getStackTraceString(new Throwable()));
                }                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 2. 【重要】強制終了阻止フック：MIUI が finish() を呼ぶのをブロック
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (act.getPackageName().equals("com.android.camera")) {
                    // finish() を無視してアプリが終了するのを防ぐ
                    log(Log.WARN, TAG, "!!! BLOCKED !!! MIUI attempted to finish camera: " + Log.getStackTraceString(new Throwable()));
                    // void メソッドなので、proceed() を呼ばずに null を返すことで実行をキャンセル
                    return null; 
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. KeyguardManager 判定偽装：システムに「ロック状態だがセキュア許可済み」と誤認させる
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 4. 主要ライフサイクルフック（匿名クラス・ヘルパーActivityも網羅）
        String[] criticalMethods = {"onCreate", "onPostCreate", "onResume", "onAttachedToWindow", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m;
                switch (mname) {
                    case "onCreate" -> m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                    case "onPostCreate" -> m = Activity.class.getDeclaredMethod("onPostCreate", Bundle.class);
                    case "onWindowFocusChanged" -> m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                    default -> m = Activity.class.getDeclaredMethod(mname);
                }

                hook(m).intercept(chain -> {
                    Activity act = (Activity) chain.getThisObject();
                    if (act.getPackageName().equals("com.android.camera")) {
                        applyDeepPatches(act);
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 5. MIUI固有キーガードチェック無効化（保険）
        for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
            try {                // FIX: chain.getMethod() は存在しないため、事前にメソッドオブジェクトから型を判定
                Method m = Activity.class.getDeclaredMethod(methodName);
                boolean isBool = m.getReturnType() == boolean.class;
                
                hook(m).intercept(chain -> {
                    log(Log.WARN, TAG, "Suppressed: " + methodName);
                    // 戻り値が boolean なら false を、void なら null を返す
                    if (isBool) return false;
                    return null;
                });
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System Server Starting: Hooking GestureLauncherService");

        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) {
                        return chain.proceed();
                    }

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION 
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    
                    context.startActivity(intent);
                    return null; // 元のシステム処理をキャンセル
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch secure camera", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {            log(Log.WARN, TAG, "GestureLauncherService hook skipped", t);
        }
    }

    /**
     * HyperOS 2.0 対策深層パッチ
     * Windowフラグ強制適用 + 内部booleanフィールド網羅的上書き
     */
    private void applyDeepPatches(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
            }

            Class<?> current = activity.getClass();
            if (!patchedClasses.contains(current)) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    for (Field f : current.getDeclaredFields()) {
                        String name = f.getName().toLowerCase();
                        // SDK 36 / HyperOS 2.0 で確認された判定キーワードを網羅
                        if ((name.contains("secure") || name.contains("keyguard") || name.contains("locked") ||
                             name.contains("showing") || name.contains("auth") || name.contains("policy") ||
                             name.contains("permission") || name.contains("ignore") || name.contains("camera")) 
                             && f.getType() == boolean.class) {
                            try {
                                f.setAccessible(true);
                                f.setBoolean(activity, true);
                                log(Log.DEBUG, TAG, "Field Patched: " + f.getName());
                            } catch (Throwable ignored) {}
                        }
                    }
                    current = current.getSuperclass();
                }
                patchedClasses.add(current);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Deep patch failed", t);
        }
    }
}
