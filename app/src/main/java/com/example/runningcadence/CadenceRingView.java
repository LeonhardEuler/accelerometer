package com.example.runningcadence;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public final class CadenceRingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private ValueAnimator animator;
    private float displayedCadence;
    private int targetCadence;
    private boolean stable;

    public CadenceRingView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setCadence(int cadence, boolean stable) {
        if (this.stable != stable) {
            this.stable = stable;
            invalidate();
        }
        if (targetCadence == cadence) {
            return;
        }
        targetCadence = cadence;
        stopAnimation();
        if (cadence == 0 || !isAttachedToWindow()
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())) {
            displayedCadence = cadence;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(displayedCadence, cadence);
        animator.setDuration(220);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            displayedCadence = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float inset = 12 * density;
        float diameter = Math.min(getWidth(), getHeight()) - inset * 2;
        float left = (getWidth() - diameter) / 2;
        float top = (getHeight() - diameter) / 2;
        arc.set(left, top, left + diameter, top + diameter);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(7 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xff29364b);
        canvas.drawArc(arc, 135, 270, false, paint);
        paint.setColor(stable ? 0xffbcf36d : 0xff9ca9ff);
        canvas.drawArc(arc, 135, 270 * Math.max(0, Math.min(1, displayedCadence / 240f)), false, paint);
    }
}
