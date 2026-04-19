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
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.InputStream;
import java.lang.ref.WeakReference;
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

        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setInheritShowWhenLocked(true);
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

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

        viewPager = new ViewPager2(this);
        viewPager.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        viewPager.setBackgroundColor(0xFF000000);
        viewPager.setOffscreenPageLimit(1);

        SwipeDismissLayout container = new SwipeDismissLayout(this);
        container.addView(viewPager);
        setContentView(container);

        adapter = new PhotoAdapter(uris, this);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(uris.size() - 1, false);

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

    private Bitmap decodeSampledBitmapFromUri(Uri uri, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, options);
            }
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
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
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
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

    private class SwipeDismissLayout extends FrameLayout {
        private float initialY;
        private float initialX;
        private boolean isSwiping;
        private final float swipeThreshold;

        public SwipeDismissLayout(Context context) {
            super(context);
            swipeThreshold = getResources().getDisplayMetrics().heightPixels * 0.2f;
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
                    if (dy > 50 && dy > dx) {
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
                    if (viewPager.getTranslationY() > swipeThreshold) {
                        viewPager.setAlpha(0f);
                        finish();
                    } else {
                        viewPager.animate().translationY(0).alpha(1.0f).setDuration(200)
                                .withStartAction(() -> isSwiping = false).start();
                    }
                    break;
            }
            return true;
        }
    }

    private class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder> {
        private final List<Uri> uris;
        private final WeakReference<Activity> activityRef;

        PhotoAdapter(List<Uri> uris, Activity activity) {
            this.uris = uris;
            this.activityRef = new WeakReference<>(activity);
        }

        @NonNull
        @Override
        public PhotoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PhotoView photoView = new PhotoView(parent.getContext());
            photoView.setBackgroundColor(0xFF000000);
            photoView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            photoView.setZoomable(true);
            return new PhotoViewHolder(photoView);
        }

        @Override
        public void onBindViewHolder(@NonNull PhotoViewHolder holder, int position) {
            holder.photoView.setImageDrawable(null);
            executor.execute(() -> {
                Activity activity = activityRef.get();
                if (activity == null || activity.isDestroyed()) return;

                int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
                Bitmap bitmap = decodeSampledBitmapFromUri(uris.get(position), screenWidth, screenHeight);

                if (bitmap != null) {
                    activity.runOnUiThread(() -> {
                        if (holder.getAdapterPosition() == position) {
                            holder.photoView.setImageBitmap(bitmap);
                        } else {
                            bitmap.recycle();
                        }
                    });
                }
            });
        }

        // リサイクル時のメモリ解放
        @Override
        public void onViewRecycled(@NonNull PhotoViewHolder holder) {
            super.onViewRecycled(holder);
            holder.photoView.setImageDrawable(null);
        }

        @Override
        public int getItemCount() { return uris.size(); }

        class PhotoViewHolder extends RecyclerView.ViewHolder {
            PhotoView photoView;
            PhotoViewHolder(PhotoView pv) { super(pv); photoView = pv; }
        }
    }
}
