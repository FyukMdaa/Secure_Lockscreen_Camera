package com.github.droserasprout.lockscreencamera;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.github.droserasprout.lockscreencamera.hook.ActivityVisibilitySpoofHook;
import com.github.droserasprout.lockscreencamera.hook.CameraActivityLifecycleHook;
import com.github.droserasprout.lockscreencamera.hook.CameraGestureLauncherHook;
import com.github.droserasprout.lockscreencamera.hook.DecorViewProtectionHook;
import com.github.droserasprout.lockscreencamera.hook.GalleryRedirectHook;
import com.github.droserasprout.lockscreencamera.hook.KeyguardDismissBlockHook;
import com.github.droserasprout.lockscreencamera.hook.KeyguardIntentRewriteHook;
import com.github.droserasprout.lockscreencamera.hook.MediaStoreSessionTrackingHook;
import com.github.droserasprout.lockscreencamera.hook.MiscSystemHook;
import com.github.droserasprout.lockscreencamera.util.CameraPackageUtil;
import com.github.droserasprout.lockscreencamera.util.ModulePrefs;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * モジュールのエントリーポイント。
 * 各機能は {@code hook} パッケージ内の専用クラスに委譲する。
 *
 * パッケージ判定について:
 *   設定画面で選択されたパッケージリストを SharedPreferences から読み出し、
 *   一致する場合のみフックを適用する。設定が空の場合はフォールバックリストを使う。
 */
public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        Context context = (Context) param.getApplication();

        // 設定ベースの判定（フォールバック付き）
        boolean enabled;
        try {
            enabled = ModulePrefs.isPackageEnabled(context, pkg);
        } catch (Exception e) {
            enabled = CameraPackageUtil.isCameraPackage(pkg);
        }

        if (!enabled) {
            log(Log.DEBUG, TAG, "Skipping non-target package: " + pkg);
            return;
        }

        log(Log.INFO, TAG, "Targeting Camera App: " + pkg);

        DecorViewProtectionHook.install(this, context);
        KeyguardDismissBlockHook.install(this);
        ActivityVisibilitySpoofHook.install(this, context);
        KeyguardIntentRewriteHook.install(this, context);
        GalleryRedirectHook.install(this, context);
        CameraActivityLifecycleHook.install(this, context);
        MediaStoreSessionTrackingHook.install(this);
        MiscSystemHook.install(this, param);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        CameraGestureLauncherHook.install(this, param);
    }
}
