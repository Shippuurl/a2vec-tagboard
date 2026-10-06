#!/usr/bin/env python3
"""验证 android/TagBoard 的渲染口径 —— 直接编译并运行 app 的真源码
（BoardSpec + tools/RenderMain），不是复制一份参数。

判据：
  1. 所选布局的全部 tag 检出，hamming 全 0
  2. 每个 tag 的中心必须落在所在棋盘格的几何中心
     （公式 col*CELL + CELL/2, row*CELL + CELL/2 —— tag 在格内严格居中）
  3. 2800 / 2156 / 1920 / 1260 / 1080 五种屏宽下均全部检出

用法： python3 android/TagBoard/verify_render.py
"""
import os
import subprocess
import sys
import tempfile
import argparse

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA_HOME = os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
CELL = 236          # 棋盘格边长，必须与 BoardSpec.BASE_CELL 一致


def read_pgm(path):
    with open(path, "rb") as f:
        data = f.read()
    parts = data.split(b"\n", 3)
    assert parts[0] == b"P5", parts[0]
    w, h = [int(v) for v in parts[1].split()]
    return np.frombuffer(parts[3], np.uint8).reshape(h, w)


def build_board(tmpdir, layout):
    out = os.path.join(tmpdir, "classes")
    os.makedirs(out, exist_ok=True)
    subprocess.run([os.path.join(JAVA_HOME, "bin", "javac"), "-d", out,
                    os.path.join(HERE, "src", "com", "a2vec", "tagboard", "BoardSpec.java"),
                    os.path.join(HERE, "tools", "RenderMain.java")], check=True)
    pgm = os.path.join(tmpdir, "board.pgm")
    subprocess.run([os.path.join(JAVA_HOME, "bin", "java"), "-cp", out, "RenderMain", pgm, layout],
                   check=True, stdout=subprocess.DEVNULL)
    return read_pgm(pgm)


def main():
    import pupil_apriltags as pat
    parser = argparse.ArgumentParser()
    parser.add_argument('--layout', choices=('classic-12', 'compact-6'), default='classic-12')
    args = parser.parse_args()
    rows, cols, expected = (3, 4, 12) if args.layout == 'classic-12' else (2, 3, 6)

    with tempfile.TemporaryDirectory() as tmp:
        board = build_board(tmp, args.layout)
    print(f"[1] app 源码渲染出的板: {board.shape[1]} x {board.shape[0]} px")

    det = pat.Detector(families="tag36h11", nthreads=4, quad_decimate=1.0)
    canvas = np.full((board.shape[0], board.shape[1]), 255, np.uint8)
    canvas[:, :] = board
    res = det.detect(canvas)
    got = {d.tag_id: d for d in res}
    ids = sorted(got)

    print("[2] 检出（基线 1:1）")
    ok = ids == list(range(1, expected + 1)) and all(got[t].hamming == 0 for t in ids)
    print(f"    检出 {len(res)}/{expected}  {'OK' if ok else 'FAIL 缺 ' + str(sorted(set(range(1, expected + 1)) - set(ids)))}"
          f"   hamming {sorted(set(d.hamming for d in res))}")
    if not ok:
        return 1

    worst, worst_tag = 0.0, 0
    for r in range(rows):
        for i in range(cols):
            tid = r * cols + i + 1
            col = 2 * i + (r & 1)
            margin = 48
            ex, ey = margin + col * CELL + CELL / 2.0, r * CELL + CELL / 2.0
            gx, gy = got[tid].center
            e = max(abs(gx - ex), abs(gy - ey))
            if e > worst:
                worst, worst_tag = e, tid
    sizes = [float(np.linalg.norm(d.corners[0] - d.corners[1])) for d in res]
    print(f"    tag 中心 vs 格中心：最大偏差 {worst:.2f} px（tag{worst_tag}）")
    print(f"    tag 边长 {min(sizes):.1f} .. {max(sizes):.1f} px")
    ok_all = worst < 1.0

    print("[3] 不同屏宽下的检出")
    for width in (2800, 2156, 1920, 1260, 1080):
        sc = width / float(board.shape[1])
        import cv2
        big = cv2.resize(board, None, fx=sc, fy=sc, interpolation=cv2.INTER_NEAREST)
        pad = np.full((big.shape[0] + 40, big.shape[1] + 40), 255, np.uint8)
        pad[20:20 + big.shape[0], 20:20 + big.shape[1]] = big
        rr = det.detect(pad)
        i2 = sorted(d.tag_id for d in rr)
        good = i2 == list(range(1, expected + 1))
        ok_all &= good
        s = [float(np.linalg.norm(d.corners[0] - d.corners[1])) for d in rr]
        med = float(np.median(s)) if s else 0.0
        print(f"    屏宽 {width:>5}px  缩放 {sc:.4f}  检出 {len(rr):>2}/{expected} "
              f"{'OK' if good else 'FAIL'}   tag 边长中位 {med:6.1f} px")

    print(f"\n[4] 结论: {'tag 在格内居中，各屏宽均 ' + str(expected) + '/' + str(expected) if ok_all else '★仍有问题'}")
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
