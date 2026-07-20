"""Route:  lenormand"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 雷诺曼 =====
def _lenormand(spread="line-5", seed=None, cards=None):
    from arcanite.core import LenormandDeck
    from arcanite.core.spread import get_spread_registry
    d = LenormandDeck.load()
    sp = get_spread_registry(system="lenormand").load_spread(spread)
    from arcanite.core.models import DrawnCard, Orientation
    from arcanite.core.deck import LenormandDrawnCard
    if not cards or not isinstance(cards, list):
        items = d.draw_with_data(len(sp.positions), seed=seed, allow_reversals=False)
    else:
        items = []
        for i, entry in enumerate(cards):
            if isinstance(entry, str):
                entry = {"id": entry}
            cid = entry["id"]
            card = d.get_card(cid)
            dc = DrawnCard(card_id=card.card_id, card_name=card.card_name,
                           position_index=i, position_name="", orientation=Orientation.UPRIGHT,
                           image_path=None)
            dc._attach_deck(d)
            items.append(LenormandDrawnCard(dc, card))
    _card_number = {c.card_id: i+1 for i, c in enumerate(d.cards)}
    cards = []
    for i, item in enumerate(items):
        try:
            cards.append({
                "position": sp.positions[i].name, "card_id": item.card_id, "orientation": item.orientation.value,
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
    result = {
        "system": "lenormand", "engine": "arcanite-unified", "seed": seed,
        "spread": {
        "id": spread,
        "name": getattr(sp, "name", ""),
        "description": getattr(sp, "description", ""),
        "layout": [{"x": lp.x, "y": lp.y} for lp in sp.layout.positions] if getattr(sp, "layout", None) and getattr(sp.layout, "positions", None) else None,
        "positions": [{"name": p.name, "rag": getattr(p, "rag_mapping", ""), "desc": getattr(p, "short_description", ""),
                        "mirror": getattr(p, "mirror_target", None), "sig": getattr(p, "is_significator", False)}
                       for p in sp.positions]
    },
        "cards": cards,
        "statistics": d.analyze_draw(items),
        "karmic_mirrors": FE.parse_karmic_mirrors(sp.positions, items),
        "fe_portrait": FE.parse_portrait_3x3_cage(items, spread),
    }
    if spread == "grand-tableau":
        result["gt_master"] = FE.parse_grand_tableau_master_mode(items, sp.positions)
        sig_idx = result["gt_master"]["significator_absolute_index"]
        result["gt_intersection"] = FE.get_intersection(sig_idx)
        result["counting_pulse"] = FE.calculate_counting_pulse(items, 0)
        result["_hint"] = "arcanite 36张语义getter已全量。FE引擎已全覆盖: karmic_mirrors/portrait/GT_master(step1-4)/intersection/counting_pulse"
    else:
        result["_hint"] = "arcanite 36张语义getter已全量。牌阵<10张: karmic_mirrors+portrait。GT牌阵另含 GT_master(step1-4)+intersection+counting_pulse"
    return result
