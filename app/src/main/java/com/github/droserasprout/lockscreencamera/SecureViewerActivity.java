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
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecureViewerActivity extends Activity {

    private static final String TAG = "SecureViewer";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private BroadcastReceiver screenOffReceiver;
    private ViewPager2 viewPager;
    private PhotoAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. ロック画面表示 & セキュリティ設定
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setInheritShowWhenLocked(true);
        }
        // スクリーンショット/録画を物理的に防止
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        // 2. データの受け取り（Intent から）
        List<Uri> uris = getIntent().getParcelableArrayListExtra("session_photos_list");

        // フォールバック: リストが空でも、単一画像の Intent Data はあるか確認
        if (uris == null || uris.isEmpty()) {
            Uri singleUri = getIntent().getData();
            if (singleUri != null) {
                uris = new ArrayList<>();
                uris.add(singleUri);
                Log.i(TAG, "Fallback to single image from Intent Data");
            }
        }

        if (uris == null || uris.isEmpty()) {
            Log.w(TAG, "No photos to show");
            finish();
            return;
        }

        // 3. UIの構築 (ViewPager2)
        viewPager = new ViewPager2(this);
        viewPager.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        viewPager.setBackgroundColor(0xFF000000); // 背景を黒で統一
        setContentView(viewPager);

        // Adapterのセット
        adapter = new PhotoAdapter(uris);
        viewPager.setAdapter(adapter);

        // 初期位置を最新の画像（右端）に設定
        viewPager.setCurrentItem(uris.size() - 1, false);

        // 4. 操作性の改善 (ダブルタップで終了)
        GestureDetector gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                finish();
                return true;
            }
        });

        // ViewPager2のスクロールを妨げないようにしつつ、ジェスチャーを検知
        viewPager.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });

        Toast.makeText(this, "ダブルタップで終了 | ピンチでズーム", Toast.LENGTH_SHORT).show();

        // 5. 画面OFFで自動終了
        screenOffReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { finish(); }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, filter);
        }
    }

    // OOM対策: 画面サイズに合わせて画像を縮小して読み込む
    private Bitmap decodeSampledBitmapFromUri(Uri uri, int reqWidth, int reqHeight) {
        try {
            // 1. 画像のサイズだけ読み込む
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, options);
            }

            // 2. 縮小率を計算
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;

            // 3. 縮小して読み込む
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, options);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode: " + uri, e);
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (screenOffReceiver != null) {
            try { unregisterReceiver(screenOffReceiver); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
    }

    // ViewPager2用のRecyclerView Adapter
    private class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {
        private final List<Uri> uris;

        PhotoAdapter(List<Uri> uris) {
            this.uris = uris;
        }

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // ImageViewの代わりにPhotoViewを使用
            PhotoView photoView = new PhotoView(parent.getContext());
            photoView.setBackgroundColor(0xFF000000);
            photoView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            
            // ピンチズームを有効にする（デフォルトで有効だが明示的に設定）
            photoView.setZoomable(true);
            // ダブルタップズームを無効にする（終了操作と競合を避けるため）
            photoView.setAllowDoubleTapZoom(false);
            
            return new PhotoViewHolder(photoView);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            holder.photoView.setImageDrawable(null); // リサイクル時にクリア

            executor.execute(() -> {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                Bitmap bitmap = decodeSampledBitmapFromUri(uris.get(position), screenWidth, screenHeight);

                if (bitmap != null) {
                    runOnUiThread(() -> {
                        // 非同期読み込み完了時に位置が変わっていないか確認
                        if (holder.getAdapterPosition() == position) {
                            holder.photoView.setImageBitmap(bitmap);
                        } else {
                            bitmap.recycle();
                        }
                    });
                }
            });
        }

        @Override
        public int getItemCount() {
            return uris.size();
        }

        class PhotoViewHolder extends RecyclerView.ViewHolder {
            PhotoView photoView;

            PhotoViewHolder(PhotoView pv) {
                super(pv);
                photoView = pv;
            }
        }
    }
}
