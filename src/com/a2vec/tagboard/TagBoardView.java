package com.a2vec.tagboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

/** Selectable staggered AprilTag board. Geometry and IDs come from BoardSpec.
 * Physical dimensions use the same final viewport scale as rendering.
 */
public class TagBoardView extends View {

    // 全部几何常量与点阵都在 BoardSpec 里（纯 Java，可被离线验证工具复用）
    public static final int BASE_MARGIN_X = BoardSpec.BASE_MARGIN_X;
    public static final int BASE_CELL = BoardSpec.BASE_CELL;
    public static final int BASE_TAG = BoardSpec.BASE_TAG;
    private int BOARD_COLS = BoardSpec.boardCols();
    private int BOARD_ROWS = BoardSpec.boardRows();
    private int N_TAG_COLS = BoardSpec.tagCols();
    public static final float TAG_INSET_X = BoardSpec.TAG_INSET_X;
    public static final float TAG_INSET_Y = BoardSpec.TAG_INSET_Y;

    private static final int TAG_BITS = BoardSpec.TAG_BITS;
    private static final int TAG_BORDER_BITS = BoardSpec.TAG_BORDER_BITS;
    private static final String[] TAG_PATTERN = BoardSpec.TAG_PATTERN;

    private static final int C_BG = 0xFF000000;
    private static final int C_BOARD = 0xFFFFFFFF;
    private static final int C_TAG = 0xFF000000;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_DIM = 0xFF9E9E9E;
    private static final int C_RULER = 0xFFFFC107;
    private static final int C_ACCENT = 0xFF4FC3F7;
    private static final int C_WARN = 0xFFFF7043;

    /** 边缘刻度带宽度（dp）。板子四周留这么宽，用来画毫米刻度。 */
    private static final float EDGE_PAD_DP = 26f;
    private float EDGE_PAD = 26f;

    private final float density;
    private final float xdpi, ydpi, scaledDensity;
    private final int densityDpi;
    private final int pxNaturalW, pxNaturalH;   // 自然方向（窄边 = xdpi 对应边）
    private final float screenWmm, screenHmm;
    private final float pxPerMm;          // = xdpi / 25.4，边缘刻度的唯一依据

    private ScaleGestureDetector pinch;
    private float gridDensity = 4f;
    private boolean touching;
    /** 本次手势是否已经「不算长按」：落过第二根手指，或单指拖动超过 touch slop。 */
    private boolean gestureMoved;
    private final int touchSlop;
    private float downX, downY;
    private Runnable onBoardChanged;
    private final Runnable commit = () -> { if (!touching && onBoardChanged != null) onBoardChanged.run(); };
    public void setOnBoardChanged(Runnable callback) { onBoardChanged = callback; }
    private void scheduleCommit() { removeCallbacks(commit); postDelayed(commit, 1000); }

    private float boardScale = 1f;
    private float boardLeft, boardTop, boardW, boardH;
    private boolean boardFitsWidth = true;

