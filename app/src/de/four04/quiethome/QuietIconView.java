package de.four04.quiethome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Small vector controls, independent of fonts and external icon libraries. */
public final class QuietIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String kind;
    private int color = 0xfff6f8f7;
    public QuietIconView(Context context, String kind) { super(context); this.kind=kind; }
    public void setColor(int color) { this.color=color; invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save(); canvas.translate(getWidth()/2f,getHeight()/2f);
        float scale=Math.min(getWidth(),getHeight())/28f; canvas.scale(scale,scale);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND);
        if ("gear".equals(kind)) {
            canvas.drawCircle(0,0,7.7f,paint); canvas.drawCircle(0,0,3.1f,paint);
            for(int i=0;i<8;i++){canvas.save();canvas.rotate(i*45);canvas.drawLine(0,-8,0,-10.5f,paint);canvas.restore();}
        } else if ("wifi".equals(kind)) {
            canvas.drawArc(-11,-9,11,13,225,90,false,paint);canvas.drawArc(-7,-5,7,9,225,90,false,paint);canvas.drawArc(-3,-1,3,5,225,90,false,paint);
            paint.setStyle(Paint.Style.FILL);canvas.drawCircle(0,7,1.4f,paint);
        } else if ("hdmi".equals(kind)) {
            canvas.drawRoundRect(-10,-6,10,5,2,2,paint);canvas.drawLine(-5,5,-3,9,paint);canvas.drawLine(5,5,3,9,paint);canvas.drawLine(-3,9,3,9,paint);
            for(int i=-5;i<=5;i+=5)canvas.drawLine(i,-2,i,1,paint);
        } else if ("back".equals(kind)) {
            canvas.drawLine(3,-7,-4,0,paint);canvas.drawLine(-4,0,3,7,paint);
        } else {
            canvas.drawLine(-5,-5,5,5,paint);canvas.drawLine(-5,5,5,-5,paint);
        }
        canvas.restore();
    }
}
