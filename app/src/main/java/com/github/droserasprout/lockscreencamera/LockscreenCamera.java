package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri; // Referrer 用
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View; // SystemUI 用
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

        log(Log.INFO, TAG, "Targeting com.android.camera (NativeWindow Survival Patch)");

        // 1. Activity Visibility Spoofing (Surface 生存のための最重要ハック)
        // MIUI カメラに「お前は最前面で見えている」と嘘をつくことで、リソース解放を防ぐ
        
        // hasWindowFocus -> 常に true
        try {
            hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}

        // isResumed -> 常に true (Final メソッドの可能性あり)
        try {
            hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 2. Aggressive Finish Blocking (自爆処理の完全阻止)
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) {
                    log(Log.WARN, TAG, "BLOCKED finish() to prevent NativeWindow death.");
                    return null; // void メソッドなので null でキャンセル
                }
                return chain.proceed();
            });
            // finishAfterTransition も同様にブロック
            hook(Activity.class.getDeclaredMethod("finishAfterTransition")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) {
                    log(Log.WARN, TAG, "BLOCKED finishAfterTransition().");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. 信頼性偽装 (Referrer & Keyguard)
        try {
            hook(Activity.class.getDeclaredMethod("getReferrer")).intercept(chain -> {                if (isCameraActivity((Activity) chain.getThisObject()))
                    return Uri.parse("android-app://android"); // 呼び出し元をシステムに偽装
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        try {
            hook(KeyguardManager.class.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 4. Intent の動的書き換え
        try {
            hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {
                Intent intent = (Intent) chain.proceed();
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
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

        // 5. ライフサイクルフック（Window フラグ設定など）
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
                        // attachBaseContext では getPackageName が null になることがあるためクラス名で判定
                        boolean isTarget = isCameraActivity(act);                        
                        if (isTarget) {
                            if ("onWindowFocusChanged".equals(methodName)) {
                                boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                                if (!hasFocus) return chain.proceed();
                            }
                            applyWindowAndBufferFixes(act);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 6. その他システムフック群
        
        // CameraManager AvailabilityCallback ブロック
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            Method onUnavailable = callbackClass.getDeclaredMethod("onCameraUnavailable", String.class);
            hook(onUnavailable).intercept(chain -> {
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + ((List<?>) chain.getArgs()).get(0));
                return null;
            });
        } catch (Throwable ignored) {}

        // BiometricManager 偽装
        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}
        
        // setIntent フック
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    } // onPackageReady end

    /**
     * カメラアプリの Activity かどうか判定するヘルパー
     */
    private boolean isCameraActivity(Activity act) {
        try {
            if (act == null) return false;            String pkg = act.getPackageName();
            return pkg != null && pkg.equals("com.android.camera");
        } catch (Exception e) {
            try { return act.getClass().getName().startsWith("com.android.camera"); } 
            catch (Exception e2) { return false; }
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
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

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

    /**
     * Window 属性の操作と NativeWindow 維持処理
     */
    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Intent intent = activity.getIntent();            if (intent != null) {
                intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                intent.putExtra("ShowCameraWhenLocked", true);
                intent.putExtra("android.intent.extra.CAMERA_OPEN_SOURCE", "lockscreen_gesture");
                intent.putExtra("StartFromKeyguard", true);
            }

            Window window = activity.getWindow();
            if (window != null) {
                // 1. フラグ設定
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                window.setAttributes(lp);

                // 2. バッファ問題回避
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                // 3. System UI Visibility の強制（Surface 安定化）
                View decorView = window.getDecorView();
                if (decorView != null) {
                    // SYSTEM_UI_FLAG_LAYOUT_STABLE | SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    decorView.setSystemUiVisibility(0x00000100 | 0x00000400);
                }
                
                // 4. キーガード解除要求
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    KeyguardManager km = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null) km.requestDismissKeyguard(activity, null);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setInheritShowWhenLocked(true);
                }
            }

            // 5. 内部フィールドパッチ
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);
            
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Fixes failed", t);        }
    }

    /**
     * 高速フィールド探索（キャッシュ付き）
     */
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
                    } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                }
                return null;
            });

            if (f != null) f.set(obj, value);
        } catch (Throwable ignored) {}
    }
}
