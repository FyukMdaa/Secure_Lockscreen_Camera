package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.app.KeyguardManager;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** requestDismissKeyguard（PIN 画面表示要求）を完全にブロックする。 */
public final class KeyguardDismissBlockHook {

    private static final String TAG = "LockscreenCamera";

    private KeyguardDismissBlockHook() {}

    public static void install(XposedModule module) {
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                    "requestDismissKeyguard", Activity.class, KeyguardManager.KeyguardDismissCallback.class);
            module.hook(dismissMethod).intercept(chain -> {
                module.log(Log.WARN, TAG, "BLOCKED: requestDismissKeyguard (Preventing PIN screen)");
                return null;
            });
        } catch (Throwable ignored) {
            // このデバイス／OS バージョンに該当メソッドが存在しない場合はスキップ
        }
    }
}
