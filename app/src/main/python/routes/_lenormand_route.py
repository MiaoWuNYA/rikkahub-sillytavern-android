"""Route:  lenormand"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 雷诺曼 =====
def _lenormand(spread="line-5", seed=None):
    from arcanite.core import LenormandDeck
    from arcanite.core.spread import get_spread_registry
    d = LenormandDeck.load()
    sp = get_spread_registry(system="lenormand").load_spread(spread)
    items = d.draw_with_data(len(sp.positions), seed=seed)
    # card_id → 序号 (1-36) 映射, 用于图片URL
    _card_number = {c.card_id: i+1 for i, c in enumerate(d.cards)}
    cards = []
    for i, item in enumerate(items):
        try:
            cards.append({
                "position": sp.positions[i].name, "card_id": item.card_id,
                "card_name": item.card_name, "core": item.get_core(),
                "timing": item.get_timing(), "modifier": item.get_modifier_behavior(),
                "as_person": item.get_as_person(),
                "playing_card": item.get_playing_card(),
                "topic_contexts": item.get_topic_contexts(),
                "line_reading": item.get_line_reading(),
                "combination_grammar": item.get_combination_grammar(),
                "combinations": item.get_combinations(),
                "grand_tableau": item.get_grand_tableau(),
                "image_url": f"https://steve-p.org/cards/pix/PLen-A-{_card_number.get(item.card_id, 0):02d}.png",
                "image_tag": f"<img src='https://steve-p.org/cards/pix/PLen-A-{_card_number.get(item.card_id, 0):02d}.png'/>",
            })
        except Exception:
            cards.append({"position": sp.positions[i].name, "card_id": item.card_id if hasattr(item,'card_id') else None, "error": "card data partial"})
    from lenormand_engine import LenormandFateEngine as FE
    return {
        "system": "lenormand", "engine": "arcanite-unified", "seed": seed,
        "spread_positions": [p.name for p in sp.positions],
        "cards": cards,
        "statistics": d.analyze_draw(items),
        "karmic_mirrors": {i: FE.parse_karmic_mirrors(sp.positions, items) for i in [0]},
        "fe_portrait": FE.parse_portrait_3x3_cage(items, spread),
        "_hint": "arcanite 36张语义getter已全量。FE引擎另有: GT_portrait/骑士步/镜像/反射。自探索: dir(LenormandFateEngine)"
    }
