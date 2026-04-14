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
import java.util.List; // List インポートを追加
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    // 重複処理防止用キャッシュ
    private static final Set<Class<?>> patchedClasses = new HashSet<>();

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.getPackageName().equals("com.android.camera")) {
            return;
        }

        log(Log.INFO, TAG, "Targeting com.android.camera (HyperOS 3.0 Defense)");

        // 1. デバッグ：setShowWhenLocked(false) 呼び出し元特定
        try {
            hook(Activity.class.getDeclaredMethod("setShowWhenLocked", boolean.class)).intercept(chain -> {
                // List<Object> から引数を取得
                boolean val = (boolean) ((List<?>) chain.getArgs()).get(0);
                if (!val) {
                    log(Log.ERROR, TAG, "!!! ALERT !!! setShowWhenLocked(false) called: " + Log.getStackTraceString(new Throwable()));                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 2. デバッグ：onPause / onStop 監視（終了トリガー特定用）
        for (String mname : new String[]{"onPause", "onStop"}) {
            try {
                hook(Activity.class.getDeclaredMethod(mname)).intercept(chain -> {
                    log(Log.WARN, TAG, "!!! TRACED !!! " + mname + " called: " + Log.getStackTraceString(new Throwable()));
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 3. 強制終了阻止：Activity.finish() をブロック
        try {
            hook(Activity.class.getDeclaredMethod("finish")).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (act.getPackageName().equals("com.android.camera")) {
                    log(Log.WARN, TAG, "!!! BLOCKED !!! finish() called: " + Log.getStackTraceString(new Throwable()));
                    // 強制終了を無視（void なので null を返す）
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 4. 【重要】KeyguardManager 判定偽装（認証チェック回避）
        try {
            Class<?> kmClass = KeyguardManager.class;
            // 「鍵がかかっていない」または「デバイスがロックされていない」と誤認させる
            hook(kmClass.getDeclaredMethod("isDeviceLocked")).intercept(chain -> false);
            hook(kmClass.getDeclaredMethod("isKeyguardLocked")).intercept(chain -> false);
        } catch (Throwable ignored) {}

        // 5. Intent 書き換え阻止（セキュアアクション維持）
        try {
            hook(Activity.class.getDeclaredMethod("setIntent", Intent.class)).intercept(chain -> {
                Intent intent = (Intent) ((List<?>) chain.getArgs()).get(0);
                if (intent != null && !MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(intent.getAction())) {
                    intent.setAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    log(Log.INFO, TAG, "Force-restored Secure Intent Action");
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 6. onUserInteraction フック（タッチ操作時のフラグ維持）
        try {            hook(Activity.class.getDeclaredMethod("onUserInteraction")).intercept(chain -> {
                Activity act = (Activity) chain.getThisObject();
                if (act.getPackageName().equals("com.android.camera")) {
                    // タッチ操作で状態がリセットされないよう、再度パッチを適用
                    applyDeepPatches(act);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 7. 主要ライフサイクルフック
        String[] criticalMethods = {"onCreate", "onPostCreate", "onResume", "onAttachedToWindow", "onWindowFocusChanged"};
        for (String mname : criticalMethods) {
            try {
                Method m;
                if (mname.equals("onCreate")) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if (mname.equals("onPostCreate")) {
                    m = Activity.class.getDeclaredMethod("onPostCreate", Bundle.class);
                } else if (mname.equals("onWindowFocusChanged")) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                hook(m).intercept(chain -> {
                    Activity act = (Activity) chain.getThisObject();
                    if (act.getPackageName().equals("com.android.camera")) {
                        applyDeepPatches(act);
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 8. MIUI固有キーガードチェック無効化
        for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
            try {
                Method m = Activity.class.getDeclaredMethod(methodName);
                boolean isBool = m.getReturnType() == boolean.class;
                hook(m).intercept(chain -> isBool ? false : null);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "System Server Starting: Hooking GestureLauncherService");

        try {            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    // 鍵画面が出ていない場合は通常起動にフォールバック
                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    // 偽装フックが効いている場合は常に false が返る可能性があるため、
                    // ここではジェスチャーからの起動であれば常にセキュアカメラを起動する
                    // (本来の isKeyguardLocked チェックはシステム側の制御用)
                    
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

    /**
     * HyperOS 3.0 対策深層パッチ
     * Windowフラグ強制 + フィールド網羅的上書き + 動的メソッドフック
     * ※ onUserInteraction 等から頻繁に呼ばれるため、キャッシュによる最適化を含む
     */
    private void applyDeepPatches(Activity activity) {
        try {
            // 1. Windowフラグ徹底適用（毎回実行）
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            
            Window win = activity.getWindow();
            if (win != null) {
                win.addFlags(                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                );
            }

            // 2. フィールド・メソッドスキャン（初回のみ実行）
            Class<?> current = activity.getClass();
            if (!patchedClasses.contains(current)) {
                // フィールドスキャン
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    for (Field f : current.getDeclaredFields()) {
                        String name = f.getName().toLowerCase();
                        // キーワードを網羅（auth, policy, screen 等）
                        if ((name.contains("secure") || name.contains("keyguard") || name.contains("locked") ||
                             name.contains("showing") || name.contains("auth") || name.contains("policy") ||
                             name.contains("permission") || name.contains("ignore") || name.contains("camera") ||
                             name.contains("userauthenticated") || name.contains("focus") || name.contains("screen")) 
                             && f.getType() == boolean.class) {
                            try {
                                f.setAccessible(true);
                                f.setBoolean(activity, true);
                                log(Log.DEBUG, TAG, "Field Patched: " + f.getName());
                            } catch (Throwable ignored) {}
                        }
                    }

                    // 動的メソッドフック（policy, authenticate, verify 等）
                    for (Method m : current.getDeclaredMethods()) {
                        String mname = m.getName().toLowerCase();
                        if ((mname.contains("policy") || mname.contains("authenticate") || mname.contains("verified") ||
                             mname.contains("careful") || mname.contains("confirm") || mname.contains("need")) 
                                && m.getReturnType() == boolean.class 
                                && m.getParameterCount() <= 1) {
                            try {
                                hook(m).intercept(chain -> {
                                    log(Log.DEBUG, TAG, "Bypassing Auth Policy check: " + m.getName());
                                    return true; // 認証済みとして誤認させる
                                });
                            } catch (Throwable ignored) {}
                        }
                    }
                    current = current.getSuperclass();
                }
                patchedClasses.add(current);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Deep patch failed", t);
        }    }
}
