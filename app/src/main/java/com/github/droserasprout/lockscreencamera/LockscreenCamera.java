package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    // 対応させるカメラパッケージのリスト（必要に応じて追加）
    private static final String[] TARGET_CAMERA_PACKAGES = {
        "com.android.camera",
        "com.google.android.GoogleCamera",
        "com.miui.camera",
        "com.sec.android.app.camera",
        "com.openthecamera" // Open Camera等
    };

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        // 対象パッケージかどうかのチェックを柔軟に
        boolean isTarget = false;
        for (String pkg : TARGET_CAMERA_PACKAGES) {
            if (param.getPackageName().equals(pkg)) {
                isTarget = true;
                break;
            }
        }
        if (!isTarget) return;

        log(Log.INFO, TAG, "applying hooks for " + param.getPackageName());

        try {
            // アクティビティのクラス名はパッケージによって異なる可能性がありますが、
            // 多くのカメラアプリは "com.android.camera.Camera" を継承しているか、同名のクラスを持っているとは限りません。
            // ここでは汎用的に Activity をフックするのが難しいので、
            // メインアクティビティを特定してフックするか、対象クラス名を動的に取得する必要があります。
            // 以下のコードは元のロジックを維持していますが、
            // 実機で "ClassNotFoundException" が出る場合はクラス名の調査が必要です。

            Class<?> cameraClass = null;
            try {
                cameraClass = Class.forName("com.android.camera.Camera", true, param.getClassLoader());
            } catch (ClassNotFoundException e) {
                // フォールバック: パッケージ名 + .Camera を試す
                try {
                    cameraClass = Class.forName(param.getPackageName() + ".Camera", true, param.getClassLoader());
                } catch (ClassNotFoundException e2) {
                    log(Log.WARN, TAG, "Camera class not found, trying to find main activity...");
                    // PackageManagerを使ってメインアクティビティを取得してフックする処理を入れるとより堅牢です
                    return;
                }
            }

            Method onCreate = findMethod(cameraClass, "onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                log(Log.INFO, TAG, "hooking Camera activity onCreate");
                Activity activity = (Activity) chain.getThisObject();
                setShowWhenLockedFlags(activity);
                return chain.proceed();
            });

            for (String methodName : new String[]{"onStart", "onResume"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        Activity activity = (Activity) chain.getThisObject();
                        setShowWhenLockedFlags(activity);
                        return chain.proceed();
                    });
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Could not hook " + methodName, t);
                }
            }

            // checkKeyguard と checkKeyguardFlag を無効化
            for (String methodName : new String[]{"checkKeyguard", "checkKeyguardFlag"}) {
                try {
                    Method m = findMethod(cameraClass, methodName);
                    hook(m).intercept(chain -> {
                        log(Log.INFO, TAG, "suppressing " + methodName);
                        // 戻り値の型に応じて安全な値を返す
                        if (m.getReturnType() == boolean.class) {
                            return false; 
                        }
                        return null; 
                    });
                } catch (Throwable t) {
                    // メソッドが存在しない場合は無視（AOSP以外のカメラアプリによくある）
                    log(Log.WARN, TAG, "Could not hook " + methodName + " (method might not exist)", t);
                }
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error hooking camera app", t);
        }
    }

    private void setShowWhenLockedFlags(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            final Window win = activity.getWindow();
            win.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error setting window flags", t);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        log(Log.INFO, TAG, "onSystemServerStarting called");

        // GestureLauncherService
        try {
            Class<?> gestureClass = Class.forName(
                    "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                    "handleCameraGesture", boolean.class, int.class);
            hook(handleCameraGesture).intercept(chain -> {
                log(Log.INFO, TAG, "suppressing GestureLauncherService.handleCameraGesture");
                return null;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error hooking GestureLauncherService", t);
        }

        // PhoneWindowManager
        try {
            Class<?> pwmClass = Class.forName(
                    "com.android.server.policy.PhoneWindowManager", true, param.getClassLoader());
            Method handleCameraGesture = pwmClass.getDeclaredMethod("handleCameraGesture");
            hook(handleCameraGesture).intercept(chain -> {
                log(Log.INFO, TAG, "hooking PhoneWindowManager.handleCameraGesture");
                
                Object thisObject = chain.getThisObject();
                Context context = null;
                
                try {
                    // より安全にContextを取得する試み
                    Method getContextMethod = thisObject.getClass().getMethod("getContext");
                    getContextMethod.setAccessible(true);
                    context = (Context) getContextMethod.invoke(thisObject);
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "Failed to get context", e);
                    return null; // 元の処理を殺す
                }

                if (context == null) {
                    return null;
                }

                // デフォルトのカメラアプリを解決する（簡易版）
                Intent baseIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                PackageManager pm = context.getPackageManager();
                List<android.content.pm.ResolveInfo> resolveInfos = pm.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY);
                
                // 起動するインテントを作成
                Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                
                if (!resolveInfos.isEmpty()) {
                    // システムが推奨するカメラアプリがあればそれを使う
                    String pkgName = resolveInfos.get(0).activityInfo.packageName;
                    String clsName = resolveInfos.get(0).activityInfo.name;
                    intent.setPackage(pkgName);
                    intent.setClassName(pkgName, clsName); // クラス名も指定したほうが確実
                    log(Log.INFO, TAG, "Launching default secure camera: " + pkgName);
                } else {
                    // フォールバック: AOSP カメラ
                    intent.setPackage("com.android.camera");
                    log(Log.WARN, TAG, "No default camera found, falling back to com.android.camera");
                }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                
                try {
                    UserHandle current = (UserHandle) UserHandle.class.getField("CURRENT").get(null);
                    Method startActivityAsUser = Context.class.getMethod(
                        "startActivityAsUser", Intent.class, UserHandle.class);
                    startActivityAsUser.invoke(context, intent, current);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Error launching secure camera from PhoneWindowManager", t);
                }
                return null;  // 元の処理をキャンセル
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error hooking PhoneWindowManager", t);
        }
    }
}
