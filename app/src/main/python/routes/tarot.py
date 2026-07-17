"""
结构化塔罗占卜函数（Pilot）。

把原来 routes/塔罗路由.py 里 700+ 行的 docstring/prompt 路由，收敛成一个确定性函数。
AI 只需要调用 reading(...) 并解读返回的 JSON，无需自己拼写 engine API 代码。
"""

import json
import sys
import traceback
from typing import Any, Optional

# 确保 app/src/main/python 在 import 路径里（兼容 Android / 本地两种运行方式）
if "app/src/main/python" not in sys.path:
    sys.path.insert(0, "app/src/main/python")

from tarot_elemental_engine import ElementalDignityEngine as EE


def _safe_value(value: Any) -> str:
    """从 orientation / enum 中安全取字符串。"""
    if value is None:
        return "upright"
    val = getattr(value, "value", str(value))
    return "reversed" if str(val).lower() in ("reversed", "reverse") else "upright"


def _safe_call(fn, *args, default=None, **kwargs):
    """安全调用引擎 getter，出错时返回 default 而不是抛异常。"""
    try:
        return fn(*args, **kwargs)
    except Exception:
        return default


def _card_to_dict(dc: Any, position: Any, index: int) -> dict:
    """把一张抽出的牌（DrawnCard）和牌阵位置转成结构化 dict。"""
    orientation = _safe_value(getattr(dc, "orientation", None))
    reversed_flag = orientation == "reversed"
    rag = getattr(position, "rag_mapping", getattr(position, "name", f"position_{index}"))

    return {
        "index": index,
        "position": getattr(position, "name", f"position_{index}"),
        "rag_mapping": rag,
        "orientation": orientation,
        "card_id": getattr(dc, "card_id", None),
        "card_name": getattr(dc, "card_name", None),
        "card_number": getattr(dc, "card_number", None),
        "suit": getattr(dc, "suit", None),
        "core_meaning": _safe_call(dc.get_core_meaning, reversed=reversed_flag),
        "position_interpretation": _safe_call(dc.get_interpretation, rag, reversed=reversed_flag),
        "question_context_love": _safe_call(dc.get_question_context, "love", reversed=reversed_flag),
        "question_context_career": _safe_call(dc.get_question_context, "career", reversed=reversed_flag),
        "elemental_correspondences": _safe_call(dc.get_elemental_correspondences),
        "symbols": _safe_call(dc.get_symbols),
        "affirmations": _safe_call(dc.get_affirmations),
        "journaling_prompts": _safe_call(dc.get_journaling_prompts),
        "relationships": _safe_call(dc.get_relationships),
        "waite_meaning": _safe_call(dc.get_waite_meaning, orientation),
        "tk_meaning_en": _safe_call(dc.get_tk_meaning, orientation, "en"),
        "tk_meaning_zh": _safe_call(dc.get_tk_meaning, orientation, "zh"),
        "reading_aspects": getattr(dc, "reading_aspects", None),
        "contextual_meanings": getattr(dc, "contextual_meanings", None),
        "archetype": getattr(dc, "archetype", None),
        "description": getattr(dc, "description", None),
    }


def _build_narrative_hints(ee: dict, cards: list) -> dict:
    """基于引擎数据生成叙事提示，帮助 AI 组织解读。"""
    composition = ee.get("composition", {})
    stats = ee.get("statistics", {})
    reversal = ee.get("reversal", {})
    absence = ee.get("absence", {})

    major_ratio = composition.get("major_arcana_ratio")
    major_count = composition.get("major_arcana_count", 0)
    minor_count = composition.get("minor_arcana_count", 0)
    court_count = composition.get("court_count", 0)

    return {
        "overall_structure": {
            "total_cards": len(cards),
            "major_arcana_count": major_count,
            "minor_arcana_count": minor_count,
            "court_count": court_count,
            "major_arcana_ratio": major_ratio,
        },
        "energy_pattern": {
            "dominant_element": stats.get("dominant_element"),
            "weakest_element": stats.get("weakest_element"),
            "absent_elements": absence.get("absent_elements", []),
            "blocked_energy": reversal.get("blocked_energy_signal", False),
            "reversal_ratio": reversal.get("reversal_ratio", 0),
        },
        "narrative_principles": [
            "先给总体基调（大牌比例、元素分布、正逆位信号）",
            "再逐位置讲故事，把单张牌义放进位置语境里",
            "最后给出整合叙事与行动建议",
            "数据只用来增强语气，不要罗列字段",
        ],
    }


def reading(
    question: str,
    spread: str = "three_card",
    seed: Optional[int] = None,
) -> dict:
    """
    执行一次塔罗占卜，返回结构化 JSON。

    Args:
        question: 用户问题（用于返回，不影响抽牌）。
        spread: 牌阵 ID。常见可用值：
            - three_card / past_present_future / 3p
            - celtic_cross
            - horseshoe
            - five_cross / cross_five
            - relationship_spread
        seed: 抽牌随机种子。不传则真随机；传了可复现。

    Returns:
        dict: 包含 question, spread, cards, elemental_analysis, narrative_hints。
              如果出错，包含 error 字段。
    """
    try:
        from arcanite.core import TarotDeck
        from arcanite.core.spread import load_spread
    except Exception as e:
        return {"error": f"arcanite 加载失败: {e}"}

    try:
        deck = TarotDeck.load(system="tarot")
        spread_obj = load_spread(spread)
        positions = getattr(spread_obj, "positions", None) or []
        n = len(positions)

        if n == 0:
            return {
                "error": f"牌阵 '{spread}' 没有位置定义，请检查 spread ID。",
                "available_spread_aliases": [
                    "three_card", "past_present_future", "3p",
                    "celtic_cross", "horseshoe", "five_cross", "relationship_spread",
                ],
            }

        drawn = deck.draw(n, seed=seed)
        ee = EE.full_analysis(drawn)

        cards = [
            _card_to_dict(dc, positions[i], i)
            for i, dc in enumerate(drawn)
        ]

        return {
            "question": question,
            "spread": spread,
            "spread_name": getattr(spread_obj, "name", spread),
            "seed": seed,
            "drawn_count": n,
            "cards": cards,
            "elemental_analysis": {
                "spread_dignity": ee.get("spread_dignity", []),
                "statistics": ee.get("statistics", {}),
                "chain_analysis": ee.get("chain_analysis", {}),
                "island_detection": ee.get("island_detection", {}),
                "numerology": ee.get("numerology", {}),
                "composition": ee.get("composition", {}),
                "absence": ee.get("absence", {}),
                "doubling": ee.get("doubling", {}),
                "reversal": ee.get("reversal", {}),
            },
            "narrative_hints": _build_narrative_hints(ee, cards),
        }

    except Exception as e:
        return {
            "error": f"塔罗占卜执行失败: {e}",
            "traceback": traceback.format_exc(),
        }


def main_cli():
    """命令行快速测试入口：python routes/tarot.py"""
    q = input("问题: ").strip() or "今天运势如何"
    spread = input("牌阵: ").strip() or "three_card"
    seed_str = input("种子(留空随机): ").strip()
    seed = int(seed_str) if seed_str else None

    result = reading(question=q, spread=spread, seed=seed)
    print(json.dumps(result, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    main_cli()
