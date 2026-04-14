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
    // 重複スキャン防止用キャッシュ
    private static final Set<Class<?>> scannedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Final Battle Code)");

        // 1. 【デバッグ用】setShowWhenLocked(false) の呼び出し元を特定
        try {
            Method setter = Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class);
            hook(setter).intercept(chain -> {
                // 修正: List<Object> のため .get(0) を使用
                boolean value = (boolean) chain.getArgs().get(0);
                if (!value) {
                    log(Log.ERROR, TAG, "!!! ALERT !!! setShowWhenLocked(false) called by: " +                         Log.getStackTraceString(new Throwable()));
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 2. 【重要】ライフサイクルメソッドをフック
        // onCreate, onResume に加え、onAttachedToWindow と onWindowFocusChanged を追加
        // これにより、描画の直前で状態を確定させる
        String[] persistentMethods = {"onCreate", "onResume", "onAttachedToWindow", "onWindowFocusChanged"};

        for (String methodName : persistentMethods) {
            try {
                Method m;
                if (methodName.equals("onCreate")) {
                    m = Activity.class.getDeclaredMethod(methodName, Bundle.class);
                } else if (methodName.equals("onWindowFocusChanged")) {
                    m = Activity.class.getDeclaredMethod(methodName, boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(methodName);
                }

                hook(m).intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    
                    if (activity.getPackageName().equals("com.android.camera")) {
                        // MIUI の判定を欺くために、処理前に強制的に内部状態を書き換える
                        forceFixInternalFields(activity);
                        applyLockscreenFlags(activity);
                    }
                    
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
                // メソッドが存在しない場合は無視
            }
        }
        
        // 3. MIUI 固有のキーガードチェック無効化 (保険)
        for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
            try {
                // checkKeyguard は ActivityBase などに存在する可能性が高いため、
                // 見つからなくてもエラーにならないように try-catch しておく
                Method m = Activity.class.getDeclaredMethod(methodName);
                hook(m).intercept(chain -> {
                    log(Log.DEBUG, TAG, "Suppressed method: " + methodName);
                    if (m.getReturnType() == boolean.class) return false;
                    return null;
                });
            } catch (Throwable ignored) {}        }
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

                    // 鍵画面が出ていない場合は通常起動に任せる
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
        } catch (Throwable t) {
            log(Log.WARN, TAG, "GestureLauncherService hook skipped", t);
        }
    }

    private void applyLockscreenFlags(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Window win = activity.getWindow();            if (win != null) {
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
     * MIUI/HyperOS の内部 boolean フィールドを強制上書き
     * キーガードやセキュア状態に関連するフィールドを true に倒し、描画拒否を防ぐ
     */
    private void forceFixInternalFields(Activity activity) {
        Class<?> clazz = activity.getClass();
        if (scannedClasses.contains(clazz)) return; // 既にスキャン済みのクラスはスキップ（パフォーマンス対策）

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
                            // すべて true に設定して、MIUI の監視ロジックを欺く
                            f.setBoolean(activity, true);
                            log(Log.DEBUG, TAG, "Field Patched: " + f.getName());
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
