"""Route:  tarot"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 塔罗 =====
def _tarot(spread="celtic-cross", seed=None, question_type=None, kaabalah=False):
    from arcanite.core import TarotDeck
    from arcanite.core.spread import load_spread
    from tarot_elemental_engine import ElementalDignityEngine as EE
    deck = TarotDeck.load(system="tarot")
    sp = load_spread(spread)
    drawn = deck.draw(len(sp.positions), seed=seed)
    cards = []
    SUIT_URL={"wands":"W","cups":"C","swords":"S","pentacles":"P"}
    RANK_URL={1:"01",2:"02",3:"03",4:"04",5:"05",6:"06",7:"07",8:"08",9:"09",10:"10",
               11:"J1",12:"J2",13:"QU",14:"KI"}
    for i, dc in enumerate(drawn):
        try:
            is_rev = dc.orientation.value == "reversed"
            if dc.suit=="major_arcana" or dc.suit=="major":
                img=f"https://steve-p.org/cards/pix/RWSa-T-{dc.card_number:02d}.png"
            else:
                s=SUIT_URL.get(dc.suit,"T")
                n=RANK_URL.get(dc.card_number,f"{dc.card_number:02d}")
                img=f"https://steve-p.org/cards/pix/RWSa-{s}-{n}.png"
            cards.append({
                "position": sp.positions[i].rag_mapping,
                "card_number": dc.card_number, "card_name": dc.card_name,
                "suit": dc.suit, "orientation": dc.orientation.value,
                "core_meaning": dc.get_core_meaning(reversed=is_rev),
                "interpretation": dc.get_interpretation(sp.positions[i].rag_mapping, reversed=is_rev),
                "question_context": dc.get_question_context(question_type, reversed=is_rev) if question_type else None,
                "elemental": dc.get_elemental_correspondences(),
                "symbols": dict(dc.get_symbols()),
                "affirmations": dc.get_affirmations(),
                "journaling_prompts": dc.get_journaling_prompts(),
                "relationships": dc.get_relationships(),
                "archetype": getattr(dc, 'archetype', None),
                "reading_aspects": getattr(dc, 'reading_aspects', []),
                "contextual_meanings": getattr(dc, 'contextual_meanings', {}),
                "description": getattr(dc, 'description', {}),
                "waite_meaning": {"upright":dc.get_waite_meaning("upright"), "reversed":dc.get_waite_meaning("reversed")},
                "tk_meaning": {"upright_en":dc.get_tk_meaning("upright","en"), "upright_zh":dc.get_tk_meaning("upright","zh"),
                               "reversed_en":dc.get_tk_meaning("reversed","en"), "reversed_zh":dc.get_tk_meaning("reversed","zh")},
                "meditation_focus": (dc.raw_data or {}).get("meditation_focus") if hasattr(dc,'raw_data') else None,
                "image_url": img,
            })
        except Exception:
            cards.append({"position": sp.positions[i].rag_mapping, "card_number": dc.card_number if hasattr(dc,'card_number') else None, "error": "card data partial"})
    result = {
        "system": "tarot", "engine": "arcanite-unified", "seed": seed,
        "spread": {"id": spread, "positions": [p.rag_mapping for p in sp.positions]},
        "cards": cards, "ee_analysis": EE.full_analysis(drawn),
        "_hint": "arcanite内置18字段已全量。Kaabalah(JS): 22塔罗导出+5牌桌+7牌阵+卡巴拉对应+777表。自探索: Object.keys(Kaabalah)"
    }
    if kaabalah:
        _js_load("kaabalah-engine")
        kaabalah_results = []
        for c in drawn:
            try:
                n = c.card_number  # 直接用整数，不走脆弱的 card_name→JS 字符串→getTarotCardNumber 嵌套
                k = _js("kaabalah-engine", f"JSON.stringify(Kaabalah.getTarotCorrespondenceProfile({{tarotCardNumber:{n}}}))")
                kaabalah_results.append(k)
            except Exception:
                kaabalah_results.append(json.dumps({"error": "kaabalah bridge failed", "card": c.card_name}, ensure_ascii=False))
        result["kaabalah"] = kaabalah_results
    return result
