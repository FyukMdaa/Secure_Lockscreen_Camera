package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;

public class SecureViewerActivity extends Activity {

    private static final String TAG = "SecureViewer";

    // 並列デコードのためスレッド数を2に拡張
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    // position → 実行中タスク のマップ（高速スワイプ時の古いタスクをキャンセルするため）
    private final ConcurrentHashMap<Integer, Future<?>> pendingTasks = new ConcurrentHashMap<>();

    private BroadcastReceiver screenOffReceiver;
    private ViewPager2 viewPager;
    private PhotoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setInheritShowWhenLocked(true);
        }
        // SecureViewerActivity では FLAG_SECURE を意図的に維持する
        // （LockscreenCamera の clearFlags フックとは異なり、ここではスクリーンショット禁止が必要）
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );

        List<Uri> uris = getIntent().getParcelableArrayListExtra("session_photos_list");
        if (uris == null || uris.isEmpty()) {
            Uri singleUri = getIntent().getData();
            if (singleUri != null) {
                uris = new ArrayList<>();
                uris.add(singleUri);
            }
        }

        if (uris == null || uris.isEmpty()) {
            finish();
            return;
        }

        // URI のスキームを検証し content:// 以外を除外する
        List<Uri> safeUris = new ArrayList<>();
        for (Uri uri : uris) {
            if ("content".equals(uri.getScheme())) {
                safeUris.add(uri);
            } else {
                Log.w(TAG, "Rejected non-content URI: " + uri.getScheme());
            }
        }

        if (safeUris.isEmpty()) {
            finish();
            return;
        }

        viewPager = new ViewPager2(this);
        viewPager.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        viewPager.setBackgroundColor(0xFF000000);
        viewPager.setOffscreenPageLimit(1);

        SwipeDismissLayout container = new SwipeDismissLayout(this);
        container.addView(viewPager);
        setContentView(container);

        adapter = new PhotoAdapter(safeUris, this, executor, pendingTasks);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(safeUris.size() - 1, false);

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
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {}
            screenOffReceiver = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // onStop で解除済みでも念のりガード
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {}
            screenOffReceiver = null;
        }
        // 実行中タスクを全キャンセル
        for (Future<?> task : pendingTasks.values()) {
            task.cancel(true);
        }
        pendingTasks.clear();
        executor.shutdownNow();
    }

    // =========================================================================
    // ビットマップデコード（static — アクティビティへの暗黙参照を持たない）
    // =========================================================================

    @Nullable
    static Bitmap decodeSampledBitmapFromUri(
            Context context, Uri uri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, options);
            }
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, options);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode: " + uri, e);
            return null;
        }
    }

    static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // =========================================================================
    // SwipeDismissLayout
    // =========================================================================

    private class SwipeDismissLayout extends FrameLayout {
        private float initialY;
        private float initialX;
        private boolean isSwiping;
        // dp → px 変換でハードコードピクセル値を排除
        private final float swipeStartThresholdPx;
        private final float swipeDismissThresholdPx;

        SwipeDismissLayout(Context context) {
            super(context);
            swipeDismissThresholdPx =
                context.getResources().getDisplayMetrics().heightPixels * 0.2f;
            swipeStartThresholdPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16,
                context.getResources().getDisplayMetrics()
            );
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialY = ev.getY();
                    initialX = ev.getX();
                    isSwiping = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = ev.getY() - initialY;
                    float dx = Math.abs(ev.getX() - initialX);
                    // 下方向かつ水平移動より大きい場合のみ dismiss スワイプとして横取り
                    if (dy > swipeStartThresholdPx && dy > dx) {
                        isSwiping = true;
                        return true;
                    }
                    break;
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (!isSwiping) return super.onTouchEvent(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float dy = ev.getY() - initialY;
                    float translationY = Math.max(0, dy);
                    float progress = getHeight() > 0 ? translationY / getHeight() : 0;
                    viewPager.setTranslationY(translationY);
                    viewPager.setAlpha(1.0f - (progress * 0.8f));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (viewPager.getTranslationY() > swipeDismissThresholdPx) {
                        viewPager.setAlpha(0f);
                        finish();
                    } else {
                        viewPager.animate()
                            .translationY(0)
                            .alpha(1.0f)
                            .setDuration(200)
                            .withStartAction(() -> isSwiping = false)
                            .start();
                    }
                    break;
            }
            return true;
        }
    }

    // =========================================================================
    // PhotoAdapter（静的内部クラス — アクティビティへの暗黙参照を持たない）
    // =========================================================================

    private static class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {

        private final List<Uri> uris;
        private final WeakReference<Activity> activityRef;
        private final ExecutorService executor;
        private final ConcurrentHashMap<Integer, Future<?>> pendingTasks;

        PhotoAdapter(
                List<Uri> uris,
                Activity activity,
                ExecutorService executor,
                ConcurrentHashMap<Integer, Future<?>> pendingTasks) {
            this.uris = uris;
            this.activityRef = new WeakReference<>(activity);
            this.executor = executor;
            this.pendingTasks = pendingTasks;
        }

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PhotoView photoView = new PhotoView(parent.getContext());
            photoView.setBackgroundColor(0xFF000000);
            photoView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            photoView.setZoomable(true);
            return new PhotoViewHolder(photoView);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            holder.photoView.setImageDrawable(null);

            // 同じポジションの前回タスクをキャンセルして競合を防ぐ
            Future<?> prev = pendingTasks.remove(position);
            if (prev != null) prev.cancel(true);

            Activity activity = activityRef.get();
            if (activity == null || activity.isDestroyed()) return;

            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            // Context のみ渡し、アクティビティへの強参照をラムダに持ち込まない
            Context appContext = activity.getApplicationContext();
            Uri uri = uris.get(position);

            Future<?> task = executor.submit(() -> {
                Bitmap bitmap = decodeSampledBitmapFromUri(
                    appContext, uri, screenWidth, screenHeight);
                if (bitmap == null) return;

                Activity act = activityRef.get();
                if (act == null || act.isDestroyed()) {
                    bitmap.recycle();
                    return;
                }

                act.runOnUiThread(() -> {
                    pendingTasks.remove(position);
                    // バインド時のポジションと現在のポジションが一致する場合のみ反映
                    if (holder.getAdapterPosition() == position) {
                        holder.photoView.setImageBitmap(bitmap);
                    } else {
                        // ポジションずれが発生した場合はネイティブメモリを即解放
                        bitmap.recycle();
                    }
                });
            });

            pendingTasks.put(position, task);
        }

        @Override
        public void onViewRecycled(@NonNull PhotoViewHolder holder) {
            super.onViewRecycled(holder);
            // Drawable 参照を切り、GC がビットマップを回収できるようにする
            holder.photoView.setImageDrawable(null);
        }

        @Override
        public int getItemCount() {
            return uris.size();
        }

        static class PhotoViewHolder extends RecyclerView.ViewHolder {
            final PhotoView photoView;

            PhotoViewHolder(PhotoView pv) {
                super(pv);
                photoView = pv;
            }
        }
    }
}
