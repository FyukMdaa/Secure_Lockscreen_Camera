package com.github.droserasprout.lockscreencamera.hook;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.github.droserasprout.lockscreencamera.util.ReflectionFieldUtil;

/** カメラ Activity のウィンドウ属性・内部フラグをロック画面上で描画可能な状態に強制する。 */
public final class WindowSecurityBypass {

    private static final String TAG = "LockscreenCamera";

    // 書き換え対象の内部フィールド名リスト（各 OEM のカメラ実装で使われがちな boolean 系フィールド）
    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
        "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground",
        "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
        "mUserAuthenticationFlag", "mIgnoreKeyguardCheck",
        "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground"
    };

    private WindowSecurityBypass() {}

    public static void apply(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setInheritShowWhenLocked(true);
            }

            Window window = activity.getWindow();
            if (window != null) {
                applyWindowFlags(window);
            }

            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                ReflectionFieldUtil.setFieldFast(activity, fieldName, true);
            }
            ReflectionFieldUtil.setFieldFast(activity, "mIsNormalIntent", false);
            ReflectionFieldUtil.setFieldFast(activity, "mShowEnteringAnimation", false);
            ReflectionFieldUtil.setFieldFast(activity, "mKeyguardStatus", 1);
            ReflectionFieldUtil.setFieldFast(activity, "mIsSecureCameraId", 0);
        } catch (Throwable t) {
            Log.d(TAG, "UI Fixes failed: " + t);
        }
    }

    private static void applyWindowFlags(Window window) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.setFormat(PixelFormat.TRANSLUCENT);

        WindowManager.LayoutParams lp = window.getAttributes();
        lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        window.setAttributes(lp);
        window.addFlags(lp.flags);
        window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        View decorView = window.getDecorView();
        if (decorView != null) {
            decorView.setAlpha(1.0f);
            decorView.setVisibility(View.VISIBLE);
            decorView.requestFocus();
        }
    }
}
