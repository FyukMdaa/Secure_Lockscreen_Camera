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
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    // 【軽量化】全スキャン廃止。判定に直結するフィールドのみを狙い撃ち
    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
        "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground", "mIsGalleryLock"
    };

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Optimized / Disconnect Fix)");

        // 1. KeyguardManager 偽装（システム認証チェック回避）
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 2. Intent 保護（セキュアアクション維持）
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) chain.getArgs().get(0);
                if (intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. finish() ブロック＆真犯人特定
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                log(Log.ERROR, TAG, "!!! Finish Attempted !!! Trace: " + Log.getStackTraceString(new Throwable()));
                return null; // 終了処理をキャンセル
            });
        } catch (Throwable ignored) {}

        // 4. 重要ライフサイクルフック（FLAG_SECURE クリア + 軽量パッチ）
        String[] criticalMethods = {"onCreate", "onResume", "onWindowFocusChanged", "onPause", "onStop"};
        for (String mname : criticalMethods) {
            try {
                Method m;
                if ("onCreate".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if ("onWindowFocusChanged".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                hook(m).intercept(chain -> {
                    Activity act = (Activity) chain.getThisObject();
                    
                    // OneTrack 等の分析コンポーネントは完全にスキップ（ANR 対策）
                    if (act.getClass().getName().contains("onetrack")) {
                        return chain.proceed();
                    }

                    if (act.getPackageName().equals("com.android.camera")) {
                        // onPause/onStop はブロックせず、トレースのみ出力して proceed
                        if ("onPause".equals(mname) || "onStop".equals(mname)) {
                            log(Log.WARN, TAG, "!!! " + mname + " Called !!! Trace: " + Log.getStackTraceString(new Throwable()));
                        }
                        applyLitePatches(act);
                    }                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
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
                    log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    }

    /**
     * 軽量パッチ：FLAG_SECURE クリア + 特定フィールド書き換え
     */
    private void applyLitePatches(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
                // ★最重要：MIUIが設定するセキュアフラグを強制解除（CameraService切断防止）
                win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }

            // 特定フィールドのピンポイント書き換え（高速）
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldIfExists(activity, fieldName, true);
            }
            setFieldIfExists(activity, "mKeyguardStatus", 1);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Lite patch failed", t);
        }
    }

    /**
     * 指定フィールドの親クラス探索＆値設定（全スキャン廃止でANR回避）
     */
    private void setFieldIfExists(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            while (current != null && !current.getName().equals("android.app.Activity")) {
                try {
                    Field f = current.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(obj, value);
                    return; // 成功即終了
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
    }
}
