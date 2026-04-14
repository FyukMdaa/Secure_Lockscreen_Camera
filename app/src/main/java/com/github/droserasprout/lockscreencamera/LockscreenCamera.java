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
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    // 重複処理防止用キャッシュ
    private static final Set<Class<?>> patchedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (HyperOS 3.0 Deep Defense)");

        // 1. 【重要】KeyguardManager の判定偽装（システム制約回避）
        // 「ロックされていない」と誤認させることで、AuthenticationPolicy の壁を回避する
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}
        // 2. 【重要】setIntent フック（Intent 書き換え阻止）
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    log(Log.INFO, TAG, "Forced Intent Action to SECURE");
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. 【重要】onWindowAttributesChanged フック（後出しフラグ剥がし対策）
        try {
            hook(Activity.class.getDeclaredMethod("onWindowAttributesChanged", WindowManager.LayoutParams.class))
                .intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    if (activity.getPackageName().equals("com.android.camera")) {
                        applyDeepPatches(activity);
                    }
                    return chain.proceed();
                });
        } catch (Throwable ignored) {}

        // 4. 【重要】onPostCreate フック（初期化完了後の念押し）
        try {
            hook(Activity.class.getDeclaredMethod("onPostCreate", Bundle.class)).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                if (activity.getPackageName().equals("com.android.camera")) {
                    applyDeepPatches(activity);
                    log(Log.INFO, TAG, "Final post-create patch applied");
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 5. デバッグ：setShowWhenLocked(false) 呼び出し元特定
        try {
            hook(Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class)).intercept(chain -> {
                boolean val = (boolean) ((List<?>) chain.getArgs()).get(0);
                if (!val) {
                    log(Log.ERROR, TAG, "!!! ALERT !!! setShowWhenLocked(false) called: " + Log.getStackTraceString(new Throwable()));
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 6. finish() ブロック（強制終了阻止）
        try {            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                log(Log.ERROR, TAG, "!!! FINISH DETECTED !!! StackTrace: " + Log.getStackTraceString(new Throwable()));
                // 終了をブロックしてカメラの表示を維持
                return null;
            });
        } catch (Throwable ignored) {}

        // 7. onUserInteraction フック
        try {
            hook(Activity.class.getDeclaredMethod("onUserInteraction")).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (act.getPackageName().equals("com.android.camera")) {
                    applyDeepPatches(act);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 8. 主要ライフサイクルフック
        String[] criticalMethods = {"onCreate", "onResume", "onAttachedToWindow", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m;
                switch (mname) {
                    case "onCreate" -> m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
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

        // 9. MIUI固有キーガードチェック無効化
        for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
            try {
                Method m = Activity.class.getDeclaredMethod(methodName);
                boolean isBool = m.getReturnType() == boolean.class;
                hook(m).intercept(chain -> isBool ? false : null);
            } catch (Throwable ignored) {}
        }
    }

    @Override    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
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
     * HyperOS 3.0 対策深層パッチ
     */
    private void applyDeepPatches(Activity activity) {
        try {
            // 1. Windowフラグ徹底適用（毎回実行）
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD                );
            }

            // 2. フィールド・メソッドスキャン（初回のみ実行）
            Class<?> current = activity.getClass();
            if (!patchedClasses.contains(current)) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    for (Field f : current.getDeclaredFields()) {
                        String name = f.getName().toLowerCase();
                        // 最新キーワード追加: mIsSecureCamera, mIgnoreKeyguard, mInLockScreen 等
                        if ((name.contains("secure") || name.contains("keyguard") || name.contains("locked") ||
                             name.contains("showing") || name.contains("auth") || name.contains("policy") ||
                             name.contains("permission") || name.contains("ignore") || name.contains("camera") ||
                             name.contains("userauthenticated") || name.contains("focus") || name.contains("screen") ||
                             name.contains("gallarydisabled") || name.contains("objectcheck") ||
                             name.contains("isseecurecamera") || name.contains("ignorekeyguard") || name.contains("inlockscreen")) 
                             && f.getType() == boolean.class) {
                            try {
                                f.setAccessible(true);
                                f.setBoolean(activity, true);
                                log(Log.DEBUG, TAG, "Field Patched: " + f.getName());
                            } catch (Throwable ignored) {}
                        }
                        
                        // 【追加】mKeyguardStatus (int) の処理
                        // int型のフィールドで、名前に keyguardstatus が含まれる場合、値を1（認証済み/ロック中）に固定
                        if (name.contains("keyguardstatus") && f.getType() == int.class) {
                            try {
                                f.setAccessible(true);
                                f.setInt(activity, 1); 
                                log(Log.DEBUG, TAG, "Field Patched (Int): " + f.getName());
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
