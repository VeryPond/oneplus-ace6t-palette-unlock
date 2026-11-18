#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_verify.py —— 调色盘模块构建与校验的一站式脚本

为什么需要它：第 28 轮踩过一个坑——签名脚本用了残留的旧 APK 作输入，
命令返回成功，但产物 dex 是 28612 字节（最初 v2.2 的大小），
完全没有包含当轮修改。只看"命令成功"会交付假包。

本脚本把关键校验固化：
  1. 前置检查：base APK 存在且 dex 大小符合预期
  2. 组包：替换 classes.dex，保持 resources.arsc 为 STORED
  3. 签名前：校验新 dex 确实被写入
  4. 签名后：读回 APK 校验 dex 大小、关键类字符串、assets 数量、LUT SHA
  5. 全部通过才输出 OK

用法：
  python build_verify.py --base <base.apk> --dex <classes.dex> --out <out.apk> \
      --expect-dex 46288 --require-class PathInjectHook --require-class SupportFlagHook

退出码 0 = 全部校验通过；非 0 = 有一项失败（会打印 FAIL 行）
"""
import argparse, hashlib, os, sys, zipfile

LUT_SHA = ['694f087ef1d1','ad281da87cc9','8da8aca255cb','b72680c2af5f',
           '45c26b247cc6','fd4d798a6840','f3a7739006e1','7265cf25cfd3','38496c97d641']

def fail(msg):
    print('FAIL: ' + msg)
    return 1

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--base', required=True, help='基础 APK（含 assets）')
    ap.add_argument('--dex', required=True, help='新编译的 classes.dex')
    ap.add_argument('--out', required=True, help='输出 APK（未签名）')
    ap.add_argument('--expect-dex', type=int, default=0, help='期望的 dex 字节数，0=不校验')
    ap.add_argument('--require-class', action='append', default=[], help='必须包含的类名')
    ap.add_argument('--expect-assets', type=int, default=0, help='期望 assets 条目数')
    args = ap.parse_args()

    # ---- 1. 前置检查 ----
    if not os.path.isfile(args.base):
        return fail('base APK 不存在: ' + args.base)
    if not os.path.isfile(args.dex):
        return fail('新 dex 不存在: ' + args.dex)
    base_size = os.path.getsize(args.base)
    dex_size = os.path.getsize(args.dex)
    print('前置: base=%d B  dex=%d B' % (base_size, dex_size))
    if args.expect_dex and dex_size != args.expect_dex:
        return fail('新 dex 大小 %d 与期望 %d 不符' % (dex_size, args.expect_dex))

    # base 必须已有 assets（否则部署会失败）
    with zipfile.ZipFile(args.base) as zb:
        base_assets = [n for n in zb.namelist() if n.startswith('assets/')]
    print('前置: base 内 assets = %d 条' % len(base_assets))
    if len(base_assets) == 0:
        return fail('base APK 内没有 assets，PathInjectHook 会部署失败')

    # ---- 2. 组包 ----
    newdex = open(args.dex, 'rb').read()
    if os.path.exists(args.out):
        os.remove(args.out)
    replaced = False
    with zipfile.ZipFile(args.base) as zs, \
         zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as zo:
        for item in zs.infolist():
            data = zs.read(item.filename)
            if item.filename == 'classes.dex':
                data = newdex
                replaced = True
            method = zipfile.ZIP_STORED if item.filename == 'resources.arsc' else zipfile.ZIP_DEFLATED
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = method
            zi.external_attr = item.external_attr
            zo.writestr(zi, data)
    if not replaced:
        return fail('base APK 里没有 classes.dex 条目')
    print('组包: %s  %d B' % (args.out, os.path.getsize(args.out)))

    # ---- 3. 签名后校验（此处校验未签名包，签名脚本之后应再跑一次 --verify-only） ----
    return verify(args.out, args.expect_dex, args.require_class, args.expect_assets)


def verify(path, expect_dex, require_classes, expect_assets):
    if not os.path.isfile(path):
        return fail('产物不存在: ' + path)
    with zipfile.ZipFile(path) as z:
        if 'classes.dex' not in z.namelist():
            return fail('产物内无 classes.dex')
        d = z.read('classes.dex')
        print('校验: dex = %d B' % len(d))
        if expect_dex and len(d) != expect_dex:
            return fail('产物 dex 大小 %d 与期望 %d 不符（可能用了残留旧文件）'
                        % (len(d), expect_dex))
        for cls in require_classes:
            ok = cls.encode() in d
            print('校验: 含 %-28s %s' % (cls, 'OK' if ok else 'MISSING'))
            if not ok:
                return fail('产物缺少必需类 ' + cls)
        if expect_assets:
            n = len([x for x in z.namelist() if x.startswith('assets/')])
            print('校验: assets = %d (期望 %d)' % (n, expect_assets))
            if n != expect_assets:
                return fail('assets 数量不符')
        # LUT SHA（仅当存在 assets/palette/ 时校验）
        names = z.namelist()
        if all('assets/palette/SC16Lut%d' % i in names for i in range(9)):
            ok = sum(1 for i in range(9)
                     if hashlib.sha256(z.read('assets/palette/SC16Lut%d' % i))
                        .hexdigest()[:12] == LUT_SHA[i])
            print('校验: LUT SHA = %d/9' % ok)
            if ok != 9:
                return fail('LUT 内容与官方不一致')
    print('ALL OK')
    return 0


if __name__ == '__main__':
    # 支持 --verify-only 模式
    argv = sys.argv[1:]
    if argv and argv[0] == '--verify-only':
        import argparse as _ap
        p = _ap.ArgumentParser()
        p.add_argument('--verify-only', required=True)
        p.add_argument('--expect-dex', type=int, default=0)
        p.add_argument('--require-class', action='append', default=[])
        p.add_argument('--expect-assets', type=int, default=0)
        a = p.parse_args()
        sys.exit(verify(a.verify_only, a.expect_dex, a.require_class, a.expect_assets))
    sys.exit(main())
