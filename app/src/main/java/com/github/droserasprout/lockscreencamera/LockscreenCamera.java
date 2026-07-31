package com.github.droserasprout.lockscreencamera;

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

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * モジュールのエントリーポイント。
 * 各機能は {@code hook} パッケージ内の専用クラスに委譲し、このクラス自体は
 * 「対象パッケージかどうかの判定」と「どのフックを組み込むか」の一覧だけを持つ。
 *
 * 各フック機能の内容:
 *  0. DecorViewProtectionHook        - DecorView の透明化・非表示化を阻止
 *  1. KeyguardDismissBlockHook       - requestDismissKeyguard（PIN画面表示要求）をブロック
 *  2. ActivityVisibilitySpoofHook    - hasWindowFocus/isResumed のスプーフィング
 *  3. KeyguardIntentRewriteHook      - getIntent() の動的書き換え
 *  4. (ViewVisibilityProtectionHook は DecorViewProtectionHook に統合済み)
 *  5. GalleryRedirectHook            - ギャラリー確認画面を SecureViewerActivity へリダイレクト
 *  6. CameraActivityLifecycleHook    - ライフサイクルフック（セッション開始・自動終了・ウィンドウ復元）
 *  7. MediaStoreSessionTrackingHook  - 撮影された写真の URI をセッションに記録
 *  8. MiscSystemHook                 - onCameraUnavailable / canAuthenticate 対策
 *  -. CameraGestureLauncherHook      - システムサーバー側：カメラジェスチャーの起動ロジック
 */
public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    public LockscreenCamera() {
        super();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!CameraPackageUtil.isCameraPackage(pkg)) {
            return;
        }

        log(Log.INFO, TAG, "Targeting Camera App: " + pkg);

        DecorViewProtectionHook.install(this);
        KeyguardDismissBlockHook.install(this);
        ActivityVisibilitySpoofHook.install(this);
        KeyguardIntentRewriteHook.install(this);
        // ViewVisibilityProtectionHook は DecorViewProtectionHook に統合済み
        GalleryRedirectHook.install(this);
        CameraActivityLifecycleHook.install(this);
        MediaStoreSessionTrackingHook.install(this);
        MiscSystemHook.install(this, param);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        CameraGestureLauncherHook.install(this, param);
    }
}
