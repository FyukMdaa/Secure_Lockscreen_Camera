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
    // 重複処理防止用キャッシュ
    private static final Set<Class<?>> patchedClasses = new HashSet<>();
    private static final Set<Method> dynamicallyHookedMethods = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (HyperOS 2.0 / SDK 36 Deep Defense)");

        // 1. デバッグ：setShowWhenLocked(false) 呼び出し元特定
        try {
            hook(Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class)).intercept(chain -> {
                boolean val = (boolean) chain.getArgs().get(0);
                if (!val) {
                    log(Log.ERROR, TAG, "!!! ALERT !!! setShowWhenLocked(false) called: " + Log.getStackTraceString(new Throwable()));
                }                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 2. デバッグ：onPause / onStop 監視（終了トリガー特定用）
        for (String mname : new String[]{"onPause", "onStop"}) {
            try {
                hook(Activity.class.getDeclaredMethod(mname)).intercept(chain -> {
                    log(Log.WARN, TAG, "!!! TRACED !!! " + mname + " called: " + Log.getStackTraceString(new Throwable()));
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 3. 強制終了阻止：Activity.finish() をブロック
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (act.getPackageName().equals("com.android.camera")) {
                    log(Log.WARN, TAG, "!!! BLOCKED !!! finish() called: " + Log.getStackTraceString(new Throwable()));
                    return null; // void メソッドなので null 返却でキャンセル
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 4. KeyguardManager 判定偽装
        try {
            Class<?> km = KeyguardManager.class;
            hook(km.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
            hook(km.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 5. 【重要】setIntent 保護フック（Intent書き換え阻止）
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) chain.getArgs().get(0);
                if (intent != null && !MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(intent.getAction())) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    log(Log.INFO, TAG, "Force-restored Secure Intent Action");
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 6. 主要ライフサイクルフック
        String[] criticalMethods = {"onCreate", "onPostCreate", "onResume", "onAttachedToWindow", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m;                if (mname.equals("onCreate")) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if (mname.equals("onPostCreate")) {
                    m = Activity.class.getDeclaredMethod("onPostCreate", Bundle.class);
                } else if (mname.equals("onWindowFocusChanged")) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
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

        // 7. MIUI固有キーガードチェック無効化
        for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
            try {
                Method m = Activity.class.getDeclaredMethod(methodName);
                boolean isBool = m.getReturnType() == boolean.class;
                hook(m).intercept(chain -> isBool ? false : null);
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
                    return null;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch secure camera", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "GestureLauncherService hook skipped", t);
        }
    }

    /**
     * HyperOS 2.0+ 対策深層パッチ
     * Windowフラグ強制 + フィールド網羅的上書き + 動的メソッドフック
     */
    private void applyDeepPatches(Activity activity) {
        try {
            // 1. Windowフラグ徹底適用
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

            // 2. クラス階層スキャン（パフォーマンス保護付き）
            Class<?> current = activity.getClass();
            if (!patchedClasses.contains(current)) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    for (Field f : current.getDeclaredFields()) {
                        String name = f.getName().toLowerCase();
                        // 認証/ポリシー関連キーワードを網羅
                        if ((name.contains("secure") || name.contains("keyguard") || name.contains("locked") ||
                             name.contains("showing") || name.contains("auth") || name.contains("policy") ||
                             name.contains("permission") || name.contains("ignore") || name.contains("camera") ||                             name.contains("userauthenticated") || name.contains("focus")) 
                             && f.getType() == boolean.class) {
                            try {
                                f.setAccessible(true);
                                f.setBoolean(activity, true);
                                log(Log.DEBUG, TAG, "Field Patched: " + f.getName());
                            } catch (Throwable ignored) {}
                        }
                    }

                    // 動的メソッドフック（"careful", "confirm", "need" 等の判定メソッドを強制true）
                    for (Method m : current.getDeclaredMethods()) {
                        String mname = m.getName().toLowerCase();
                        if ((mname.contains("careful") || mname.contains("confirm") || mname.contains("need")) 
                                && m.getReturnType() == boolean.class 
                                && m.getParameterCount() <= 1
                                && !dynamicallyHookedMethods.contains(m)) {
                            try {
                                hook(m).intercept(chain -> true);
                                dynamicallyHookedMethods.add(m);
                                log(Log.DEBUG, TAG, "Dynamically hooked: " + m.getName());
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
