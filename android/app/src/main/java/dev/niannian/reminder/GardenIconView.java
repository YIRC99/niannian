package dev.niannian.reminder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small, readable navigation symbols; labels and touch targets belong to the parent. */
public final class GardenIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final String kind;

    public GardenIconView(Context context) {
        this(context, "today", 0xff527a58);
    }

    public GardenIconView(Context context, String kind, int color) {
        super(context);
        this.kind = kind == null ? "today" : kind;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
    }

    public void setColor(int color) {
        if (paint.getColor() == color) return;
        paint.setColor(color);
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(24 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        int save = canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        path.reset();
        switch (kind) {
            case "send":
                path.moveTo(3, 10); path.lineTo(21, 3); path.lineTo(16, 21);
                path.lineTo(11, 14); path.lineTo(3, 10); path.moveTo(11, 14); path.lineTo(21, 3);
                canvas.drawPath(path, paint);
                break;
            case "info":
                canvas.drawCircle(12, 12, 9, paint);
                canvas.drawLine(12, 10, 12, 16, paint);canvas.drawPoint(12, 7, paint);
                break;
            case "photo_error":
                rect.set(2, 3, 19, 20);canvas.drawRoundRect(rect, 2, 2, paint);
                canvas.drawCircle(7, 8, 1.5f, paint);
                path.moveTo(3, 17);path.lineTo(8, 12);path.lineTo(12, 16);path.lineTo(15, 13);
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);paint.setColor(0xfff5f4ef);canvas.drawCircle(18, 18, 5, paint);
                paint.setStyle(Paint.Style.STROKE);paint.setColor(0xff969f94);canvas.drawCircle(18, 18, 4.5f, paint);
                canvas.drawLine(18, 15.5f, 18, 18, paint);canvas.drawPoint(18, 20.5f, paint);
                break;
            case "back":
                path.moveTo(15, 5); path.lineTo(8, 12); path.lineTo(15, 19);
                canvas.drawPath(path, paint);
                break;
            case "chevron_right":
                path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18);
                canvas.drawPath(path, paint);
                break;
            case "chevron_down":
                path.moveTo(6, 9); path.lineTo(12, 15); path.lineTo(18, 9);
                canvas.drawPath(path, paint);
                break;
            case "chevron_up":
                path.moveTo(6, 15); path.lineTo(12, 9); path.lineTo(18, 15);
                canvas.drawPath(path, paint);
                break;
            case "circle_check":
                canvas.drawCircle(12, 12, 8.5f, paint);
                path.moveTo(8, 12); path.lineTo(10.8f, 14.8f); path.lineTo(16.5f, 9);
                canvas.drawPath(path, paint);
                break;
            case "heart":
                path.moveTo(12, 20);
                path.cubicTo(9.5f, 17.8f, 3.5f, 13.3f, 3.5f, 8.5f);
                path.cubicTo(3.5f, 3.8f, 9.6f, 2.8f, 12, 7.3f);
                path.cubicTo(14.4f, 2.8f, 20.5f, 3.8f, 20.5f, 8.5f);
                path.cubicTo(20.5f, 13.3f, 14.5f, 17.8f, 12, 20);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case "cycle":
                path.moveTo(4.4f, 10);
                path.cubicTo(5.4f, 5, 9.8f, 2.7f, 14.2f, 4);
                path.cubicTo(17, 4.8f, 19, 6.8f, 19.6f, 9.5f);
                path.moveTo(19.6f, 4.8f); path.lineTo(19.6f, 9.5f); path.lineTo(14.9f, 9.5f);
                path.moveTo(19.6f, 14);
                path.cubicTo(18.6f, 19, 14.2f, 21.3f, 9.8f, 20);
                path.cubicTo(7, 19.2f, 5, 17.2f, 4.4f, 14.5f);
                path.moveTo(4.4f, 19.2f); path.lineTo(4.4f, 14.5f); path.lineTo(9.1f, 14.5f);
                canvas.drawPath(path, paint);
                break;
            case "search":
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint);
                canvas.drawLine(15.2f, 15.2f, 20.5f, 20.5f, paint);
                break;
            case "refresh":
                path.moveTo(19, 7);
                path.cubicTo(16, 2, 8, 2, 5, 7);
                path.cubicTo(1, 13, 6, 21, 13, 20);
                path.cubicTo(16, 20, 19, 18, 20, 15);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(19, 3); path.lineTo(19, 7); path.lineTo(15, 7);
                canvas.drawPath(path, paint);
                break;
            case "clock":
                canvas.drawCircle(12, 12, 8.5f, paint);
                path.moveTo(12, 6.5f); path.lineTo(12, 12); path.lineTo(16, 14);
                canvas.drawPath(path, paint);
                break;
            case "repeat":
                path.moveTo(5, 12); path.lineTo(5, 10);
                path.quadTo(5, 7, 8, 7); path.lineTo(20, 7);
                path.moveTo(17, 4); path.lineTo(20, 7); path.lineTo(17, 10);
                path.moveTo(19, 12); path.lineTo(19, 14);
                path.quadTo(19, 17, 16, 17); path.lineTo(4, 17);
                path.moveTo(7, 14); path.lineTo(4, 17); path.lineTo(7, 20);
                canvas.drawPath(path, paint);
                break;
            case "garden":
                path.moveTo(12, 21); path.lineTo(12, 12);
                path.cubicTo(6, 12, 3.5f, 8.5f, 4, 4);
                path.cubicTo(9, 3.6f, 12, 7, 12, 12);
                path.cubicTo(12, 6.9f, 15.5f, 4.8f, 20, 5);
                path.cubicTo(20.1f, 10, 16.8f, 13.5f, 12, 14);
                canvas.drawPath(path, paint);
                canvas.drawLine(8, 21, 16, 21, paint);
                break;
            case "history":
                rect.set(4, 5, 20, 21);
                canvas.drawRoundRect(rect, 3, 3, paint);
                canvas.drawLine(8, 3, 8, 7, paint);
                canvas.drawLine(16, 3, 16, 7, paint);
                canvas.drawLine(4, 10, 20, 10, paint);
                canvas.drawLine(8, 14, 10, 14, paint);
                canvas.drawLine(14, 14, 16, 14, paint);
                canvas.drawLine(8, 17, 10, 17, paint);
                break;
            case "settings":
                canvas.drawCircle(12, 7, 3.5f, paint);
                path.moveTo(4.5f, 21); path.lineTo(4.5f, 19);
                path.cubicTo(4.5f, 15.3f, 7.7f, 13, 12, 13);
                path.cubicTo(16.3f, 13, 19.5f, 15.3f, 19.5f, 19);
                path.lineTo(19.5f, 21);
                canvas.drawPath(path, paint);
                break;
            case "plus":
                canvas.drawLine(12, 5, 12, 19, paint);
                canvas.drawLine(5, 12, 19, 12, paint);
                break;
            case "check":
                path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(20, 7);
                canvas.drawPath(path, paint);
                break;
            default:
                canvas.drawCircle(12, 12, 4.2f, paint);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * i / 4;
                    canvas.drawLine(12 + (float) Math.cos(angle) * 7.5f,
                            12 + (float) Math.sin(angle) * 7.5f,
                            12 + (float) Math.cos(angle) * 9.5f,
                            12 + (float) Math.sin(angle) * 9.5f, paint);
                }
                break;
        }
        canvas.restoreToCount(save);
    }
}
