package com.a2vec.tagboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * 启动时的 A2Vec 三轴标动画，点一下任意位置跳过。
 *
 * 参数与缓动逐项对齐
 * 可复用资源/01-设计资源/Logo/A2Vec-动态Logo/logo-cycle/startup-logo.ts 的
 * sampleStartupLogo()：1.4 s 运动（收拢 → 旋转两圈 → 展开归位）+ 1 s 停留。
 * 几何与静态图标同源，改这里之前先看那份 .ts。
 */
public class LogoIntroView extends View {

    public interface OnDone { void onDone(); }

    // ---- 1280 视框几何（与 startup-logo.ts 完全一致）----
    private static final float CX = 1940f / 3f;     // 646.6667
    private static final float CY = 2036f / 3f;     // 678.6667
    private static final int[][] TRIANGLE = {{668, 334}, {326, 851}, {946, 851}};
    private static final int[][] ENDS = {{678, 145}, {155, 1020}, {1095, 1000}};
    private static final float CORNER_RADIUS = 34f;
    private static final float[] ARM_STRETCH = {28, 34, 31};
    private static final float[] ARM_PHASE = {0f, 1.8f, 3.7f};
    private static final float[] ARM_SPEED = {3.6f, 3.25f, 3.45f};

    private static final float CONTRACT_RATIO = 0.32f;
    private static final float HIDDEN_RATIO = 0.09f;
    private static final float EXPAND_START = CONTRACT_RATIO + HIDDEN_RATIO;

    private static final long MOTION_MS = 1400;
    private static final long HOLD_MS = 1000;
    private static final long TOTAL_MS = MOTION_MS + HOLD_MS;

    private static final int BG = 0xFF080809;

    private final float[] armAngle = new float[3];
    private final float[] armLength = new float[3];
    private final float[] endX = new float[3];
    private final float[] endY = new float[3];

    private final Path triangle = buildTriangle();
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint armPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long startedAt = SystemClock.uptimeMillis();
    private boolean done;

    private OnDone onDone;
    private final Runnable finishTask = this::finish;

    public LogoIntroView(Context context) {
        super(context);
        setBackgroundColor(BG);
        for (int i = 0; i < 3; i++) {
            float dx = ENDS[i][0] - CX;
            float dy = ENDS[i][1] - CY;
            armAngle[i] = (float) Math.atan2(dy, dx);
            armLength[i] = (float) Math.hypot(dx, dy);
        }
        facePaint.setStyle(Paint.Style.FILL);
        facePaint.setShader(new LinearGradient(TRIANGLE[1][0], TRIANGLE[0][1], TRIANGLE[2][0], TRIANGLE[1][1],
                0xFF606063, 0xFF969699, Shader.TileMode.CLAMP));
        armPaint.setStyle(Paint.Style.STROKE);
        armPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setStyle(Paint.Style.FILL);
        hintPaint.setColor(0xFF7C7C84);
        hintPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnDone(OnDone listener) { onDone = listener; }

    public void start() {
        startedAt = SystemClock.uptimeMillis();
        done = false;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // 别在触摸分发途中把 view 摘掉，post 一帧更稳
            post(finishTask);
            return true;
        }
        return true;
    }

