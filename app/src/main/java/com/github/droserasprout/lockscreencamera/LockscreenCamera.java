package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
// 新APIの正しいパラメータクラスをインポート
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    // 重複フック防止用のクラスキャッシュ
    private static final Set<Class<?>> appliedClasses = new HashSet<>();

    // 【重要】新APIではコンストラクタに引数は不要です
    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        // ターゲットパッケージ（MIUI Cameraなど）に限定
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting " + param.getPackageName());

        try {
            // Activityのライフサイクルメソッドをフック
            String[] lifecycleMethods = {"onCreate", "onStart", "onResume"};
            for (String methodName : lifecycleMethods) {
                Method method = methodName.equals("onCreate")
                        ? Activity.class.getDeclaredMethod(methodName, Bundle.class)
                        : Activity.class.getDeclaredMethod(methodName);

                hook(method).intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    // スコープ外のActivityへの影響を防ぐため実行時に再チェック
                    if (activity.getPackageName().equals("com.android.camera")) {
                        applyLockscreenFlags(activity);
                        suppressKeyguardMethods(activity.getClass());
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook Activity lifecycle", t);
        }
    }

    // 【重要】システムサーバーフックは onSystemServerStarting が公式仕様です
    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System Server Starting: Hooking GestureLauncherService");

        try {
            Class<?> gestureClass = param.getClassLoader().loadClass(
                    "com.android.server.GestureLauncherService");
            
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                log(Log.INFO, TAG, "Intercepted GestureLauncherService.handleCameraGesture");
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                    
                    context.startActivity(intent);
                    
                    // 元の処理をキャンセルして独自の起動のみ実行（二重起動防止）
                    return null;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch secure camera", t);
                }
                return chain.proceed();
            });
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            log(Log.WARN, TAG, "GestureLauncherService not found (ROM dependent)", e);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "System server hook error", t);
        }
    }

    /**
     * ロック画面上での表示を維持するためのウィンドウフラグ適用
     * FLAG_DISMISS_KEYGUARD を追加し、MIUIのキーガード再描画によるちらつきを抑制
     */
    private void applyLockscreenFlags(Activity activity) {
        try {
            // Android 8.1+ 標準API
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);

            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | // 画面オフ防止
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD // ちらつき防止の保険
                );
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Flags application skipped", t);
        }
    }

    /**
     * キーガード関連の監視メソッドを動的に検出し、戻り値を書き換えてカメラの強制終了を防ぐ
     */
    private void suppressKeyguardMethods(Class<?> clazz) {
        if (appliedClasses.contains(clazz)) return;
        
        Class<?> current = clazz;
        int depth = 0;
        final int MAX_DEPTH = 5; // スーパークラスの遡り制限

        while (current != null && depth < MAX_DEPTH && !current.getName().equals("android.app.Activity")) {
            for (Method m : current.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                // 引数が多すぎる、戻り値が複雑なメソッドは除外
                if (m.getParameterTypes().length > 2) continue;
                Class<?> ret = m.getReturnType();
                if (ret != boolean.class && ret != void.class && ret != int.class) continue;

                // keyguard / secure / camera 関連のメソッドをターゲット
                if (name.contains("keyguard") || name.contains("secure") || name.contains("camera")) {
                    try {
                        hook(m).intercept(chain -> getSafeDefault(m));
                    } catch (Throwable ignored) {
                        // フック失敗時は無視
                    }
                }
            }
            current = current.getSuperclass();
            depth++;
        }
        appliedClasses.add(clazz);
    }

    /**
     * フックしたメソッドに対する安全なデフォルト戻り値を生成
     * MIUIの監視ロジックを欺くために、特定の条件では true を返す
     */
    private Object getSafeDefault(Method m) {
        Class<?> type = m.getReturnType();
        String name = m.getName().toLowerCase();

        if (type == boolean.class) {
            // 「セキュアか」「ロック中か」を問うメソッド -> true を返して正常系と誤認させる
            if (name.contains("check") || name.contains("issecure") || name.contains("islocked")) return true;
            // 「閉じるべきか」「隠すべきか」を問うメソッド -> false を返して表示を維持させる
            if (name.contains("dismiss") || name.contains("hide") || name.contains("shouldfinish")) return false;
            return false;
        }
        if (type == int.class) return 0;
        return null; // void や Object
    }
}
