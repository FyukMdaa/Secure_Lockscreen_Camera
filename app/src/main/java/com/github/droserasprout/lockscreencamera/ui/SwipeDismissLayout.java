package com.github.droserasprout.lockscreencamera.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 下方向スワイプで dismiss できる汎用コンテナ。
 * 内部の1 View（通常は ViewPager2）を追随させて動かし、閾値を超えたら {@link DismissListener#onDismiss()} を呼ぶ。
 */
public class SwipeDismissLayout extends FrameLayout {

    /** dismiss ジェスチャーが確定した際のコールバック */
    public interface DismissListener {
        void onDismiss();
    }

    private final View target;
    private final DismissListener listener;

    private float initialY;
    private float initialX;
    private boolean isSwiping;

    // dp → px 変換でハードコードピクセル値を排除
    private final float swipeStartThresholdPx;
    private final float swipeDismissThresholdPx;

    public SwipeDismissLayout(Context context, View target, DismissListener listener) {
        super(context);
        this.target = target;
        this.listener = listener;
        swipeDismissThresholdPx =
                context.getResources().getDisplayMetrics().heightPixels * 0.2f;
        swipeStartThresholdPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16,
                context.getResources().getDisplayMetrics());
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
            default:
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
                target.setTranslationY(translationY);
                target.setAlpha(1.0f - (progress * 0.8f));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (target.getTranslationY() > swipeDismissThresholdPx) {
                    target.setAlpha(0f);
                    if (listener != null) listener.onDismiss();
                } else {
                    target.animate()
                            .translationY(0)
                            .alpha(1.0f)
                            .setDuration(200)
                            .withStartAction(() -> isSwiping = false)
                            .start();
                }
                break;
            default:
                break;
        }
        return true;
    }
}
