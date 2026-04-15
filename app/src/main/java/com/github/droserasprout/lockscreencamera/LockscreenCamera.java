package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri; // getReferrer 用
import android.os.Build;
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
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    
    // フィールド検索用キャッシュ
    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    // 書き換え対象の内部フィールド名リスト
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

        log(Log.INFO, TAG, "Targeting com.android.camera (Referrer/Finish Bypass Strategy)");

        // 1. KeyguardManager.isKeyguardLocked() を偽装 -> 常に false (ロック解除済み) を返す
        try {
            hook(KeyguardManager.class.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 2. Activity.getReferrer() を偽装 -> 呼び出し元をシステム (android) に偽装
        // これにより、MIUI が「信頼できる起動か」を判定する材料をねじ伏せる
        try {
            hook(Activity.class.getDeclaredMethod("getReferrer")).intercept(chain -> {
                log(Log.DEBUG, TAG, "Spoofing Referrer -> android-app://android");
                return Uri.parse("android-app://android");
            });
        } catch (Throwable ignored) {}

        // 3. Activity.finish() の強制ブロック（力技）
        // LaunchGuard が "Rejecting" と判断した直後に呼ぶ finish() を阻止し、Activity を維持させる
        // ※ これによりユーザーが「戻る」ボタンを押しても閉じなくなる可能性があります（キオスク化）
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (isCameraActivity(act)) {
                    log(Log.WARN, TAG, "BLOCKED finish() to prevent LaunchGuard suicide.");
                    // void メソッドなので null を返して実行をキャンセル
                    return null;
                }
                return chain.proceed();
            });
            // finishAfterTransition も同様にブロック（念のため）
            hook(Activity.class.getDeclaredMethod("finishAfterTransition")).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (isCameraActivity(act)) {
                    log(Log.WARN, TAG, "BLOCKED finishAfterTransition().");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 4. Activity.getIntent() の横取りとフラグ注入
        // アプリが Intent を読み取る直後に、MIUI が期待するフラグを注入する
        try {
            hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {                Intent intent = (Intent) chain.proceed();
                if (intent != null) {
                    // 未設定の場合のみ注入
                    if (!intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                        log(Log.DEBUG, TAG, "Injecting MIUI Intent Extras dynamically");
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                        intent.putExtra("is_secure_camera", true);
                        intent.putExtra("ShowCameraWhenLocked", true);
                        intent.putExtra("StartFromKeyguard", true);
                    }
                }
                return intent;
            });
        } catch (Throwable ignored) {}

        // 5. ライフサイクルフック（attachBaseContext, onCreate 等）
        // これらは Window フラグの設定やバッフィクスを行うために維持
        String[] criticalMethods = {"attachBaseContext", "onCreate", "onStart", "onResume", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m;
                if ("attachBaseContext".equals(mname)) {
                    m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                } else if ("onCreate".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if ("onWindowFocusChanged".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                final String methodName = mname;
                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        Activity act = (Activity) thisObj;
                        // attachBaseContext では getPackageName が null の可能性があるためクラス名で判定
                        boolean isTarget = false;
                        try { isTarget = isCameraActivity(act); } catch (Exception e) {
                            isTarget = act.getClass().getName().startsWith("com.android.camera");
                        }

                        if (isTarget) {
                            if ("onWindowFocusChanged".equals(methodName)) {
                                boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                                if (!hasFocus) return chain.proceed();
                            }
                            applyWindowAndBufferFixes(act);
                        }
                    }                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 6. その他システムフック群（既存）
        
        // CameraManager AvailabilityCallback ブロック
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            Method onUnavailable = callbackClass.getDeclaredMethod("onCameraUnavailable", String.class);
            hook(onUnavailable).intercept(chain -> {
                String cameraId = (String) ((List<?>) chain.getArgs()).get(0);
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + cameraId);
                return null;
            });
        } catch (Throwable ignored) {}

        // BiometricManager 偽装
        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}

        // Intent 保護 (setIntent)
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    } // onPackageReady end

    /**
     * カメラアプリの Activity かどうか判定するヘルパー
     */
    private boolean isCameraActivity(Activity act) {
        try {
            if (act == null) return false;
            String pkg = act.getPackageName();
            return pkg != null && pkg.equals("com.android.camera");
        } catch (Exception e) {
            try {
                return act.getClass().getName().startsWith("com.android.camera");
            } catch (Exception e2) {
                return false;
            }
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

                    intent.putExtra("android.intent.extra.CAMERA_OPEN_SOURCE", "lockscreen_gesture");
                    intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                    intent.putExtra("ShowCameraWhenLocked", true);
                    intent.putExtra("StartFromKeyguard", true);
                    
                    context.startActivity(intent);
                    return true;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    }

    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Intent intent = activity.getIntent();
            if (intent != null) {
                intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                intent.putExtra("ShowCameraWhenLocked", true);
                intent.putExtra("android.intent.extra.CAMERA_OPEN_SOURCE", "lockscreen_gesture");
                intent.putExtra("StartFromKeyguard", true);
            }
            Window window = activity.getWindow();
            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                window.setAttributes(lp);

                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null) km.requestDismissKeyguard(activity, null);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setInheritShowWhenLocked(true);
                }
            }

            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Fixes failed", t);
        }
    }

    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;
            
            Field f = fieldCache.computeIfAbsent(cacheKey, k -> {
                Class<?> c = current;
                while (c != null && !c.getName().equals("android.app.Activity")) {
                    try {
                        Field found = c.getDeclaredField(fieldName);
                        found.setAccessible(true);
                        return found;
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();                    }
                }
                return null;
            });

            if (f != null) f.set(obj, value);
        } catch (Throwable ignored) {}
    }
}
