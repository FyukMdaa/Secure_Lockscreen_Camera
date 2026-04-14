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

    // 書き換え対象のフィールド名リスト（全スキャンをやめて狙い撃ちにする）
    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked", 
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn"
    };

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Lite/Optimized Mode)");

        // 1. KeyguardManager 偽装（システム制約回避）
        // 軽量なメソッド置き換えのみ
        try {
            Class<?> kmClass = KeyguardManager.class;
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 2. Intent 保護（セキュアアクション維持）
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. 強制終了阻止（finish ブロック）
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                // 終了をブロック
                log(Log.WARN, TAG, "Blocked finish()");
                return null;
            });
        } catch (Throwable ignored) {}

        // 4. ライフサイクルフック（onCreate, onResume, onWindowFocusChanged）
        // 負荷を下げるため、onPostCreate 等は削り、重要なタイミングに集中
        String[] criticalMethods = {"onCreate", "onResume", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m;
                if (mname.equals("onCreate")) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if (mname.equals("onWindowFocusChanged")) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                hook(m).intercept(chain -> {
                    Activity act = (Activity) chain.getThisObject();
                    // パッケージチェック
                    if (act.getPackageName().equals("com.android.camera")) {
                        applyLitePatches(act);
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }
    }

    @Override    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        // ... (ジェスチャー起動処理は前回と同じ) ...
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
     * 軽量パッチ：重い全スキャンを廃止し、重要フィールドのみピンポイントで書き換え
     */
    private void applyLitePatches(Activity activity) {
        try {
            // 1. 標準フラグ適用（毎回実行）
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );            }

            // 2. 重要フィールドのピンポイント書き換え（高速化のため全スキャン廃止）
            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldIfExists(activity, fieldName, true);
            }
            // Integer 型のフィールドも対象
            setFieldIfExists(activity, "mKeyguardStatus", 1);
            
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Lite patch failed", t);
        }
    }

    /**
     * フィールドが存在すれば書き換えるヘルパーメソッド
     * 親クラスも辿るが、見つかった時点で終了するため高速
     */
    private void setFieldIfExists(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            while (current != null) {
                try {
                    Field f = current.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(obj, value);
                    // log(Log.DEBUG, TAG, "Patched: " + fieldName); // 負荷軽減のためログは最小限
                    return; // 成功したら探索終了
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
    }
}
