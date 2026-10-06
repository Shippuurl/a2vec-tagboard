import com.a2vec.tagboard.BoardSpec;
import java.io.FileOutputStream;
import java.util.Arrays;

/**
 * 离线渲染标定板，用的就是 app 的 BoardSpec 常量。
 * 绘制循环与验证脚本逐行对应，改动后请跑 verify_render.py。
 */
public class RenderMain {
    static int W, H;
    static byte[] px;

    static final int MX = BoardSpec.BASE_MARGIN_X;
    static final int CELL = BoardSpec.BASE_CELL;
    static final int TAG = BoardSpec.BASE_TAG;
    static final int BB = BoardSpec.TAG_BORDER_BITS;
    static final int BITS = BoardSpec.TAG_BITS;
    static final float IX = BoardSpec.TAG_INSET_X;
    static final float IY = BoardSpec.TAG_INSET_Y;

    static void rect(float x0, float y0, float x1, float y1, int v) {
        int l = Math.round(x0), t = Math.round(y0), r = Math.round(x1), b = Math.round(y1);
        for (int y = Math.max(t, 0); y < Math.min(b, H); y++)
            for (int x = Math.max(l, 0); x < Math.min(r, W); x++) px[y * W + x] = (byte) v;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) BoardSpec.selectLayout(args[1]);
        final int nrow = BoardSpec.boardRows(), ncol = BoardSpec.tagCols();
        W = BoardSpec.boardWidthPx();
        H = nrow * CELL;
        px = new byte[W * H];
        Arrays.fill(px, (byte) 255);

        // 棋盘：(行 + 列) 为奇 -> 实心黑方块
        for (int r = 0; r < nrow; r++)
            for (int c = 0; c < BoardSpec.boardCols(); c++)
                if (((r + c) & 1) == 1) {
                    float left = BoardSpec.originMarginX() + c * CELL;
                    rect(left, r * CELL, left + CELL, (r + 1) * CELL, 0);
                }

        final float u = TAG / (float) (BITS + 2 * BB);
        for (int r = 0; r < nrow; r++) {
            for (int i = 0; i < ncol; i++) {
                int c = 2 * i + (r & 1);
                float x0 = BoardSpec.originMarginX() + c * CELL + IX, y0 = r * CELL + IY;
                rect(x0, y0, x0 + TAG, y0 + TAG, 255);              // tag 白底
                if (BB > 0) {                                        // 一圈黑框
                    rect(x0, y0, x0 + u, y0 + TAG, 0);
                    rect(x0 + TAG - u, y0, x0 + TAG, y0 + TAG, 0);
                    rect(x0, y0, x0 + TAG, y0 + u, 0);
                    rect(x0, y0 + TAG - u, x0 + TAG, y0 + TAG, 0);
                }
                String p = BoardSpec.TAG_PATTERN[BoardSpec.tagId(r, i) - 1];
                for (int rr = 0; rr < BITS; rr++)
                    for (int cc = 0; cc < BITS; cc++)
                        if (p.charAt(rr * BITS + cc) == '1')
                            rect(x0 + (cc + BB) * u, y0 + (rr + BB) * u,
                                 x0 + (cc + BB + 1) * u, y0 + (rr + BB + 1) * u, 0);
            }
        }
        try (FileOutputStream o = new FileOutputStream(args[0])) {
            o.write(("P5\n" + W + " " + H + "\n255\n").getBytes("US-ASCII"));
            o.write(px);
        }
        System.out.println("wrote " + args[0] + "  " + W + "x" + H);
    }
}