    private boolean serverUp = false;
    private String serverStatus = "启动中…";
    private String poseText = "";

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mono = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TagBoardView(Context context, DisplayMetrics dm) {
        super(context);
        this.density = dm.density;
        this.EDGE_PAD = EDGE_PAD_DP * dm.density;
        this.xdpi = dm.xdpi;
        this.ydpi = dm.ydpi;
        this.densityDpi = dm.densityDpi;
        this.scaledDensity = dm.scaledDensity;
        this.pxNaturalW = dm.widthPixels;
        this.pxNaturalH = dm.heightPixels;

        float wmm = pxNaturalW / xdpi * 25.4f;
        float hmm = pxNaturalH / ydpi * 25.4f;
        // 兜底：个别 ROM 把 xdpi/ydpi 直接填成 densityDpi
        if (!(wmm > 20f && wmm < 1000f)) wmm = pxNaturalW / (float) densityDpi * 25.4f;
        if (!(hmm > 20f && hmm < 1000f)) hmm = pxNaturalH / (float) densityDpi * 25.4f;
        this.screenWmm = wmm;
        this.screenHmm = hmm;
        this.pxPerMm = (float) (xdpi / 25.4);

        text.setColor(C_TEXT);
        mono.setColor(C_TEXT);
        mono.setTypeface(Typeface.MONOSPACE);

        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        gridDensity = BoardSpec.tagCols();
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                gridDensity = Math.max(1f, Math.min(20f, gridDensity / detector.getScaleFactor()));
                int n = Math.round(gridDensity);
                if (BoardSpec.tagCols() != n || BoardSpec.boardRows() != n) {
                    BoardSpec.selectGrid(n, n);
                    onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
                    postInvalidateOnAnimation();
                }
                return true;
            }
        });
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        removeCallbacks(commit);
        final int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                gestureMoved = false;
                downX = event.getX();
                downY = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下 = 用户要缩放/平移，长按立刻作废。
                // View 自带的长按只认「单指超出 slop」，完全不看手指数量，
                // 双指按住不动 500ms 就会弹布局对话框 —— 这就是那个冲突的来源。
                gestureMoved = true;
                cancelLongPress();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!gestureMoved) {
                    final float dx = event.getX() - downX;
                    final float dy = event.getY() - downY;
                    if (dx * dx + dy * dy > (float) touchSlop * touchSlop) {
                        gestureMoved = true;
                        cancelLongPress();
                    }
                }
                break;
            default:
                break;
        }

        pinch.onTouchEvent(event);

        touching = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
        if (!touching) scheduleCommit();

        super.onTouchEvent(event);
        return true;
    }
    @Override protected void onDetachedFromWindow() { removeCallbacks(commit); super.onDetachedFromWindow(); }

    private float dp(float v) {
        return v * density;
    }

    private static Paint solid(int color) {
        Paint p = new Paint();
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    /**
     * 在**设备像素空间**直接画板面。
     *
     * 这里刻意不再「先把板画成一张基线位图，再整体缩放贴到屏幕」：标定靶每个
     * module 的边界都必须落在整像素上，任何中间缩放都会让模块宽度忽宽忽窄。
     * 之前把每个 tag 光栅化成 8×8 像素再放大 20 倍，模块宽度就全乱了，
     * 这就是 tag 看起来「糊成一团」的原因。
     * 现在所有坐标都在设备像素里算，边界统一 round()，模块宽度最多差 1px 且均匀分布。
     */
    private void drawBoard(Canvas c, float left, float top, float scale) {
        BOARD_COLS = BoardSpec.boardCols(); BOARD_ROWS = BoardSpec.boardRows(); N_TAG_COLS = BoardSpec.tagCols();

        final Paint white = solid(C_BOARD);
        final Paint black = solid(C_TAG);
        // 靶面边缘要硬：抗锯齿会在黑白交界留一圈灰，阈值化时反而更难判黑白
        white.setAntiAlias(false);
        black.setAntiAlias(false);

        // 底色（含左右白边）
        c.drawRect(left, top,
                left + BoardSpec.contentW() * scale,
                top + BoardSpec.contentH() * scale, white);

        for (int r = 0; r < BOARD_ROWS; ++r) {
            for (int col = 0; col < BOARD_COLS; ++col) {
                if (((r + col) & 1) == 1) {
                    final float x0 = left + (BoardSpec.originMarginX() + col * BASE_CELL) * scale;
                    final float x1 = left + (BoardSpec.originMarginX() + (col + 1) * BASE_CELL) * scale;
                    final float y0 = top + r * BASE_CELL * scale;
                    final float y1 = top + (r + 1) * BASE_CELL * scale;
                    c.drawRect(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), black);
                }
            }
            for (int i = 0; i < N_TAG_COLS; ++i) {
                final float x = left + (BoardSpec.originMarginX() + (2 * i + (r & 1)) * BASE_CELL + TAG_INSET_X) * scale;
                final float y = top + (r * BASE_CELL + TAG_INSET_Y) * scale;
                drawTag(c, TAG_PATTERN[r * N_TAG_COLS + i], x, y, BASE_TAG * scale, white, black);
            }
        }
    }

    /**
     * 在 size×size 的设备像素方块里画一个 tag36h11（8×8 module，含 1 module 黑框）。
     * 先按 round() 求出 9 条 module 边界存进数组，再照数组画矩形——这样同一行里的
     * 模块宽度只差 0~1px 且不会累积漂移，整块正好占满 size。
     */
    private void drawTag(Canvas c, String pat, float x0, float y0, float size, Paint white, Paint black) {
        final int n = TAG_BITS + 2 * TAG_BORDER_BITS;   // 8
        final float u = size / n;

        // 先铺白底：tag 可能压在棋盘格的黑格上
        c.drawRect(Math.round(x0), Math.round(y0),
                Math.round(x0 + size), Math.round(y0 + size), white);

        final int[] ex = new int[n + 1];
        final int[] ey = new int[n + 1];
        for (int k = 0; k <= n; k++) {
            ex[k] = Math.round(x0 + k * u);
            ey[k] = Math.round(y0 + k * u);
        }

        // 一圈黑框
        final int b = TAG_BORDER_BITS;
        c.drawRect(ex[0], ey[0], ex[b], ey[n], black);          // 左
        c.drawRect(ex[n - b], ey[0], ex[n], ey[n], black);      // 右
        c.drawRect(ex[0], ey[0], ex[n], ey[b], black);          // 上
        c.drawRect(ex[0], ey[n - b], ex[n], ey[n], black);      // 下

        // 6×6 数据格
        for (int r = 0; r < TAG_BITS; r++) {
            for (int col = 0; col < TAG_BITS; col++) {
                if (pat.charAt(r * TAG_BITS + col) != '1') continue;
                c.drawRect(ex[col + b], ey[r + b], ex[col + b + 1], ey[r + b + 1], black);
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Fit the complete board bitmap including its white margins.
        float availW = w - 2 * EDGE_PAD;
        float availH = h - 2 * EDGE_PAD;
        boardScale = Math.min(availW / BoardSpec.contentW(), availH / BoardSpec.contentH());
        boardW = BoardSpec.boardWidthPx() * boardScale;
        boardH = BoardSpec.boardHeightPx() * boardScale;
        boardLeft = (w - BoardSpec.contentW() * boardScale) / 2f;
        boardTop = (h - BoardSpec.contentH() * boardScale) / 2f;
        scheduleCommit();
        boardFitsWidth = BoardSpec.contentW() * boardScale >= availW - 0.5f;
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(C_BG);


        drawBoard(cv, boardLeft, boardTop, boardScale);

        drawEdgeTicks(cv);
        drawCornerLabel(cv);
    }

    /** 毫米刻度：小刻度 1mm、中刻度 5mm、大刻度 10mm 并标数字。全屏可用 xDpi 换算。 */
    private void drawEdgeTicks(Canvas cv) {
        final int w = getWidth(), h = getHeight();
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setStrokeWidth(1f);

        // 左右两侧的竖直刻度，数值 = 距板中心线的毫米数
        for (int side = 0; side < 2; side++) {
            float xEdge = (side == 0) ? 0f : w;
            int dir = (side == 0) ? 1 : -1;
            float cy = boardTop + boardH / 2f;
            int nDown = (int) ((h - cy) / pxPerMm) + 1;
            int nUp = (int) (cy / pxPerMm) + 1;
            for (int k = -nUp; k <= nDown; k++) {
                float y = cy + k * pxPerMm;
                if (y < 1 || y > h - 1) continue;
                int a = Math.abs(k);
                float len = (a % 10 == 0) ? EDGE_PAD : (a % 5 == 0 ? EDGE_PAD * 0.55f : EDGE_PAD * 0.28f);
                t.setColor(a % 10 == 0 ? C_ACCENT : 0xFF606060);
                cv.drawLine(xEdge, y, xEdge + dir * len, y, t);
            }
        }

        // 底部水平刻度，数值 = 距板中心线的毫米数
        float cx = boardLeft + boardW / 2f;
        int nL = (int) (cx / pxPerMm) + 1;
        int nR = (int) ((w - cx) / pxPerMm) + 1;
        for (int k = -nL; k <= nR; k++) {
            float x = cx + k * pxPerMm;
            if (x < 1 || x > w - 1) continue;
            int a = Math.abs(k);
            float len = (a % 10 == 0) ? EDGE_PAD : (a % 5 == 0 ? EDGE_PAD * 0.55f : EDGE_PAD * 0.28f);
            t.setColor(a % 10 == 0 ? C_ACCENT : 0xFF606060);
            cv.drawLine(x, h, x, h - len, t);
        }

        // 参照：0 处加一条贯穿刻度带的中线
        t.setStrokeWidth(2f);
        t.setColor(C_RULER);
        cv.drawLine(0, boardTop + boardH / 2f, EDGE_PAD * 0.5f, boardTop + boardH / 2f, t);
        cv.drawLine(w, boardTop + boardH / 2f, w - EDGE_PAD * 0.5f, boardTop + boardH / 2f, t);
        cv.drawLine(cx, h, cx, h - EDGE_PAD * 0.5f, t);
    }

    /** 左下角小字：只有端口和一行尺度说明，其余信息全部走服务。 */
    private void drawCornerLabel(Canvas cv) {
        float fs = Math.max(dp(9), Math.min(dp(12), getWidth() / 120f));
        mono.setTextSize(fs);
        mono.setTextAlign(Paint.Align.LEFT);

        String line1 = serverStatus;
        String line2 = String.format(java.util.Locale.US,
                "%s  |  tag %.3fmm  |  Pinch: 1x1 - 20x20", BoardSpec.layoutName(), tagMm());

        float x = dp(7);
        float y = getHeight() - dp(7);
        mono.setColor(0xFF000000);
        cv.drawText(line2, x + 1, y + 1, mono);
        cv.drawText(line1, x + 1, y - fs - dp(2) + 1, mono);
        mono.setColor(serverUp ? C_ACCENT : C_WARN);
        cv.drawText(line1, x, y - fs - dp(2), mono);
        mono.setColor(C_DIM);
        cv.drawText(line2, x, y, mono);
    }

    /** 板上 tag 边长（mm）。 */
    public double tagMm() {
        return BASE_TAG * metersPerBasePx() * 1000.0;
    }

    // ---------------- 对外暴露标定参数（给 CalibServer 用） ----------------

    public void setServerStatus(boolean up, String status) {
        this.serverUp = up;
        this.serverStatus = status;
        postInvalidate();
    }

    public void setPoseText(String t) {
        this.poseText = (t == null) ? "" : t;
        postInvalidate();
    }

    /**
     * 板上 1 个"基线像素"对应的物理长度（米）。
     * 基准是屏幕上板子真正的宽度：BoardSpec.boardWidthPx() 个基线像素
     * → boardWidthMmOnScreen() 毫米。**不能**用屏宽当板宽，否则板子没铺满屏宽时
     * tag 尺寸会系统性偏小（X100 Pro 实测板宽只占屏宽 98.1%）。
     */
    public double metersPerBasePx() {
        return boardScale / pxPerMm / 1000.0;
    }

    /** 板子内容在屏幕上实际显示的宽度（mm）。 */
    public double boardWidthMmOnScreen() {
        return BoardSpec.contentW() * boardScale / pxPerMm;
    }

    /** tag 中心在屏幕上的绝对位置（px），用来和相机检出的位置核对。 */
    public double[] tagCenterOnScreen(int r, int i) {
        float[] c = BoardSpec.tagCenterInContent(r, i);
        return new double[]{
                boardLeft + c[0] * boardScale,
                boardTop + c[1] * boardScale};
    }

    /** tag 黑框外沿的物理边长（米）。这是标定唯一必须实测的量。 */
    public double tagSizeMeters() {
        return BASE_TAG * metersPerBasePx();
    }

    /** 供 /tagboard/board.png 导出：设备上真正渲染出来的板 bitmap。 */
    public Bitmap boardBitmap() {
        final int w = Math.max(1, Math.round(BoardSpec.contentW() * boardScale));
        final int h = Math.max(1, Math.round(BoardSpec.contentH() * boardScale));
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        drawBoard(canvas, 0f, 0f, boardScale);
        return result;
    }

    public double screenWidthMm() {
        return screenWmm;
    }

    private static String j(double v, int digits) {
        return String.format(java.util.Locale.US, "%." + digits + "f", v);
    }

    public String toJson(boolean minimal) {
        final double cellMm = BASE_CELL * metersPerBasePx() * 1000.0;
        final int nTag = BOARD_ROWS * N_TAG_COLS;

        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');
        sb.append("\"ok\":true,");
        sb.append("\"device\":\"").append(CalibServer.esc(android.os.Build.MANUFACTURER + " "
                + android.os.Build.MODEL)).append("\",");
        sb.append("\"android\":\"").append(CalibServer.esc(android.os.Build.VERSION.RELEASE)).append("\",");

        sb.append("\"screen\":{");
        sb.append("\"width_px\":").append(pxNaturalW).append(',');
        sb.append("\"height_px\":").append(pxNaturalH).append(',');
        sb.append("\"width_mm\":").append(j(screenWmm, 4)).append(',');
        sb.append("\"height_mm\":").append(j(screenHmm, 4)).append(',');
        sb.append("\"diagonal_in\":").append(j(Math.hypot(screenWmm, screenHmm) / 25.4, 4)).append(',');
        sb.append("\"xdpi\":").append(j(xdpi, 4)).append(',');
        sb.append("\"ydpi\":").append(j(ydpi, 4)).append(',');
        sb.append("\"density_dpi\":").append(densityDpi).append(',');
        sb.append("\"density\":").append(j(density, 6)).append(',');
        sb.append("\"scaled_density\":").append(j(scaledDensity, 6)).append(',');
        sb.append("\"px_per_mm\":").append(j(xdpi / 25.4, 6)).append(',');
        sb.append("\"note\":\"width/height 为自然(竖屏)方向；app 本身锁横屏\"},");

        sb.append("\"board\":{");
        sb.append("\"image_px\":[").append(BoardSpec.boardWidthPx()).append(',')
                .append(BoardSpec.boardHeightPx()).append("],");
        sb.append("\"layout\":\"").append(BoardSpec.layoutName()).append("\",");
        sb.append("\"available_layouts\":[\"classic-12\",\"compact-6\"],");
        sb.append("\"checker_cols\":").append(BOARD_COLS).append(',');
        sb.append("\"checker_rows\":").append(BOARD_ROWS).append(',');
        sb.append("\"tag_cols\":").append(N_TAG_COLS).append(',');
        sb.append("\"margin_px\":").append(BASE_MARGIN_X).append(',');
        sb.append("\"cell_mm\":").append(j(cellMm, 4)).append(',');
        sb.append("\"tag_px\":").append(BASE_TAG).append(',');
        sb.append("\"tag_border_bits\":").append(TAG_BORDER_BITS).append(',');
        sb.append("\"tag_over_cell\":").append(j(BASE_TAG / (double) BASE_CELL, 5)).append(',');
        sb.append("\"width_mm\":").append(j(cellMm * BOARD_COLS, 4)).append(',');
        sb.append("\"height_mm\":").append(j(cellMm * BOARD_ROWS, 4)).append(',');
        sb.append("\"width_on_screen_mm\":").append(j(boardWidthMmOnScreen(), 4)).append(',');
        sb.append("\"scale\":").append(j(boardScale, 6)).append(',');
        sb.append("\"meters_per_base_px\":").append(j(metersPerBasePx(), 10)).append(',');
        sb.append("\"plane\":\"tag 与板共面；板面法线 = 屏幕法线；所有 tag 朝向一致\"}");

        if (!minimal) {
            sb.append(",\"grasp\":{");
            sb.append("\"layout\":\"").append(BoardSpec.layoutName()).append("\",");
            sb.append("\"tag_family\":\"tag36h11\",");
            sb.append("\"tag_size_m\":").append(j(tagSizeMeters(), 8)).append(',');
            sb.append("\"tag_mm\":").append(j(tagMm(), 4)).append(',');
            sb.append("\"tag_ids\":[");
            for (int id = 1; id <= nTag; id++) {
                if (id > 1) sb.append(',');
                sb.append(id);
            }
            sb.append("],");
            sb.append("\"tag_ids_by_row\":[");
            for (int r = 0; r < BOARD_ROWS; r++) {
                if (r > 0) sb.append(',');
                sb.append('[');
                for (int i = 0; i < N_TAG_COLS; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(r * N_TAG_COLS + i + 1);
                }
                sb.append(']');
            }
            sb.append("],");
            // Tag1 中心为原点；导出历史三维格式 [向右, 0, 向上]，含奇数行横向错位。
            // 因为 tag 在格内居中，tag1(行0列0) 的中心就在原点，所以相对偏移就是纯格距。
            sb.append("\"centers_m\":[");
            for (int r = 0; r < BOARD_ROWS; r++) {
                for (int i = 0; i < N_TAG_COLS; i++) {
                    if (r > 0 || i > 0) sb.append(',');
                    sb.append('[').append(j((2 * i + (r & 1)) * cellMm / 1000.0, 8))
                            .append(",0.0,")
                            .append(j(-r * cellMm / 1000.0, 8)).append(']');
                }
            }
            sb.append("],");
            // 同一批 tag 在**屏幕像素**里的位置，用于和相机检出直接核对
            sb.append("\"tag_centers_screen_px\":[");
            for (int r = 0; r < BOARD_ROWS; r++) {
                for (int i = 0; i < N_TAG_COLS; i++) {
                    if (r > 0 || i > 0) sb.append(',');
                    double[] c = tagCenterOnScreen(r, i);
                    sb.append('[').append(j(c[0], 2)).append(',').append(j(c[1], 2)).append(']');
                }
            }
            sb.append("],");
            sb.append("\"headless_ok\":").append(serverUp).append(',');
            sb.append("\"objects\":{\"tag1\":{\"size\":[")
                    .append(j(tagSizeMeters(), 8)).append(',')
                    .append(j(tagSizeMeters(), 8)).append(",0]}}");

            sb.append(",\"server\":{");
            sb.append("\"ip\":\"").append(CalibServer.esc(CalibServer.localIpv4())).append("\",");
            sb.append("\"port\":8899,");
            sb.append("\"pose_echo\":").append(poseText.length() > 0);
            sb.append('}');          // 关 server
            sb.append('}');          // 关 grasp
        }
        sb.append('}');              // 关根对象
        return sb.toString();
    }

    /** 运行中切换可选布局，并让服务立即返回新的几何参数。 */
    public void setLayout(String name) {
        BoardSpec.selectLayout(name);
        gridDensity = BoardSpec.tagCols();
        getContext().getSharedPreferences("board", Context.MODE_PRIVATE).edit().putString("layout", name).apply();
        onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }
}
