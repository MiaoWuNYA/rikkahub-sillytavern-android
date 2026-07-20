"""Route:  tarot"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 塔罗 =====
def _tarot(spread="celtic-cross", seed=None, question_type=None, kaabalah=False, cards=None):
    from arcanite.core import TarotDeck
    from arcanite.core.spread import load_spread
    from tarot_elemental_engine import ElementalDignityEngine as EE
    deck = TarotDeck.load(system="tarot")
    sp = load_spread(spread)
    from arcanite.core.models import DrawnCard, Orientation
    if not cards or not isinstance(cards, list):
        drawn = deck.draw(len(sp.positions), seed=seed)
    else:
        drawn = []
        for i, entry in enumerate(cards):
            if isinstance(entry, str):
                entry = {"id": entry, "reversed": False}
            cid, is_rev = entry["id"], entry.get("reversed", False)
            card = deck.get_card(cid)
            dc = DrawnCard(card_id=card.card_id, card_name=card.card_name,
                           position_index=i, position_name="",
                           orientation=Orientation.REVERSED if is_rev else Orientation.UPRIGHT,
                           image_path=deck.get_image_path(card))
            dc._attach_deck(deck)
            drawn.append(dc)
    cards = []
    SUIT_URL={"wands":"W","cups":"C","swords":"S","pentacles":"P"}
    RANK_URL={1:"0A",2:"02",3:"03",4:"04",5:"05",6:"06",7:"07",8:"08",9:"09",10:"10",
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
                "image_tag": f"<img src='{img}' style='transform:rotate({180 if is_rev else 0}deg)'/>",
            })
        except Exception:
            cards.append({"position": sp.positions[i].rag_mapping, "card_number": dc.card_number if hasattr(dc,'card_number') else None, "error": "card data partial"})
    result = {
        "system": "tarot", "engine": "arcanite-unified", "seed": seed,
        "spread": {
        "id": spread,
        "name": getattr(sp, "name", ""),
        "description": getattr(sp, "description", ""),
        "layout": [{"x": lp.x, "y": lp.y} for lp in sp.layout.positions] if getattr(sp, "layout", None) and getattr(sp.layout, "positions", None) else None,
        "positions": [{"name": p.name, "rag": p.rag_mapping, "desc": p.short_description} for p in sp.positions]
    },
        "cards": cards, "ee_analysis": EE.full_analysis(drawn),
    }
    # 补全 EE 4个未包方法: 三牌尊贵/架桥/关系分类/流向
    try:
        els = [r["primary_element"] for r in result["ee_analysis"]["spread_dignity"]]
        n = len(els)
        result["dignity_triples"] = [EE.calc_dignity(els[i-1] if i>0 else None, els[i], els[i+1] if i+1<n else None) for i in range(n)]
        result["bridge_triples"] = [EE.check_bridge(els[i-1] if i>0 else None, els[i], els[i+1] if i+1<n else None) for i in range(n)]
        result["flow_pairs"] = [EE.get_directional_flow(els[i], els[i+1]) for i in range(n-1)]
        result["relation_pairs"] = [EE.classify_relation(els[i], els[i+1]) for i in range(n-1)]
        result["_hint"] = "arcanite内置18字段已全量。EE 18方法全覆盖。Kaabalah(JS): 22塔罗导出+5牌桌+7牌阵+卡巴拉对应+777表"
    except Exception:
        result["_hint"] = "arcanite内置18字段已全量。Kaabalah(JS): 22塔罗导出+5牌桌+7牌阵+卡巴拉对应+777表"
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
        # 生命之树全图 (22路径+11球体+777对应)
        try:
            result["kaabalah_tree"] = _js("kaabalah-engine", "JSON.stringify(Kaabalah.buildKaabalisticMapData({}))")
        except: pass
    return result
