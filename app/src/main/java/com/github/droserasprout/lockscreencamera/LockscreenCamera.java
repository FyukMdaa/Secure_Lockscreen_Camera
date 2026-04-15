package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
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

    @Override    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Surface Survival / State Fix)");

        // 1. 【最重要】可視性・レジューム状態の完全偽装
        // MIUIカメラが「バックグラウンド/非フォーカス」と誤認してリソース解放するのを防ぐ
        try {
            hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> true);
            hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> true);
        } catch (Throwable ignored) {}

        // 2. 終了系メソッドの網羅的ブロック（自爆処理の完全阻止）
        String[] terminationMethods = {"finish", "finishAffinity", "finishAndRemoveTask"};
        for (String methodName : terminationMethods) {
            try {
                hook(Activity.class.getDeclaredMethod(methodName)).intercept(chain -> {
                    if (isCameraActivity((Activity) chain.getThisObject())) {
                        log(Log.WARN, TAG, "Blocked termination: " + methodName);
                        return null; // void メソッドなので null でキャンセル
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }
        // finishAfterTransition も追加
        try {
            hook(Activity.class.getDeclaredMethod("finishAfterTransition")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) {
                    log(Log.WARN, TAG, "Blocked termination: finishAfterTransition");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. SurfaceView の非表示化を阻止（NativeWindow 死亡防止）
        // MIUIが内部でプレビュービューを隠そうとするのを強制的に VISIBLE に書き換える
        try {
            hook(SurfaceView.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                Context ctx = ((SurfaceView) chain.getThisObject()).getContext();
                if (isCameraContext(ctx)) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) {
                        args.set(0, View.VISIBLE);
                        log(Log.DEBUG, TAG, "Prevented SurfaceView from being hidden");
                    }                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 4. システムロック状態の偽装
        try {
            hook(KeyguardManager.class.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
            hook(KeyguardManager.class.getDeclaredMethod("isKeyguardSecure")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 5. 信頼性偽装 (Referrer)
        try {
            hook(Activity.class.getDeclaredMethod("getReferrer")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject()))
                    return Uri.parse("android-app://android");
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 6. Intent の動的書き換え
        try {
            hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {
                Intent intent = (Intent) chain.proceed();
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    if (!intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                        intent.putExtra("is_secure_camera", true);
                        intent.putExtra("ShowCameraWhenLocked", true);
                        intent.putExtra("StartFromKeyguard", true);
                    }
                }
                return intent;
            });
        } catch (Throwable ignored) {}

        // 7. ライフサイクルフック（Window/BatchFix）
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

        // 8. 既存システムフック群
        try {
            Class<?> cbClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            hook(cbClass.getDeclaredMethod("onCameraUnavailable", String.class)).intercept(chain -> {
                log(Log.INFO, TAG, "Blocked onCameraUnavailable: " + ((List<?>) chain.getArgs()).get(0));
                return null;
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> bioClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(bioClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}
        
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null)
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    // ヘルパー：Activity判定
    private boolean isCameraActivity(Activity act) {
        try {
            return act != null && "com.android.camera".equals(act.getPackageName());
        } catch (Exception e) {
            try { return act.getClass().getName().startsWith("com.android.camera"); }             catch (Exception e2) { return false; }
        }
    }

    // ヘルパー：Context判定（SurfaceView用）
    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try { return "com.android.camera".equals(ctx.getPackageName()); } 
        catch (Exception e) { return ctx.getClass().getName().contains("camera"); }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            Class<?> gestureClass = Class.forName("com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method m = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class, int.class);

            hook(m).intercept(chain -> {
                try {
                    Method getCtx = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getCtx.invoke(chain.getThisObject());
                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                    intent.putExtra("ShowCameraWhenLocked", true);
                    intent.putExtra("StartFromKeyguard", true);
                    context.startActivity(intent);
                    return true;
                } catch (Throwable t) { log(Log.ERROR, TAG, "Launch failed", t); }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}
    }

    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            Intent intent = activity.getIntent();
            if (intent != null) {
                intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                intent.putExtra("ShowCameraWhenLocked", true);
            }

            Window win = activity.getWindow();
            if (win != null) {
                WindowManager.LayoutParams lp = win.getAttributes();                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                win.setAttributes(lp);
                win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                win.setFormat(PixelFormat.TRANSLUCENT);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setInheritShowWhenLocked(true);
                }
            }

            for (String f : TARGET_BOOLEAN_FIELDS) setFieldFast(activity, f, true);
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
        } catch (Throwable t) { log(Log.DEBUG, TAG, "Fixes failed", t); }
    }

    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> c = obj.getClass();
            String key = c.getName() + ":" + fieldName;
            Field f = fieldCache.computeIfAbsent(key, k -> {
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
