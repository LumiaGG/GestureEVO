#!/usr/bin/env python3
"""Generate the lightweight SmartSegmenter dictionary.

The generated asset stores one word and frequency per line because the runtime
segmenter uses frequency-based dynamic programming.
"""

from __future__ import annotations

import argparse
import re
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_PATH = ROOT / "app/src/main/assets/segmenter/zh_core_dict.txt"

USER_AGENT = "GestureEVO-segmenter-dict-generator"

MIN_WORD_LENGTH = 2
MAX_WORD_LENGTH = 12
MAX_TOTAL_WORDS = 60_000
MAX_JIEBA_WORDS = 30_000


@dataclass(frozen=True)
class Source:
    name: str
    url: str
    limit: int


THUOCL_BASE = "https://raw.githubusercontent.com/thunlp/THUOCL/master/data"
THUOCL_SOURCES = [
    Source("THUOCL_IT", f"{THUOCL_BASE}/THUOCL_IT.txt", 7_000),
    Source("THUOCL_diming", f"{THUOCL_BASE}/THUOCL_diming.txt", 8_000),
    Source("THUOCL_caijing", f"{THUOCL_BASE}/THUOCL_caijing.txt", 6_000),
    Source("THUOCL_chengyu", f"{THUOCL_BASE}/THUOCL_chengyu.txt", 5_000),
    Source("THUOCL_food", f"{THUOCL_BASE}/THUOCL_food.txt", 5_000),
    Source("THUOCL_law", f"{THUOCL_BASE}/THUOCL_law.txt", 4_000),
    Source("THUOCL_medical", f"{THUOCL_BASE}/THUOCL_medical.txt", 5_000),
    Source("THUOCL_car", f"{THUOCL_BASE}/THUOCL_car.txt", 3_000),
    Source("THUOCL_animal", f"{THUOCL_BASE}/THUOCL_animal.txt", 3_000),
    Source("THUOCL_lishimingren", f"{THUOCL_BASE}/THUOCL_lishimingren.txt", 2_000),
]

JIEBA_DICT_URL = "https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt"

PROJECT_WORDS = [
    "GestureEVO", "Android", "OCR", "URL", "API", "Pin池", "大爆炸", "分词页面", "智能分词",
    "系统分词", "词典分词", "词典版本", "轻量分词", "无障碍", "无障碍服务", "无障碍内容",
    "悬浮窗", "悬浮球", "自由触发", "自由触发器", "触发区域", "触发动作", "手势操作",
    "手势导航", "手势管理", "手势配置", "手势动作", "触摸区域", "触摸偏移", "指针轨迹",
    "拖拽模式", "边缘手势", "上滑返回", "下滑通知", "框选区域", "框选截图", "区域截图",
    "屏幕截图", "智能选择", "文本选择", "选择文本", "选中文本", "长按选择", "拖动选择",
    "批量选择", "复制所选", "分享所选", "发送到EVO", "发送到应用", "搜索引擎", "搜索关键词",
    "搜索动作", "直接搜索", "快速搜索", "屏幕搜索", "搜索悬浮窗", "搜索方式", "搜索应用",
    "搜索历史", "搜索建议", "搜索结果", "直接链接", "引擎图标", "翻译应用", "翻译动作",
    "翻译结果", "打开网址", "打开网页", "打开浏览器", "发送文本", "发送图片", "Pin池显示",
    "Pin池模式", "Pin池项目", "Pin池提醒", "Pin池规则", "Pin池通知", "自动Pin", "手动Pin",
    "固定内容", "图片Pin", "文本Pin", "通知Pin", "OCR优先", "文字识别", "图像识别",
    "屏幕识别", "识别结果", "识别文本", "识别失败", "识别区域", "离线OCR", "本地OCR",
    "二维码", "二维码识别", "条形码", "条码识别", "扫码结果", "图片编辑器", "导出图片",
    "保存图片", "分享图片", "取件码", "取餐码", "验证码", "短信验证码", "邮箱验证码",
    "通知内容", "通知权限", "通知图标", "通知白名单", "通知黑名单", "本地规则", "时间猜测",
    "原版", "正版", "复刻", "复刻版", "重制", "重制版", "移植版", "试玩版", "正式版", "完整版", "增强版", "豪华版",
    "原作", "续作", "新作", "剧情", "故事", "角色", "主角", "结局", "关卡",
    "玩法", "让我", "让你", "让他", "让她", "让人", "只会", "不会", "没有",
    "已经", "觉得", "显得", "做得", "做成", "做出", "做出来", "讲述", "去讲",
    "太好", "太好了", "真要", "最终", "几乎", "胜算", "重复", "复杂", "失望",
    "满意", "喜欢", "推荐", "评价", "体验", "表现",]

