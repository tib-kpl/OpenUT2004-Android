package fr.tibkpl.openut2004;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Xvfb framebuffer captures do not include the X server's mouse cursor. */
final class MenuCursorView extends View {
    private final InputBridge input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    MenuCursorView(Context context, InputBridge input) {
        super(context);
        this.input = input;
        setFocusable(false);
        setClickable(false);
        float scale = getResources().getDisplayMetrics().density;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 20 * scale);
        arrow.lineTo(5 * scale, 15 * scale);
        arrow.lineTo(9 * scale, 23 * scale);
        arrow.lineTo(13 * scale, 21 * scale);
        arrow.lineTo(9 * scale, 13 * scale);
        arrow.lineTo(16 * scale, 13 * scale);
        arrow.close();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (input.isMenuMode()) {
            canvas.save();
            canvas.translate(input.pointerXFraction() * getWidth(), input.pointerYFraction() * getHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xffffffff);
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
            paint.setColor(0xff101010);
            canvas.drawPath(arrow, paint);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }
}
