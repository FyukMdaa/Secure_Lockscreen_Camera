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
    // 重複スキャン防止用
    private static final Set<Class<?>> scannedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    /** クラス階層を遡ってメソッドを検索 */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getDeclaredMethod(name, params); }
            catch (NoSuchMethodException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchMethodException(name);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (with Debug Hook)");

        // 【重要】デバッグフック：setShowWhenLocked(false) を呼んでいる場所を特定
        try {
            Method target = Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class);
            hook(target).intercept(chain -> {
                boolean show = (boolean) chain.getArgs()[0];
                if (!show) {
                    log(Log.WARN, TAG, "!!! ALERT !!! setShowWhenLocked(false) called by: " + 
                        Log.getStackTraceString(new Throwable()));
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> cameraClass = Class.forName("com.android.camera.Camera", true, param.getClassLoader());

            // ライフサイクルフック
            String[] methods = {"onCreate", "onStart", "onResume"};
            for (String methodName : methods) {
                try {
                    Method m = methodName.equals("onCreate") 
                        ? findMethod(cameraClass, "onCreate", Bundle.class) 
                        : findMethod(cameraClass, methodName);
                    
                    hook(m).intercept(chain -> {
                        // 1. 元の処理を先に実行（MIUIの初期化処理を完了させる）
                        Object res = chain.proceed();
                        
                        // 2. 後処理として強制上書き（初期化結果をねじ伏せる）
                        Activity activity = (Activity) chain.getThisObject();
                        applyLockscreenFlags(activity);
                        forceFixInternalState(activity); // 【修正案】内部フィールド書き換え
                        
                        return res;
                    });
                } catch (Throwable ignored) {}
            }

            // MIUI固有のキーガードチェック無効化
            for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        log(Log.DEBUG, TAG, "Suppressed method: " + methodName);
                        if (m.getReturnType() == boolean.class) return false;
                        return null;
                    });
                } catch (Throwable ignored) {}
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook setup failed", t);
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

                    // 鍵画面が出ていない場合は通常起動
                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) {
                        return chain.proceed();
                    }

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    // フラグ追加：アニメーション抑制・フロント再配置など安定化策
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION  // 追加
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); // 追加
                    
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

    private void applyLockscreenFlags(Activity activity) {
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
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Flags apply failed", t);
        }
    }

    /**
     * 修正案：Activity 内部の boolean フィールドを強制上書き
     * chain.proceed() の後に実行することで、MIUIが設定した初期値をねじ伏せる
     */
    private void forceFixInternalState(Activity activity) {
        Class<?> clazz = activity.getClass();
        if (scannedClasses.contains(clazz)) return;

        try {
            Class<?> current = clazz;
            while (current != null && !current.getName().equals("android.app.Activity")) {
                for (Field f : current.getDeclaredFields()) {
                    String name = f.getName().toLowerCase();
                    // キーガード関連の boolean フィールドを抽出
                    if ((name.contains("keyguard") || name.contains("secure") || name.contains("locked")) 
                            && f.getType() == boolean.class) {
                        try {
                            f.setAccessible(true);
                            boolean oldVal = f.getBoolean(activity);
                            if (!oldVal) { // 元が false の場合のみ上書き（ログ抑制）
                                log(Log.INFO, TAG, "Patched field: " + f.getName() + " in " + current.getSimpleName() + " -> true");
                                f.setBoolean(activity, true);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Field patch failed", t);
        }
        scannedClasses.add(clazz);
    }
}
