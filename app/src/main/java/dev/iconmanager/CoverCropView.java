package dev.iconmanager;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class CoverCropView extends View {
    private final Bitmap source;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float centreX = .5f, centreY = .5f, zoom = 1f, lastX, lastY;

    CoverCropView(Context context, Bitmap source) {
        super(context);
        this.source = source;
        setContentDescription(context.getString(R.string.crop_help));
    }

    void setZoom(float value) { zoom = value; clamp(); invalidate(); }

    private float cropWidth() {
        return Math.min(source.getWidth(), source.getHeight() * 9f / 16f) / zoom;
    }

    private float cropHeight() { return cropWidth() * 16f / 9f; }

    private void clamp() {
        float x = cropWidth() / source.getWidth() / 2;
        float y = cropHeight() / source.getHeight() / 2;
        centreX = Math.max(x, Math.min(1 - x, centreX));
        centreY = Math.max(y, Math.min(1 - y, centreY));
    }

    private RectF frame() {
        float width = Math.min(getWidth(), getHeight() * 9f / 16f);
        float height = width * 16f / 9f;
        return new RectF((getWidth() - width) / 2, (getHeight() - height) / 2,
                (getWidth() + width) / 2, (getHeight() + height) / 2);
    }

    private RectF destination(RectF frame) {
        float scale = frame.width() / cropWidth();
        return new RectF(frame.centerX() - centreX * source.getWidth() * scale,
                frame.centerY() - centreY * source.getHeight() * scale,
                frame.centerX() + (1 - centreX) * source.getWidth() * scale,
                frame.centerY() + (1 - centreY) * source.getHeight() * scale);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        RectF frame = frame();
        canvas.save();
        canvas.clipRect(frame);
        canvas.drawBitmap(source, null, destination(frame), paint);
        canvas.restore();
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        canvas.drawRect(frame, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastX = event.getX(); lastY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float scale = frame().width() / cropWidth();
            if (scale > 0) {
                centreX -= (event.getX() - lastX) / scale / source.getWidth();
                centreY -= (event.getY() - lastY) / scale / source.getHeight();
                clamp(); invalidate();
            }
            lastX = event.getX(); lastY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) { performClick(); return true; }
        return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    Bitmap crop() {
        return ThemeEngine.cropCover(source, centreX, centreY, zoom);
    }
}
