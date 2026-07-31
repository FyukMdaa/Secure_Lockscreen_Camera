package com.github.droserasprout.lockscreencamera.hook;

import io.github.libxposed.api.XposedModule;

/**
 * カメラアプリ内の SurfaceView 等が非表示化されるのを阻止する。
 *
 * 修正メモ: このクラスの setVisibility フックは DecorViewProtectionHook に統合された。
 * View.setVisibility は単一のフックで DecorView / 非 DecorView を問わず
 * カメラコンテキスト内で VISIBLE を強制するため、このクラスは空のまま残され、
 * LockscreenCamera からの install 呼び出しは削除される。
 */
public final class ViewVisibilityProtectionHook {

    private ViewVisibilityProtectionHook() {}

    /**
     * 何もしない。setVisibility の保護は {@link DecorViewProtectionHook} に統合済み。
     * 互換性のためクラス自体は残すが、新規コードからの呼び出しは不要。
     */
    public static void install(XposedModule module) {
        // setVisibility フックは DecorViewProtectionHook に統合されたため、
        // ここでは何もしない。
    }
}
