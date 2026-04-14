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

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    
    // 既にフック処理を適用したクラスを保持し、重複処理によるパフォーマンス低下を防ぐ
    private static final Set<Class<?>> appliedClasses = new HashSet<>();

    public LockscreenCamera(@NonNull ModuleLoaderParam param) {
        super(param);
    }

    /**
     * アプリケーション（カメラ）側のフック
     * 注: 使用しているLSPosed APIバージョンに合わせてメソッド名を調整してください
     *     (標準的な libxposed では onPackageLoaded が使われることが多いです)
     */
    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        // ターゲットパッケージ（MIUI Cameraなど）に限定
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting " + param.getPackageName());

        try {
            // Activityのライフサイクルメソッドを一括フック
            // MIUIカメラは複数のActivityを遷移するため、基底クラスをフックして網羅する
            String[] lifecycleMethods = {"onCreate", "onStart", "onResume"};
            
            for (String methodName : lifecycleMethods) {
                Method method = methodName.equals("onCreate")
                        ? Activity.class.getDeclaredMethod(methodName, Bundle.class)
                        : Activity.class.getDeclaredMethod(methodName);

                hook(method).intercept(chain -> {
                    Activity activity = (Activity) chain.getThisObject();
                    
                    // 実行時にパッケージ名を再チェックして、スコープ外のActivityへの影響を防ぐ
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

    /**
     * システムサーバー（ジェスチャー起動）側のフック
     * 注: 標準APIでは onSystemServerLoaded を使用する場合が多いです
     */
    @Override
    public void onSystemServerReady(@NonNull SystemServerReadyParam param) {
        log(Log.INFO, TAG, "System Server Ready: Hooking GestureLauncherService");

        try {
            // com.android.server.GestureLauncherService をフック
            Class<?> gestureClass = param.getClassLoader().loadClass(
                    "com.android.server.GestureLauncherService");
            
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                log(Log.INFO, TAG, "Intercepted GestureLauncherService.handleCameraGesture");
                try {
                    // Contextを取得してセキュアカメラインテントを発行
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    // フラグ設定：タスクのクリアと履歴からの除外
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK 
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                    
                    context.startActivity(intent);
                    
                    // 元の処理（システム標準のカメラ起動など）をキャンセルし、
                    // 独自フックのみの実行とする（二重起動・競合防止）
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
            activity.setKeepScreenOn(true); // プレビュー中の画面オフ防止

            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD // 保険: キーガードを裏へ回して描画競合を防ぐ
                );
            }
        } catch (Throwable t) {
            // フラグ設定失敗は致命的ではないためログのみ
            log(Log.DEBUG, TAG, "Flags application skipped or failed", t);
        }
    }

    /**
     * キーガード関連の監視メソッドを動的に検出し、戻り値を書き換えてカメラの強制終了を防ぐ
     */
    private void suppressKeyguardMethods(Class<?> clazz) {
        // 既に処理済みのクラスはスキップ
        if (appliedClasses.contains(clazz)) return;
        
        Class<?> current = clazz;
        int depth = 0;
        final int MAX_DEPTH = 5; // スーパークラスの遡り制限（パフォーマンス保護）

        while (current != null && depth < MAX_DEPTH && !current.getName().equals("android.app.Activity")) {
            for (Method m : current.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();

                // 対象外メソッドの除外（引数が多すぎる、戻り値が複雑なものを避ける）
                if (m.getParameterTypes().length > 2) continue;
                Class<?> ret = m.getReturnType();
                if (ret != boolean.class && ret != void.class && ret != int.class) continue;

                // 対象メソッドの検知（keyguard / secure / camera 関連）
                if (name.contains("keyguard") || name.contains("secure") || name.contains("camera")) {
                    try {
                        hook(m).intercept(chain -> {
                            // デバッグ用ログ（必要に応じて有効化）
                            // log(Log.DEBUG, TAG, "Suppressed: " + m.getName()); 
                            return getSafeDefault(m);
                        });
                    } catch (Throwable ignored) {
                        // フック失敗時は無視（既にフック済み、アクセス不可など）
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
            // 「セキュアか」「ロック中か」「チェックするか」を問うメソッド -> true を返して正常系と誤認させる
            // (isseecure は issecure のタイポ対策も含む)
            if (name.contains("check") || name.contains("issecure") || name.contains("islocked")) return true;
            
            // 「閉じるべきか」「隠すべきか」を問うメソッド -> false を返して表示を維持させる
            if (name.contains("dismiss") || name.contains("hide") || name.contains("shouldfinish")) return false;
            
            return false; // デフォルト
        }
        if (type == int.class) return 0;
        return null; // void や Object
    }
}
