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
    // 重複フィールドスキャン防止用キャッシュ
    private static final Set<Class<?>> patchedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (Final Deep Patch / SDK 36 Ready)");

        // 【デバッグトラップ】誰が setShowWhenLocked(false) を呼んでいるか特定
        try {
            Method setter = Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class);
            hook(setter).intercept(chain -> {
                // 修正: List<Object> のため .get(0) を使用
                boolean val = (boolean) chain.getArgs().get(0);
                if (!val) {
                    log(Log.ERROR, TAG, "!!! ALERT !!! setShowWhenLocked(false) called by: " +                         Log.getStackTraceString(new Throwable()));
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 【重要】Activity.class のライフサイクルを直接フック
        // これにより、匿名クラスや内部ヘルパーActivityも漏れなく捕捉できる
        String[] criticalMethods = {"onCreate", "onPostCreate", "onResume", "onAttachedToWindow", "onWindowFocusChanged"};
        
        for (String mname : criticalMethods) {
            try {
                Method m;
                switch (mname) {
                    case "onCreate":
                        m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                        break;
                    case "onPostCreate":
                        m = Activity.class.getDeclaredMethod("onPostCreate", Bundle.class);
                        break;
                    case "onWindowFocusChanged":
                        m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                        break;
                    default:
                        m = Activity.class.getDeclaredMethod(mname);
                        break;
                }

                hook(m).intercept(chain -> {
                    Activity act = (Activity) chain.getThisObject();
                    // スコープ内のカメラActivityでのみ実行
                    if (act.getPackageName().equals("com.android.camera")) {
                        // 可視性/状態変化のたびに深層パッチを適用
                        applyDeepPatches(act);
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
                // 特定ROMでメソッドが存在しない場合は無視
            }
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System Server Starting: Hooking GestureLauncherService");

        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    // 鍵画面が出ていない場合は通常起動にフォールバック
                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) {
                        return chain.proceed();
                    }

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    // 【強化】Intentフラグ（存在しないフラグは削除済み）
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

    /**
     * 深層パッチ：Windowフラグ設定 + 内部booleanフィールドの強制上書き
     * MIUI/HyperOSの匿名クラス・認証ポリシーチェックをフィールドレベルで無力化する
     */
    private void applyDeepPatches(Activity activity) {
        try {
            // 1. 標準Windowフラグの徹底適用
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
            }

            // 2. フィールドの全スキャンと強制書き換え（パフォーマンス保護付き）
            Class<?> current = activity.getClass();
            if (!patchedClasses.contains(current)) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    for (Field f : current.getDeclaredFields()) {
                        String name = f.getName().toLowerCase();
                        // SDK 36 で確認された認証/ポリシー関連キーワードを網羅
                        if ((name.contains("secure") || name.contains("keyguard") || name.contains("locked") ||
                             name.contains("showing") || name.contains("auth") || name.contains("policy") ||
                             name.contains("permission")) && f.getType() == boolean.class) {
                            try {
                                f.setAccessible(true);
                                f.setBoolean(activity, true); // 監視ロジックを欺くために true 固定
                                log(Log.DEBUG, TAG, "Field Patched: " + f.getName());
                            } catch (Throwable ignored) {}
                        }
                    }
                    current = current.getSuperclass();
                }
                patchedClasses.add(current); // キャッシュ登録
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Deep patch failed", t);
        }
    }
}