WORD_RE = re.compile(r"^[\u3400-\u9fffA-Za-z0-9+#&._-]+$")
HAS_CJK_RE = re.compile(r"[\u3400-\u9fff]")
PURE_NUMBER_RE = re.compile(r"^[0-9.\-_/]+$")


def fetch_text(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode("utf-8", errors="ignore")


def normalize_word(raw: str) -> str | None:
    word = raw.strip().lstrip("\ufeff")
    if not word or word.startswith("#"):
        return None
    if not (MIN_WORD_LENGTH <= len(word) <= MAX_WORD_LENGTH):
        return None
    if PURE_NUMBER_RE.fullmatch(word):
        return None
    if not WORD_RE.fullmatch(word):
        return None
    return word


def parse_weighted_lines(text: str, limit: int) -> dict[str, int]:
    weighted: list[tuple[int, int, str]] = []
    for order, line in enumerate(text.splitlines()):
        parts = line.strip().split()
        if not parts:
            continue
        word = normalize_word(parts[0])
        if word is None:
            continue
        try:
            weight = int(float(parts[1])) if len(parts) > 1 else 1
        except ValueError:
            weight = 1
        weighted.append((-max(1, weight), order, word))
    weighted.sort()
    return {word: -weight for weight, _, word in weighted[:limit]}


def merge_weighted(groups: list[dict[str, int]]) -> list[tuple[str, int]]:
    merged: dict[str, int] = {}
    for group in groups:
        for word, weight in group.items():
            merged[word] = max(merged.get(word, 0), max(1, weight))
    return sorted(merged.items(), key=lambda item: (-item[1], len(item[0]), item[0]))[:MAX_TOTAL_WORDS]


def generate() -> list[tuple[str, int]]:
    groups: list[dict[str, int]] = [
        {word: 50_000_000 for word in dict.fromkeys(PROJECT_WORDS) if normalize_word(word) is not None}
    ]

    print("Fetching jieba dictionary...", file=sys.stderr)
    groups.append(parse_weighted_lines(fetch_text(JIEBA_DICT_URL), MAX_JIEBA_WORDS))

    for source in THUOCL_SOURCES:
        print(f"Fetching {source.name}...", file=sys.stderr)
        groups.append(parse_weighted_lines(fetch_text(source.url), source.limit))

    return merge_weighted(groups)


def write_output(path: Path, words: list[tuple[str, int]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    header = [
        "# GestureEVO SmartSegmenter dictionary",
        "# Generated by tools/generate_segmenter_dict.py",
        "# Format: word<TAB>frequency",
        "# Sources:",
        "# - jieba dict.txt, MIT License: https://github.com/fxsjy/jieba",
        "# - THUOCL, MIT License: https://github.com/thunlp/THUOCL",
        "# - GestureEVO project-specific custom words",
        "# Full license notices are bundled at assets/licenses/open_source_notices.txt",
        f"# Word count: {len(words)}",
        "",
    ]
    body = [f"{word}\t{weight}" for word, weight in words]
    path.write_text("\n".join(header + body) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=OUTPUT_PATH,
        help="Output dictionary path. Defaults to the app asset dictionary.",
    )
    args = parser.parse_args()

    words = generate()
    write_output(args.output, words)
    print(f"Wrote {len(words)} words to {args.output}")
    print(f"Size: {args.output.stat().st_size / 1024:.1f} KiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
