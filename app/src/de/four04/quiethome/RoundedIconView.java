package de.four04.quiethome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Clips the actual fitted artwork, including wide logos, without cropping it. */
public final class RoundedIconView extends ImageView {
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final float corner;
    public RoundedIconView(Context context) {
        super(context);
        corner = 10 * getResources().getDisplayMetrics().density;
        setScaleType(ScaleType.FIT_CENTER);
    }
    @Override protected void onDraw(Canvas canvas) {
        Drawable drawable = getDrawable();
        if (drawable == null) { super.onDraw(canvas); return; }
        bounds.set(drawable.getBounds());
        getImageMatrix().mapRect(bounds);
        bounds.offset(getPaddingLeft(), getPaddingTop());
        float radius = Math.min(corner, Math.min(bounds.width(), bounds.height()) * 0.24f);
        clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip); super.onDraw(canvas); canvas.restoreToCount(save);
    }
}
