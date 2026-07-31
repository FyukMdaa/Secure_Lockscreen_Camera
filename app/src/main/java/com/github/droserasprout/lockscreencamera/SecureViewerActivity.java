package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;

import android.view.WindowManager;
import android.view.Window;
import androidx.viewpager2.widget.ViewPager2;

import com.github.droserasprout.lockscreencamera.ui.PhotoAdapter;
import com.github.droserasprout.lockscreencamera.ui.SwipeDismissLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ロック画面上でセッション中の写真をスワイプ閲覧するための Activity。
 * FLAG_SECURE を維持し、スクリーンショット・画面録画からは保護する。
 */
public class SecureViewerActivity extends Activity {

    private static final String TAG = "SecureViewer";
    private static final String EXTRA_SESSION_PHOTOS = "session_photos_list";

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<Integer, Future<?>> pendingTasks = new ConcurrentHashMap<>();

    private BroadcastReceiver screenOffReceiver;
    private ViewPager2 viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ロック画面上に確実に表示するための設定
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setInheritShowWhenLocked(true);
        }

        // Window レベルでもフラグを設定（API 27 以降は Activity API で十分だが、
        // 一部 OEM では Window フラグも必要）
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
            window.setAttributes(lp);
            // スクリーンショット禁止
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }

        List<Uri> safeUris = resolveSafeUris();
        if (safeUris.isEmpty()) {
            Log.w(TAG, "No URIs to display, finishing");
            finish();
            return;
        }

        Log.i(TAG, "Displaying " + safeUris.size() + " photos");
        setupViewPager(safeUris);
        registerScreenOffReceiver();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // フォーカス取得時に再度ロック画面上への表示を確保
            setShowWhenLocked(true);
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // バックキーで閉じる
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private List<Uri> resolveSafeUris() {
        List<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_SESSION_PHOTOS);
        if (uris == null || uris.isEmpty()) {
            uris = new ArrayList<>();
            Uri singleUri = getIntent().getData();
            if (singleUri != null) uris.add(singleUri);
            if (!uris.isEmpty()) {
                Log.i(TAG, "Fallback to single image from Intent Data: " + uris.get(0));
            }
        }
        return uris != null ? uris : new ArrayList<>();
    }

    private void setupViewPager(List<Uri> safeUris) {
        viewPager = new ViewPager2(this);
        viewPager.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        viewPager.setBackgroundColor(0xFF000000);
        viewPager.setOffscreenPageLimit(1);

        SwipeDismissLayout container = new SwipeDismissLayout(this, viewPager, this::finish);
        container.addView(viewPager);
        setContentView(container);

        PhotoAdapter adapter = new PhotoAdapter(safeUris, this, executor, pendingTasks);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(safeUris.size() - 1, false);
    }

    private void registerScreenOffReceiver() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish();
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterScreenOffReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterScreenOffReceiver();
        for (Future<?> task : pendingTasks.values()) {
            task.cancel(true);
        }
        pendingTasks.clear();
        executor.shutdownNow();
    }

    private void unregisterScreenOffReceiver() {
        if (screenOffReceiver == null) return;
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (Exception ignored) {}
        screenOffReceiver = null;
    }
}
