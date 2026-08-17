#!/usr/bin/env python3
"""构建 assets/history/ 下的单日 JSON。

用法:
    python3 tools/build_history_assets.py            # 拉取上游并生成
    python3 tools/build_history_assets.py --self-test # 只跑年份解析器自检

产物入库提交；构建时不联网。可从任意工作目录运行（输出路径由 __file__ 推导）。
"""
import argparse
import glob
import io
import json
import os
import re
import sys
import tarfile
import urllib.request

# 钉死在上游 main 的最新提交（2024-10-04「删除 Wiki数据」），数据源已冻结。
# 换 SHA 必须同步更新下面三个 EXPECTED_* 常量，否则断言会拦截。
UPSTREAM_SHA = "a40df914cfada297c775dd66e6f927da1f454fff"
TARBALL = f"https://codeload.github.com/LuoH-AN/TodayInHistory/tar.gz/{UPSTREAM_SHA}"
DOWNLOAD_TIMEOUT = 60  # 秒

MEMBER_RE = re.compile(r"^[^/]+/Wiki/WikiJson/(\d{1,2})-(\d{1,2})\.json$")
LEAD_YEAR_RE = re.compile(r"^[^\d\-]{0,2}(-?\d+)")
KIND_CODE = {"event": "e", "birth": "b", "death": "d"}

YEAR_MIN = -5000          # 下界取 -5000：1-1 有一条合法条目年份为 -4713（儒略日纪元起点）
YEAR_MAX = 2100
MAX_YEAR_FIELD_LEN = 40   # 超长的 year 字段是上游抓取噪声（把正文段落当成了年份）

# 对上述冻结数据源的期望产出。不符即视为上游/解析规则发生了未预期的变化，硬失败。
EXPECTED_DAYS = 366
EXPECTED_KEPT = 54018
EXPECTED_DROPPED = 175

# 相对脚本自身定位：<repo>/tools/build_history_assets.py -> <repo>/app/src/main/assets/history
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "history")


def parse_year(raw):
    """把上游的 year 字段解析成整数；无法解析时返回 None。

    规则（已对全量 54,193 条回归验证：纯数字年份 0 错误，丢弃 175 条 / 0.32%）：
      1. 非字符串（null / 数字 / 缺字段）判为噪声
      2. 字段长度 > 40 判为噪声
      3. 数字必须出现在开头（允许最多 2 个前导非数字字符，覆盖 "• 2024"）
      4. 结果须落在 [YEAR_MIN, YEAR_MAX]
    """
    if not isinstance(raw, str):
        return None
    if len(raw) > MAX_YEAR_FIELD_LEN:
        return None
    m = LEAD_YEAR_RE.match(raw)
    if not m:
        return None
    value = int(m.group(1))
    if not (YEAR_MIN <= value <= YEAR_MAX):
        return None
    return value


def parse_entry(item):
    """把一条上游记录转成 [年份, 正文, 类型码]；无法使用时返回 None。

    对上游 schema 做轻量防御：字段缺失、类型不对、正文空白的条目一律丢弃并计入统计，
    而不是抛 KeyError/TypeError 中断整次构建。
    """
    if not isinstance(item, dict):
        return None

    year = parse_year(item.get("year"))
    if year is None:
        return None

    code = KIND_CODE.get(item.get("type"))
    if code is None:
        return None

    content = item.get("content")
    if not isinstance(content, str) or not content.strip():
        return None

    return [year, content, code]


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
        print(f"自检未通过：{len(SELF_TEST_CASES)} 条用例中有 {len(failures)} 条失败")
        print("\n".join(failures))
        return 1
    print(f"自检通过：{len(SELF_TEST_CASES)}/{len(SELF_TEST_CASES)}")
    return 0


def collect(blob):
    """解析 tarball，返回 (days, kept, dropped)。days 是 {(month, day): rows}。"""
    days = {}
    kept = 0
    dropped = 0

    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
        for member in tar.getmembers():
            m = MEMBER_RE.match(member.name)
            if not m:
                continue

            fh = tar.extractfile(member)
            if fh is None:  # 目录/符号链接等非常规成员
                print(f"警告：跳过无法读取的成员 {member.name}", file=sys.stderr)
                continue

            key = (int(m.group(1)), int(m.group(2)))
            if key in days:
                raise ValueError(f"上游存在重复日期 {key[0]}-{key[1]}（成员 {member.name}）")

            raw_entries = json.load(fh)
            if not isinstance(raw_entries, list):
                raise ValueError(f"{member.name} 顶层不是 JSON 数组")

            rows = []
            for item in raw_entries:
                row = parse_entry(item)
                if row is None:
                    dropped += 1
                    continue
                rows.append(row)

            # 稳定排序：同年份保持上游原始顺序
            rows.sort(key=lambda r: r[0])
            kept += len(rows)
            days[key] = rows

    return days, kept, dropped


def write_out(days):
    """先清空 OUT_DIR 下已有的 *.json，再整批落盘，避免残留上游已删除的日期。"""
    os.makedirs(OUT_DIR, exist_ok=True)
    for stale in glob.glob(os.path.join(OUT_DIR, "*.json")):
        os.remove(stale)

    for (month, day), rows in sorted(days.items()):
        out_path = os.path.join(OUT_DIR, f"{month}-{day}.json")
        with open(out_path, "w", encoding="utf-8") as fh:
            json.dump(rows, fh, ensure_ascii=False, separators=(",", ":"))


def build():
    print(f"拉取 {TARBALL} …")
    with urllib.request.urlopen(TARBALL, timeout=DOWNLOAD_TIMEOUT) as resp:
        blob = resp.read()

    # 全部解析进内存并校验通过后才落盘，失败不会留下半套产物。
    days, kept, dropped = collect(blob)

    total = kept + dropped
    ratio = f"{dropped / total:.2%}" if total else "n/a"
    print(f"解析 {len(days)} 天：保留 {kept} 条，丢弃 {dropped} 条 ({ratio})")

    problems = []
    if len(days) != EXPECTED_DAYS:
        problems.append(f"天数：期望 {EXPECTED_DAYS}，实际 {len(days)}")
    if kept != EXPECTED_KEPT:
        problems.append(f"保留条目数：期望 {EXPECTED_KEPT}，实际 {kept}")
    if dropped != EXPECTED_DROPPED:
        problems.append(f"丢弃条目数：期望 {EXPECTED_DROPPED}，实际 {dropped}")

    if problems:
        print("错误：产出与冻结数据源的期望值不符，未写入任何文件。", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        print(
            "若确认是有意更换上游 SHA 或调整解析规则，请同步更新 EXPECTED_* 常量。",
            file=sys.stderr,
        )
        return 1

    write_out(days)
    print(f"生成 {len(days)} 个文件到 {OUT_DIR}")
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true", help="只跑年份解析器自检")
    args = ap.parse_args()
    sys.exit(self_test() if args.self_test else build())
