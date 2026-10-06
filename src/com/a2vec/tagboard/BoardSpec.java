package com.a2vec.tagboard;

/**
 * 标定板的全部几何常量与点阵，纯 Java（不依赖 Android），
 * 供 TagBoardView 绘制，也供 tools/RenderMain 离线渲染验证 —— 保证两边同一套口径。
 *
 * 这份参数是用 pupil_apriltags 对着 biaoding/out/tagboard_3x4_checker.png
 * 兼容布局反复核对：12 个 tag 全部检出、编号一致、hamming 全 0；另有 6-tag 紧凑布局，
 * 检出中心与 JSON 里 centers_px 的偏差只有 +0.35 / +0.38 px。
 */
public final class BoardSpec {

    private BoardSpec() {}

    // ---- 板子在"基线像素"下的几何（1920 x 1080 屏幕宽度即基线宽度）----
    // 板 bitmap 左右各留的白边。**不能小**：手机屏幕两侧是曲面（X100 Pro 实测边缘
    // 明显外折），tag 落在弯曲区会破坏"整块板共面"这个前提，联合求解 RMS 从 ~1px
    // 恶化到 25px。48 基线像素在 70.7mm 屏上约 1.7mm，足够把最外列 tag 推进平面区。
    public static final int BASE_MARGIN_X = 48;
    public static final int BASE_CELL = 236;      // 棋盘格边长
    public static final int BASE_TAG = 165;       // tag 黑框外沿边长 = 165/236 格 = 0.6992
    private static String activeLayout = "classic-12";
    public static final int CLASSIC_COLS = 8, CLASSIC_ROWS = 3, CLASSIC_TAG_COLS = 4;
    public static final int COMPACT_COLS = 6, COMPACT_ROWS = 2, COMPACT_TAG_COLS = 3;
    public static void selectLayout(String name) {
        if ("compact-6".equals(name) || "classic-12".equals(name)) activeLayout = name;
    }
    public static String layoutName() { return activeLayout; }
    public static int boardCols() { return "compact-6".equals(activeLayout) ? COMPACT_COLS : CLASSIC_COLS; }
    public static int boardRows() { return "compact-6".equals(activeLayout) ? COMPACT_ROWS : CLASSIC_ROWS; }
    public static int tagCols() { return "compact-6".equals(activeLayout) ? COMPACT_TAG_COLS : CLASSIC_TAG_COLS; }
    public static int originMarginX() { return BASE_MARGIN_X; }
    public static int tagCount() { return boardRows() * tagCols(); }

    // tag 外沿在棋盘格里的内缩量 = (格宽 - tag宽)/2，X/Y 相同 —— tag 在白格里严格居中。
    // 参考 PNG 的 tag 在 X 方向右偏了 16 基线像素（屏上 1.28 mm），那个偏心没有物理理由，
    // 本 app 改成居中；检出率不受影响（黑框外的白 quiet zone 仍有一整格）。
    public static final float TAG_INSET_X = (BASE_CELL - BASE_TAG) / 2f;   // 35.5
    public static final float TAG_INSET_Y = (BASE_CELL - BASE_TAG) / 2f;   // 35.5

    /**
     * "板内容"坐标系：把 bitmap 的左右两侧留白也包进来，原点 = bitmap 左上角，
     * 宽度按当前布局与两侧留白计算。这块区域就是要铺满屏宽的部分。
     */
    // Both layouts include the complete bitmap and its margins when fitting the viewport.
    public static int contentW() { return boardWidthPx(); }
    public static int contentH() { return boardRows() * BASE_CELL; }
    public static final int CONTENT_X0 = BASE_MARGIN_X;   // 第一个棋盘格在 bitmap 里的 x

    /**
     * 第 r 行第 i 个 tag 的**黑框外沿左上角**在板内容坐标系里的位置（基线像素）。
     * 就是 BoardSpec.tagOrigin，这里再显式给一个内容坐标系版本，避免和视口偏移混淆。
     */
    public static float[] tagOriginInContent(int r, int i) {
        return tagOrigin(r, i);   // 原点一致：都是 bitmap 左上角
    }

    /** 第 r 行第 i 个 tag 的**中心**在板内容坐标系里的位置（基线像素）。 */
    public static float[] tagCenterInContent(int r, int i) {
        float[] o = tagOrigin(r, i);
        return new float[]{o[0] + BASE_TAG / 2f, o[1] + BASE_TAG / 2f};
    }

    /** tag36h11 的 6x6 数据位；1 = 黑。tag 内还各有一圈白边（borderBits = 1）。 */
    public static final int TAG_BITS = 6;
    public static final int TAG_BORDER_BITS = 1;

    public static final String[] TAG_PATTERN = {
            /* tag 1 */ "011011010010111001110000000101100100",
            /* tag 2 */ "100011110111011011111110101101000100",
            /* tag 3 */ "111001101100011010000110000111011000",
            /* tag 4 */ "101110111110101011000010110000101000",
            /* tag 5 */ "110010100011001010100100011100110000",
            /* tag 6 */ "010111101111010001011010100101011111",
            /* tag 7 */ "110101000111100010110101100111110111",
            /* tag 8 */ "010010101000000001000111001010111011",
            /* tag 9 */ "101100011101111101001010010110011011",
            /* tag10 */ "100111100010011101101000000011010011",
            /* tag11 */ "010101001100101110010110000000000011",
            /* tag12 */ "101001101011001101011011101000111101",
    };

    public static final int TAG_COUNT = TAG_PATTERN.length;

    /** 板 bitmap 尺寸（含两侧留白）。 */
    public static int boardWidthPx() {
        return BASE_MARGIN_X * 2 + boardCols() * BASE_CELL;
    }

    public static int boardHeightPx() {
        return contentH();
    }

    /**
     * 第 r 行第 i 个 tag 在板 bitmap 里的左上角（单位：基线像素）。
     * 同一个 tag 的局部坐标是它的黑框外沿左上角。
     */
    public static float[] tagOrigin(int r, int i) {
        int col = 2 * i + (r & 1);
        return new float[]{originMarginX() + col * BASE_CELL + TAG_INSET_X, r * BASE_CELL + TAG_INSET_Y};
    }

    public static int tagId(int r, int i) {
        return r * tagCols() + i + 1;
    }

    /** tag 物理边长（米）= 屏宽(m) / 棋盘列数 * 165/236。 */
    public static double tagSizeMeters(double screenWidthMm) {
        return screenWidthMm * BASE_TAG / boardWidthPx() / 1000.0;
    }
}
