package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    // 【最適化】スレッドセーフなキャッシュに変更
    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
        "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground", 
        "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
        "mUserAuthenticationFlag", "mIgnoreKeyguardCheck",
        "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground"
    };

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }
        log(Log.INFO, TAG, "Targeting com.android.camera (Stage 4: Optimized Bypass)");

        // 1. onCameraUnavailable ブロック
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            Method onUnavailable = callbackClass.getDeclaredMethod("onCameraUnavailable", String.class);
            hook(onUnavailable).intercept(chain -> {
                String cameraId = (String) ((List<?>) chain.getArgs()).get(0);
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + cameraId);
                return null;
            });
        } catch (Throwable ignored) {}

        // 2. BiometricManager 偽装
        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            Method canAuth = biometricClass.getDeclaredMethod("canAuthenticate", int.class);
            hook(canAuth).intercept(chain -> 0);
        } catch (Throwable ignored) {}

        // 3. KeyguardManager 偽装
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 4. Intent 保護 (setIntent の書き換えを先行実行)
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 5. finish() 監視＋ブロック（MIUIの強制終了を防ぐため null 返却でキャンセル）
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                log(Log.ERROR, TAG, "Finish Attempted by: " + Log.getStackTraceString(new Throwable()));
                return null;
            });
        } catch (Throwable ignored) {}

        // 6. ライフサイクルフック（MIUI初期化後にパッチ適用）
        String[] criticalMethods = {"onStart", "onResume", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {            try {
                Method m = "onWindowFocusChanged".equals(mname)
                        ? Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class)
                        : Activity.class.getDeclaredMethod(mname);

                hook(m).intercept(chain -> {
                    // 1. 先に本来の処理を通す（MIUIの内部初期化を阻害しない）
                    Object res = chain.proceed();
                    
                    Activity act = (Activity) chain.getThisObject();
                    if (act.getPackageName().equals("com.android.camera")) {
                        // フォーカス喪失時はパッチをスキップ
                        if ("onWindowFocusChanged".equals(mname)) {
                            boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                            if (!hasFocus) return res; 
                        }
                        applyWindowAndBufferFixes(act);
                    }
                    return res;
                });
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            Class<?> gestureClass = Class.forName("com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class, int.class);

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
            });        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    }

    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            activity.setInheritShowWhenLocked(true);

            Window win = activity.getWindow();
            if (win != null) {
                win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                win.setFormat(PixelFormat.TRANSLUCENT);
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                );
            }

            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Stage 4 fixes failed", t);
        }
    }

    /**
     * 高速フィールド探索（スレッドセーフキャッシュ付き）
     */
    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;
            
            // computeIfAbsent でスレッドセーフにキャッシュ取得/生成
            Field f = fieldCache.computeIfAbsent(cacheKey, k -> {
                Class<?> c = current;
                while (c != null && !c.getName().equals("android.app.Activity")) {
                    try {                        Field found = c.getDeclaredField(fieldName);
                        found.setAccessible(true);
                        return found;
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
                return null; // 見つからなかった場合
            });

            if (f != null) {
                f.set(obj, value);
            }
        } catch (Throwable ignored) {}
    }
}
