package com.a2vec.tagboard;

import android.app.Activity;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * 全屏标定板。没有任何系统 UI、没有浏览器地址栏。
 * 屏幕常亮；点一下屏幕可重新进入沉浸式（万一被系统手势唤出导航条）。
 */
public class MainActivity extends Activity implements View.OnClickListener {

    private TagBoardView view;
    private CalibServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 真实物理像素 + 真实 DPI（受状态栏/导航栏影响，所以必须用 real metrics）
        DisplayMetrics dm = new DisplayMetrics();
        Display d = getWindowManager().getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            d.getRealMetrics(dm);
        } else {
            d.getMetrics(dm);
        }

        // 有些 ROM 在 real metrics 里也会把 width/height 报告成"可用"尺寸，用 size 交叉核对
        Point p = new Point();
        d.getRealSize(p);
        if (p.x > dm.widthPixels || p.y > dm.heightPixels) {
            dm.widthPixels = Math.max(dm.widthPixels, p.x);
            dm.heightPixels = Math.max(dm.heightPixels, p.y);
        }

        // DisplayMetrics 的 xdpi/ydpi 是相对"自然方向"定义的：xdpi 对应窄边。
        // 本 app 锁定横屏，所以拿到的是 width=2800, height=1260（已旋转），
        // 必须还原成自然方向，否则 宽度/xdpi 会算错一倍以上。
        if (dm.widthPixels > dm.heightPixels) {
            int t = dm.widthPixels;
            dm.widthPixels = dm.heightPixels;
            dm.heightPixels = t;
        }

        String requestedLayout = getIntent().getStringExtra("layout");
        BoardSpec.selectLayout(requestedLayout != null ? requestedLayout :
                getSharedPreferences("board", MODE_PRIVATE).getString("layout", "classic-12"));
        view = new TagBoardView(this, dm);
        view.setBackgroundColor(0xFF000000);
        view.setOnClickListener(this);
        view.setOnLongClickListener(v -> {
            String[] layouts = {"classic-12", "compact-6"};
            new android.app.AlertDialog.Builder(this)
                .setTitle("选择标定板布局")
                .setSingleChoiceItems(new String[]{"12 个 Tag · 3 行 × 4 列", "6 个大 Tag · 2 行 × 3 列"},
                    "compact-6".equals(BoardSpec.layoutName()) ? 1 : 0,
                    (dialog, which) -> { view.setLayout(layouts[which]); dialog.dismiss(); goImmersive(); })
                .setNegativeButton("取消", (dialog, which) -> goImmersive()).show();
            return true;
        });
        setContentView(view);

        server = new CalibServer(CalibServer.DEFAULT_PORT, view);
        server.start();
        view.setServerStatus(true, CalibServer.localIpv4() + ":" + server.getPort()
                + "   GET /tagboard.json");

        goImmersive();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        goImmersive();
    }

    private void goImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }
}
