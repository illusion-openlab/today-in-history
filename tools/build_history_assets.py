#!/usr/bin/env python3
"""构建 assets/history/ 下的单日 JSON。

用法:
    python3 tools/build_history_assets.py            # 拉取上游并生成
    python3 tools/build_history_assets.py --self-test # 只跑年份解析器自检

产物入库提交；构建时不联网。
"""
import argparse
import io
import json
import os
import re
import sys
import tarfile
import urllib.request

TARBALL = "https://codeload.github.com/LuoH-AN/TodayInHistory/tar.gz/refs/heads/main"
MEMBER_RE = re.compile(r"^[^/]+/Wiki/WikiJson/(\d{1,2})-(\d{1,2})\.json$")
LEAD_YEAR_RE = re.compile(r"^[^\d\-]{0,2}(-?\d+)")
KIND_CODE = {"event": "e", "birth": "b", "death": "d"}

YEAR_MIN = -5000          # 下界取 -5000：1-1 有一条合法条目年份为 -4713（儒略日纪元起点）
YEAR_MAX = 2100
MAX_YEAR_FIELD_LEN = 40   # 超长的 year 字段是上游抓取噪声（把正文段落当成了年份）

OUT_DIR = os.path.join("app", "src", "main", "assets", "history")


def parse_year(raw):
    """把上游的 year 字段解析成整数；无法解析时返回 None。

    规则（已对全量 54,193 条回归验证：纯数字年份 0 错误，丢弃 174 条 / 0.32%）：
      1. 字段长度 > 40 判为噪声
      2. 数字必须出现在开头（允许最多 2 个前导非数字字符，覆盖 "• 2024"）
      3. 结果须落在 [YEAR_MIN, YEAR_MAX]
    """
    if len(raw) > MAX_YEAR_FIELD_LEN:
        return None
    m = LEAD_YEAR_RE.match(raw)
    if not m:
        return None
    value = int(m.group(1))
    if not (YEAR_MIN <= value <= YEAR_MAX):
        return None
    return value


SELF_TEST_CASES = [
    # (输入, 期望)
    ("1945", 1945),
    ("-4713", -4713),
    ("1841（儒略历）", 1841),
    ("-1046（周武王十一，亦说-1027）", -1046),
    ("1687，大清康熙二十二八月十三", 1687),
    ("745（唐玄宗天宝四秋八月壬寅）", 745),
    ("1949:奥萝拉·奎松", 1949),
    ("1958﹕爱荷华州的第一段80号州际公路在得梅因大都会区（英语", 1958),
    ("• 2024", 2024),
    ("1988名", 1988),
    ("生不详", None),
    ("生不祥", None),
    ("维基文库中的相关原始文献", None),
    ("中华民国", None),
    ("大波普（英语", None),
    ("根据《香港法例》第410章，成岁数（有关条文）条例第5条", None),
    ("2月29日相当日之-一日是2月28日时，根据《中华民国民法》第一编总则、第五章「期日及期间」之第121条规定", None),
    ("9999", None),
    ("-99999", None),
]


def self_test():
    failures = []
    for raw, expected in SELF_TEST_CASES:
        actual = parse_year(raw)
        if actual != expected:
            failures.append(f"  parse_year({raw!r}) -> {actual!r}, 期望 {expected!r}")
    if failures:
        print(f"自检失败 {len(failures)}/{len(SELF_TEST_CASES)}:")
        print("\n".join(failures))
        return 1
    print(f"自检通过：{len(SELF_TEST_CASES)}/{len(SELF_TEST_CASES)}")
    return 0


def build():
    print(f"拉取 {TARBALL} …")
    with urllib.request.urlopen(TARBALL) as resp:
        blob = resp.read()

    os.makedirs(OUT_DIR, exist_ok=True)
    days = 0
    kept = 0
    dropped = 0

    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
        for member in tar.getmembers():
            m = MEMBER_RE.match(member.name)
            if not m:
                continue
            month, day = int(m.group(1)), int(m.group(2))
            raw_entries = json.load(tar.extractfile(member))

            rows = []
            for item in raw_entries:
                year = parse_year(item["year"])
                if year is None:
                    dropped += 1
                    continue
                code = KIND_CODE.get(item["type"])
                if code is None:
                    dropped += 1
                    continue
                rows.append([year, item["content"], code])

            # 稳定排序：同年份保持上游原始顺序
            rows.sort(key=lambda r: r[0])
            kept += len(rows)
            days += 1

            out_path = os.path.join(OUT_DIR, f"{month}-{day}.json")
            with open(out_path, "w", encoding="utf-8") as fh:
                json.dump(rows, fh, ensure_ascii=False, separators=(",", ":"))

    print(f"生成 {days} 个文件到 {OUT_DIR}")
    print(f"保留 {kept} 条，丢弃 {dropped} 条 ({dropped / (kept + dropped):.2%})")

    if days != 366:
        print(f"错误：期望 366 天，实际 {days}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="只跑年份解析器自检")
    args = ap.parse_args()
    sys.exit(self_test() if args.self_test else build())