    private void finish() {
        if (done) return;
        done = true;
        removeCallbacks(finishTask);
        if (onDone != null) onDone.onDone();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(finishTask);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas cv) {
        final long elapsed = SystemClock.uptimeMillis() - startedAt;
        final float seconds = Math.min(elapsed, MOTION_MS) / 1000f;
        final float p = clamp01(seconds / (MOTION_MS / 1000f));

        final float radius = p < CONTRACT_RATIO
                ? 1f - smootherstep(p / CONTRACT_RATIO)
                : p < EXPAND_START ? 0f
                : smootherstep((p - EXPAND_START) / (1f - EXPAND_START));
        final float turns = p < CONTRACT_RATIO
                ? rotationEase(p / CONTRACT_RATIO)
                : 1f + rotationEase((p - EXPAND_START) / (1f - EXPAND_START));

        final float widthScale = (float) Math.pow(radius, 0.9);
        final int brightness = Math.round(144 + 106 * smoothstep(radius));
        final float visibility = smoothstep(clamp01(radius / 0.14f));

        final float armWidth = 25f * widthScale;
        final float endRadius = 39f * (float) Math.pow(radius, 1.05);
        final float hubRadius = 18f * widthScale;
        final float envelope = 16f * radius * radius * (1 - radius) * (1 - radius);

        for (int i = 0; i < 3; i++) {
            final float stretch = (float) Math.sin(seconds * ARM_SPEED[i] + ARM_PHASE[i])
                    * ARM_STRETCH[i] * envelope;
            final float len = (armLength[i] + stretch) * radius;
            final float a = armAngle[i] + turns * (float) (Math.PI * 2);
            endX[i] = CX + (float) Math.cos(a) * len;
            endY[i] = CY + (float) Math.sin(a) * len;
        }

        cv.drawColor(BG);

        // 1280 视框 → 屏幕：取短边的 42% 作为最大尺寸，居中
        final float box = Math.min(getWidth(), getHeight()) * 0.42f;
        final float k = box / 1280f;
        cv.save();
        cv.translate((getWidth() - 1280f * k) / 2f, (getHeight() - 1280f * k) / 2f);
        cv.scale(k, k);

        final int a = (int) (255 * visibility);
        final int gray = (brightness << 16) | (brightness << 8) | brightness | (a << 24);

        // 三角面：随 radius 缩放、随 turns 旋转（杆的端点已自带这两个量，不要再套一次）
        facePaint.setAlpha((int) (0.52f * a));
        cv.save();
        cv.rotate(turns * 360f, CX, CY);
        cv.scale(radius, radius, CX, CY);
        cv.drawPath(triangle, facePaint);
        cv.restore();

        // 三条杆 + 端点 + 轴心
        armPaint.setColor(gray);
        armPaint.setStrokeWidth(armWidth);
        dotPaint.setColor(gray);
        for (int i = 0; i < 3; i++) {
            cv.drawLine(CX, CY, endX[i], endY[i], armPaint);
            cv.drawCircle(endX[i], endY[i], endRadius, dotPaint);
        }
        cv.drawCircle(CX, CY, hubRadius, dotPaint);

        cv.restore();

        // 跳过提示（淡入，不抢戏）
        if (p > 0.12f) {
            float fs = Math.min(getWidth(), getHeight()) * 0.028f;
            hintPaint.setTextSize(fs);
            hintPaint.setAlpha((int) (0.55f * 255 * smoothstep(clamp01((p - 0.12f) / 0.2f))));
            cv.drawText("点击跳过", getWidth() / 2f, getHeight() - fs * 2.2f, hintPaint);
        }

        if (elapsed < TOTAL_MS) {
            postInvalidateOnAnimation();
        } else {
            post(finishTask);
        }
    }

    // ---------------- 缓动（逐项照抄 startup-logo.ts） ----------------

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static float smoothstep(float v) {
        final float t = clamp01(v);
        return t * t * (3f - 2f * t);
    }

    private static float smootherstep(float v) {
        final float t = clamp01(v);
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float rotationEase(float v) {
        final float t = clamp01(v);
        final float start = (float) Math.pow(t, 1.1);
        final float end = (float) Math.pow(1f - t, 1.1);
        return smootherstep(start / (start + end));
    }

    /** 圆角三角面：距离取 min(34/tan(θ/2), 邻边×0.22)，与原 .ts 一致。 */
    private static Path buildTriangle() {
        final float[] sx = new float[3], sy = new float[3], ex = new float[3], ey = new float[3];
        for (int i = 0; i < 3; i++) {
            final int[] p = TRIANGLE[i];
            final int[] prev = TRIANGLE[(i + 2) % 3];
            final int[] next = TRIANGLE[(i + 1) % 3];
            final float pl = (float) Math.hypot(prev[0] - p[0], prev[1] - p[1]);
            final float nl = (float) Math.hypot(next[0] - p[0], next[1] - p[1]);
            final float ax = (prev[0] - p[0]) / pl, ay = (prev[1] - p[1]) / pl;
            final float bx = (next[0] - p[0]) / nl, by = (next[1] - p[1]) / nl;
            final float ang = (float) Math.acos(Math.max(-1f, Math.min(1f, ax * bx + ay * by)));
            final float d = Math.min(CORNER_RADIUS / (float) Math.tan(ang / 2f),
                    Math.min(pl * 0.22f, nl * 0.22f));
            sx[i] = p[0] + ax * d; sy[i] = p[1] + ay * d;
            ex[i] = p[0] + bx * d; ey[i] = p[1] + by * d;
        }
        final Path path = new Path();
        path.moveTo(sx[0], sy[0]);
        for (int i = 0; i < 3; i++) {
            path.quadTo(TRIANGLE[i][0], TRIANGLE[i][1], ex[i], ey[i]);
            path.lineTo(sx[(i + 1) % 3], sy[(i + 1) % 3]);
        }
        path.close();
        return path;
    }
}
