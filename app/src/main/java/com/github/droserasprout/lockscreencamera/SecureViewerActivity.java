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
import android.view.ViewGroup;
import android.view.WindowManager;

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

    // 並列デコードのためスレッド数を2に確保
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    // position → 実行中タスク のマップ（高速スワイプ時の古いタスクをキャンセルするため）
    private final ConcurrentHashMap<Integer, Future<?>> pendingTasks = new ConcurrentHashMap<>();

    private BroadcastReceiver screenOffReceiver;
    private ViewPager2 viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setInheritShowWhenLocked(true);
        }
        // LockscreenCamera 側の clearFlags フックとは異なり、
        // ここではスクリーンショット禁止のため FLAG_SECURE を意図的に維持する
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        List<Uri> safeUris = resolveSafeUris();
        if (safeUris.isEmpty()) {
            finish();
            return;
        }

        setupViewPager(safeUris);
        registerScreenOffReceiver();
    }

    /**
     * Intent から表示対象の URI リストを解決する。
     * 修正メモ: リファクタリング版では content:// 以外のスキームを厳格に除外していたが、
     * 一部のカメラアプリが file:// URI を渡すケースがあるため、
     * オリジナルの動作に合わせて null チェックのみとしスキーマ検証は行わない。
     */
    private List<Uri> resolveSafeUris() {
        List<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_SESSION_PHOTOS);
        if (uris == null || uris.isEmpty()) {
            uris = new ArrayList<>();
            Uri singleUri = getIntent().getData();
            if (singleUri != null) uris.add(singleUri);
            Log.i(TAG, "Fallback to single image from Intent Data");
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
        // onDestroy より早い段階で解除することで、プロセスが強制終了する前にも対応
        unregisterScreenOffReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // onStop で解除済みでも念のためガードする
        unregisterScreenOffReceiver();

        // 実行中タスクを全キャンセル
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
        } catch (Exception ignored) {
            // 既に解除済み
        }
        screenOffReceiver = null;
    }
}
