#!/usr/bin/env python3
"""交叉验证手机标定板的物理尺寸。

原理：腕部相机拍到板子时，已知
      tag_px_in_image = fx * tag_size_m / Z
用深度图拿到 Z，就能反推 tag_size_m；再结合 app 报的几何比例反推屏幕宽度，
和 app 用 xDpi 算出的屏幕宽度比对。两条独立链路一致 => 尺寸可信。

用法： adb forward tcp:8899 tcp:8899
       A2VEC_CAMERAS=/path/to/cameras.json python3 check_physical.py

相机内参不属于本仓库：脚本只从 $A2VEC_CAMERAS（或本目录下的 config/cameras.json）读，
需要的字段是 `cameras.wrist.color_intrinsics`，取 `[0][0]` 作为 fx。
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
WRIST_CFG = os.environ.get("A2VEC_CAMERAS") or os.path.join(HERE, "config", "cameras.json")

BASE_CELL = 236
BASE_TAG = 165


def main():
    import cv2
    import pupil_apriltags as pat

    # 1) 从手机服务取参数
    import urllib.request
    try:
        raw = urllib.request.urlopen("http://127.0.0.1:8899/tagboard.json", timeout=5).read()
    except Exception as e:
        print(f"取不到手机参数（先 adb forward tcp:8899 tcp:8899）: {e}")
        return 1
    tb = json.loads(raw.decode("utf-8"))
    s, b, g = tb["screen"], tb["board"], tb["grasp"]
    print(f"手机      {tb['device']}  Android {tb['android']}")
    print(f"屏幕       {s['width_px']} x {s['height_px']} px")
    print(f"用 xDpi 算  宽度 {s['width_mm']:.4f} mm   高度 {s['height_mm']:.4f} mm   "
          f"对角线 {s['diagonal_in']:.4f} in")
    print(f"板子       屏上宽 {b['width_on_screen_mm']:.4f} mm   棋盘格 {b['cell_mm']:.4f} mm")
    print(f"tag 边长    {g['tag_mm']:.4f} mm     tag_size_m = {g['tag_size_m']:.8f}")

    # 2) 用相机实测的 tag 像素反推
    if not os.path.exists(WRIST_CFG):
        print(f"\n读不到相机内参：{WRIST_CFG}")
        print("用 A2VEC_CAMERAS=/path/to/cameras.json 指定（需要 cameras.wrist.color_intrinsics）。")
        return 1
    cfg = json.load(open(WRIST_CFG))
    wrist = cfg["cameras"]["wrist"]
    K = np.array(wrist["color_intrinsics"], float)
    fx = K[0, 0]
    print(f"\n腕部相机   fx = {fx:.4f}")

    samples = [
        # (说明, 到板距离 Z 单位 mm, 实测 tag 像素边长)
        ("手机贴着桌面、相机 203mm（深度图实测，可能测到手机背面）", 203.4, 26.7),
    ]
    print(f"\n{'说明':<46} {'Z(mm)':>8} {'tag(px)':>8} {'反推 tag(mm)':>13} {'反推屏宽(mm)':>13}")
    for note, Z, tpx in samples:
        tag_m = tpx * (Z / 1000.0) / fx
        tag_mm = tag_m * 1000.0
        # app 里 tag 边长 = 板内容宽 * 165/content_px（板内容宽是屏上实宽，不等于屏宽）
        board_on_screen = b["width_on_screen_mm"]
        # App reports the active layout; do not assume the legacy 8-column board.
        content_px = b["image_px"][0]
        tag_mm_from_board = board_on_screen * BASE_TAG / content_px
        scale = tag_mm / tag_mm_from_board if tag_mm_from_board else 0
        screen_mm = s["width_mm"] * scale if scale else 0
        print(f"{note:<46} {Z:>8.1f} {tpx:>8.1f} {tag_mm:>13.4f} {screen_mm:>13.4f}")

    print(f"""
说明：
  - "反推屏宽" = 实测 tag 边长 / (165 / 板内容宽 px) / (板屏上宽 / 屏宽)
  - 与上面用 xDpi 算出的 {s['width_mm']:.2f} mm 比对，差异应在 1~2% 以内才可信
  - 深度图会把手机背面当成屏幕（手机屏幕是红外镜面），所以 Z 会偏小约一个机身厚度；
    真要精确定尺寸，用"示教触碰"：把 TCP 碰到屏幕上，读 TCP 位置，误差 < 1%
""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
