"""塔罗元素尊贵法引擎 — 黄金黎明体系 + 现代四分类扩展 (韦特体系)

说明：黄金黎明原始 Book T 手稿只有三个等级(大幅增强/大幅减弱/友好)，
没有"中性"这个概念；本引擎采用的是后世更细致的四分类版本
(同元素=强化/主动+主动或被动+被动=友好/火+土或风+水=中性/火+水或风+土=减弱)，
这是现代教学中更常见的版本，但不等同于纯原版 Book T。
文件内 detect_element_chain / detect_element_island / DIRECTIONAL_FLOW
三项是本引擎在元素尊贵法基础上的自定义扩展，没有黄金黎明原始文献依据，
解读时可以作为辅助参考，不要当作"黄金黎明权威技法"对外宣称。
"""

from typing import List, Dict, Any, Optional


# ═══════════════════════════════════════════════════════════════════════════════
# CORE 1-4：静态数据表
# ═══════════════════════════════════════════════════════════════════════════════

ELEMENT_OF_SUIT = {
    "Wands": "Fire", "Cups": "Water",
    "Swords": "Air", "Pentacles": "Earth"
}

ELEMENT_QUALITIES = {
    "Fire":  {"polarity": "active",  "qualities": ["hot", "dry"]},
    "Air":   {"polarity": "active",  "qualities": ["hot", "moist"]},
    "Water": {"polarity": "passive", "qualities": ["cold", "moist"]},
    "Earth": {"polarity": "passive", "qualities": ["cold", "dry"]},
}

ELEMENT_RELATIONS = {
    "Fire":  {"Fire": "identical", "Air": "friendly", "Earth": "neutral",  "Water": "inimical"},
    "Air":   {"Fire": "friendly",  "Air": "identical","Earth": "inimical", "Water": "neutral"},
    "Earth": {"Fire": "neutral",   "Air": "inimical", "Earth": "identical","Water": "friendly"},
    "Water": {"Fire": "inimical",  "Air": "neutral",  "Earth": "friendly", "Water": "identical"},
}

DIGNITY_RANK = {
    "identical": {"rank": 4, "label_zh": "极佳"},
    "friendly":  {"rank": 3, "label_zh": "得位"},
    "neutral":   {"rank": 2, "label_zh": "中性"},
    "inimical":  {"rank": 1, "label_zh": "失位"},
}

# CORE 8：宫廷牌位阶元素 (韦特体系)
COURT_ELEMENT = {
    "King": "Air", "Queen": "Water",
    "Knight": "Fire", "Page": "Earth"
}

# CORE 10：大阿卡纳占星对应
MAJOR_ARCANA_CORRESPONDENCE = {
    "The Fool": "Air", "The Magician": "Mercury", "The High Priestess": "Moon",
    "The Empress": "Venus", "The Emperor": "Aries", "The Hierophant": "Taurus",
    "The Lovers": "Gemini", "The Chariot": "Cancer", "Strength": "Leo",
    "The Hermit": "Virgo", "Wheel of Fortune": "Jupiter", "Justice": "Libra",
    "The Hanged Man": "Water", "Death": "Scorpio", "Temperance": "Sagittarius",
    "The Devil": "Capricorn", "The Tower": "Mars", "The Star": "Aquarius",
    "The Moon": "Pisces", "The Sun": "Sun", "Judgement": "Fire", "The World": "Saturn"
}

# 行星→代理元素
PLANET_ELEMENT_PROXY = {
    "Mars": "Fire", "Sun": "Fire", "Jupiter": "Fire",
    "Mercury": "Air",
    "Venus": "Earth", "Saturn": "Earth",
    "Moon": "Water"
}

ZODIAC_ELEMENT = {
    "Aries": "Fire", "Leo": "Fire", "Sagittarius": "Fire",
    "Gemini": "Air", "Libra": "Air", "Aquarius": "Air",
    "Cancer": "Water", "Scorpio": "Water", "Pisces": "Water",
    "Taurus": "Earth", "Virgo": "Earth", "Capricorn": "Earth"
}

# 数字学：小牌数字词 → 数值 (宫廷牌不参与数字学加总，传统上无数字)
NUMBER_WORD_TO_INT = {
    "ace": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10
}

# 数字学：大阿卡纳固有编号 (The Fool=0 ... The World=21)
MAJOR_ARCANA_NUMBER = {
    "The Fool": 0, "The Magician": 1, "The High Priestess": 2, "The Empress": 3,
    "The Emperor": 4, "The Hierophant": 5, "The Lovers": 6, "The Chariot": 7,
    "Strength": 8, "The Hermit": 9, "Wheel of Fortune": 10, "Justice": 11,
    "The Hanged Man": 12, "Death": 13, "Temperance": 14, "The Devil": 15,
    "The Tower": 16, "The Star": 17, "The Moon": 18, "The Sun": 19,
    "Judgement": 20, "The World": 21
}


class ElementalDignityEngine:
    """黄金黎明元素尊贵法引擎 (韦特体系)"""

    # ── 元素提取 ──────────────────────────────────────────────────────────

    @staticmethod
    def get_element(card: Any) -> Optional[str]:
        """从 DrawnCard 或 TarotCard 提取元素 (通过 card_id 解析花色)"""
        name = card.card_name if hasattr(card, 'card_name') else str(card)
        card_id = card.card_id if hasattr(card, 'card_id') else ""

        # 1. 小牌/宫廷牌：从 card_id 提取花色 (格式: "ace_of_wands", "king_of_cups" 等)
        if "_of_" in card_id:
            suit = card_id.split("_of_")[-1].title()  # "Wands", "Cups", "Swords", "Pentacles"
            if suit in ELEMENT_OF_SUIT:
                return ELEMENT_OF_SUIT[suit]

        # 2. 大牌：从占星对应取 (无 _of_ 即大阿卡纳)
        for major_name in MAJOR_ARCANA_CORRESPONDENCE:
            if major_name in name:
                corr = MAJOR_ARCANA_CORRESPONDENCE[major_name]
                if corr in ELEMENT_RELATIONS:
                    return corr
                if corr in PLANET_ELEMENT_PROXY:
                    return PLANET_ELEMENT_PROXY[corr]
                if corr in ZODIAC_ELEMENT:
                    return ZODIAC_ELEMENT[corr]
                return None

        return None

    @staticmethod
    def get_secondary_element(card: Any) -> Optional[str]:
        """宫廷牌副元素 = 宫廷位阶元素 (King=Air, Queen=Water, Knight=Fire, Page=Earth)"""
        card_id = card.card_id if hasattr(card, 'card_id') else ""
        # 从 card_id 解析 rank: "king_of_wands" → "King"
        if "_of_" in card_id:
            rank = card_id.split("_of_")[0].title()  # "King", "Queen", "Knight", "Page"
            if rank in COURT_ELEMENT:
                return COURT_ELEMENT[rank]
        return None

    # ── CORE 5-7：三牌法 ─────────────────────────────────────────────────

    @staticmethod
    def calc_dignity(left_element: Optional[str], center_element: str,
                     right_element: Optional[str]) -> Dict[str, Any]:
        """三牌法：只分析中间牌，左右夹击判断"""
        # Cancellation: 左右敌对→中间牌取本义
        if left_element and right_element:
            if ELEMENT_RELATIONS[left_element][right_element] == "inimical":
                return {
                    "dignity": "Neutral", "dignity_zh": "中性",
                    "cancellation": True,
                    "rank": 2.0,
                    "note": "左右背景能量互耗，中间牌按本义解释"
                }

        relations = []
        if left_element:
            relations.append(ELEMENT_RELATIONS[center_element][left_element])
        if right_element:
            relations.append(ELEMENT_RELATIONS[center_element][right_element])

        if not relations:
            return {"dignity": "Neutral", "dignity_zh": "中性",
                    "cancellation": False, "rank": 2.0, "note": ""}

        avg = sum(DIGNITY_RANK[r]["rank"] for r in relations) / len(relations)
        if avg >= 3.5:
            dignity, zh = "Exalted", "极佳"
        elif avg >= 2.5:
            dignity, zh = "Well Dignified", "得位"
        elif avg >= 1.5:
            dignity, zh = "Neutral", "中性"
        else:
            dignity, zh = "Ill Dignified", "失位"

        return {"dignity": dignity, "dignity_zh": zh,
                "cancellation": False, "rank": round(avg, 1), "note": ""}

    # ── CORE 5-7：多牌阵滑窗 ─────────────────────────────────────────────

    @staticmethod
    def analyze_spread(cards: List[Any]) -> List[Dict[str, Any]]:
        """对整个牌阵做滑窗元素尊贵分析"""
        elements = [ElementalDignityEngine.get_element(c) for c in cards]
        results = []
        for i, el in enumerate(elements):
            if el is None:
                results.append({"card": cards[i].card_name if hasattr(cards[i], 'card_name') else str(cards[i]),
                                "element": None, "dignity_zh": "无元素", "note": "无法判定元素"})
                continue
            left = elements[i-1] if i > 0 else None
            right = elements[i+1] if i < len(elements) - 1 else None
            dignity = ElementalDignityEngine.calc_dignity(left, el, right)
            secondary = ElementalDignityEngine.get_secondary_element(cards[i])
            results.append({
                "card": cards[i].card_name if hasattr(cards[i], 'card_name') else str(cards[i]),
                "primary_element": el,
                "secondary_element": secondary,
                "index": i,
                **dignity
            })
        return results

    # ── EXTENDED 1：桥接规则 ──────────────────────────────────────────────

    @staticmethod
    def check_bridge(left_element: Optional[str], center_element: str,
                     right_element: Optional[str]) -> Dict[str, bool]:
        """一侧敌对，另一侧友善/相同→友善方为中间牌架桥"""
        if not (left_element and right_element):
            return {"bridged": False}
        l_rel = ELEMENT_RELATIONS[center_element][left_element]
        r_rel = ELEMENT_RELATIONS[center_element][right_element]
        sides = {l_rel, r_rel}
        if "inimical" in sides and ("friendly" in sides or "identical" in sides):
            return {"bridged": True, "hostility_reduced": True}
        return {"bridged": False}

    # ── EXTENDED 2：方向性流动 (本引擎自定义扩展，非Book T原始内容) ──────────

    DIRECTIONAL_FLOW = {
        ("Fire", "Air"): "action stimulates thought",
        ("Air", "Fire"): "thought stimulates action",
        ("Water", "Earth"): "emotion manifests materially",
        ("Earth", "Water"): "material reality shapes emotion",
        ("Fire", "Water"): "passion evaporates feeling",
        ("Water", "Fire"): "feeling extinguishes passion",
        ("Air", "Earth"): "thought obstructs matter",
        ("Earth", "Air"): "matter resists thought",
    }

    @staticmethod
    def get_directional_flow(from_el: str, to_el: str) -> str:
        return ElementalDignityEngine.DIRECTIONAL_FLOW.get((from_el, to_el), "")

    # ── ADVANCED 1：元素统计 ──────────────────────────────────────────────

    @staticmethod
    def element_statistics(elements: List[Optional[str]]) -> Dict[str, Any]:
        counts = {"Fire": 0, "Water": 0, "Air": 0, "Earth": 0}
        for el in elements:
            if el in counts:
                counts[el] += 1
        valid = {k: v for k, v in counts.items() if v > 0}
        if not valid:
            return {"counts": counts, "dominant": None, "deficient": None}
        dominant = max(valid, key=valid.get)
        deficient = min(valid, key=valid.get)
        return {"counts": counts, "dominant": dominant, "deficient": deficient,
                "fire_pct": round(counts["Fire"]/len(elements)*100) if elements else 0,
                "water_pct": round(counts["Water"]/len(elements)*100) if elements else 0,
                "air_pct": round(counts["Air"]/len(elements)*100) if elements else 0,
                "earth_pct": round(counts["Earth"]/len(elements)*100) if elements else 0}

    # ── ADVANCED 2：链式强化 (本引擎自定义扩展，非Book T原始内容) ──────────

    @staticmethod
    def detect_element_chain(elements: List[Optional[str]]) -> Dict[str, Any]:
        max_chain = 0
        current_chain = 0
        last_pair = None
        for i in range(1, len(elements)):
            a, b = elements[i-1], elements[i]
            if a and b:
                rel = ELEMENT_RELATIONS[a][b]
                pair = (a, b)
                if rel == "friendly" and pair == last_pair:
                    current_chain += 1
                elif rel == "friendly":
                    current_chain = 1
                else:
                    current_chain = 0
                last_pair = pair
            max_chain = max(max_chain, current_chain)
        return {"element_chain": max_chain >= 3, "chain_length": max_chain + 1 if max_chain > 0 else 0}

    # ── ADVANCED 3：元素孤岛 (本引擎自定义扩展，非Book T原始内容) ──────────

    @staticmethod
    def detect_element_island(elements: List[Optional[str]]) -> Dict[str, Any]:
        for i in range(1, len(elements) - 1):
            cur = elements[i]
            prev = elements[i-1]
            nxt = elements[i+1]
            if cur and prev and nxt and cur != prev and cur != nxt and prev == nxt:
                return {"isolated_element": cur, "index": i, "significance": "high",
                        "note": f"第{i+1}张牌 {cur} 被两侧 {prev} 包围，是元素孤岛，需额外关注"}
        return {"isolated_element": None}

    # ── ADVANCED 4：动态分类 ──────────────────────────────────────────────

    @staticmethod
    def classify_relation(a: str, b: str) -> str:
        rel = ELEMENT_RELATIONS[a][b]
        if rel == "friendly":
            return "dynamic" if {a, b} == {"Fire", "Air"} else "receptive"
        if rel == "inimical":
            return "evaporation_conflict" if {a, b} == {"Fire", "Water"} else "obstruction_conflict"
        return rel

    # ── 辅助：数值/花色提取 ──────────────────────────────────────────────

    @staticmethod
    def get_numeric_value(card: Any) -> Optional[int]:
        """提取数字学数值：小牌(Ace-10)取面值，大阿卡纳取固有编号(0-21)，宫廷牌返回None(无数字)"""
        name = card.card_name if hasattr(card, 'card_name') else str(card)
        card_id = card.card_id if hasattr(card, 'card_id') else ""

        if "_of_" in card_id:
            rank = card_id.split("_of_")[0]
            if rank in NUMBER_WORD_TO_INT:
                return NUMBER_WORD_TO_INT[rank]
            return None  # 宫廷牌(king/queen/knight/page)，无数字

        for major_name, num in MAJOR_ARCANA_NUMBER.items():
            if major_name in name:
                return num
        return None

    @staticmethod
    def get_suit(card: Any) -> Optional[str]:
        """提取花色 (Wands/Cups/Swords/Pentacles)，大阿卡纳返回None"""
        card_id = card.card_id if hasattr(card, 'card_id') else ""
        if "_of_" in card_id:
            suit = card_id.split("_of_")[-1].title()
            if suit in ELEMENT_OF_SUIT:
                return suit
        return None

    # ── NEW 1：数字学加总法 Numerological Reduction ─────────────────────

    @staticmethod
    def numerological_reduction(cards: List[Any]) -> Dict[str, Any]:
        """
        把牌阵里所有可数字化的牌(小牌面值+大阿卡纳编号)相加，约减成单一数字，
        代表整手牌的核心叙事主题。11、22 视为大师数字，不再继续约减。
        与元素尊贵法互补：元素尊贵法看"能量强弱"，这个看"叙事母题"。
        """
        values = [ElementalDignityEngine.get_numeric_value(c) for c in cards]
        used = [v for v in values if v is not None]
        if not used:
            return {"total": 0, "reduced": None, "is_master_number": False, "used_count": 0}

        total = sum(used)
        reduced = total
        is_master = False
        while reduced > 9:
            if reduced in (11, 22):
                is_master = True
                break
            reduced = sum(int(d) for d in str(reduced))

        return {
            "total": total,
            "reduced": reduced,
            "is_master_number": is_master,
            "used_count": len(used),
            "skipped_court_or_unparsed": len(values) - len(used),
        }

    # ── NEW 2：大牌/宫廷/数字构成统计 Arcana Composition Stats ───────────

    @staticmethod
    def arcana_composition_stats(cards: List[Any]) -> Dict[str, Any]:
        """
        统计大牌占比(越高=事件越宿命/重大)、宫廷牌占比(越高=人际因素主导)、
        重复出现的数字、重复出现的花色。
        """
        total = len(cards)
        if total == 0:
            return {}

        major_count = 0
        court_count = 0
        number_counter: Dict[int, int] = {}
        suit_counter: Dict[str, int] = {}

        for c in cards:
            card_id = c.card_id if hasattr(c, 'card_id') else ""
            name = c.card_name if hasattr(c, 'card_name') else str(c)

            if "_of_" not in card_id:
                if any(m in name for m in MAJOR_ARCANA_NUMBER):
                    major_count += 1
                continue

            rank = card_id.split("_of_")[0]
            if rank.title() in COURT_ELEMENT:
                court_count += 1

            val = ElementalDignityEngine.get_numeric_value(c)
            if val is not None:
                number_counter[val] = number_counter.get(val, 0) + 1

            suit = ElementalDignityEngine.get_suit(c)
            if suit:
                suit_counter[suit] = suit_counter.get(suit, 0) + 1

        repeated_numbers = {k: v for k, v in number_counter.items() if v >= 2}
        repeated_suits = {k: v for k, v in suit_counter.items() if v >= 2}

        return {
            "major_arcana_ratio": round(major_count / total, 2),
            "court_card_ratio": round(court_count / total, 2),
            "repeated_numbers": repeated_numbers,
            "repeated_suits": repeated_suits,
        }

    # ── NEW 3：缺席读法 Absence Reading ───────────────────────────────────

    @staticmethod
    def absence_reading(cards: List[Any]) -> Dict[str, Any]:
        """
        检测整手牌里完全没出现的花色/元素。缺席本身就是信息——
        比如完全没有圣杯(Cups/Water)，常暗示问卜者正在回避情感议题。
        """
        present_suits = {ElementalDignityEngine.get_suit(c) for c in cards}
        present_suits.discard(None)
        present_elements = {ElementalDignityEngine.get_element(c) for c in cards}
        present_elements.discard(None)

        all_suits = set(ELEMENT_OF_SUIT.keys())
        all_elements = set(ELEMENT_RELATIONS.keys())

        missing_suits = list(all_suits - present_suits)
        missing_elements = list(all_elements - present_elements)

        return {
            "missing_suits": missing_suits,
            "missing_elements": missing_elements,
            "note": (
                f"完全缺席: {', '.join(missing_suits) if missing_suits else '无'}。"
                f"缺席的花色/元素代表问卜者当下回避或尚未触及的领域。"
            )
        }

    # ── NEW 4：重复数字共振 Doubling/Mirroring ───────────────────────────

    @staticmethod
    def detect_doubling(cards: List[Any]) -> Dict[str, Any]:
        """
        同一数字在不同花色重复出现 = 该数字主题被放大确认。
        比如两张不同花色的5(冲突/挑战)同时出现，比单独一张5的警示意义更强。
        只统计小牌(Ace-10)，大阿卡纳数字不参与(本身就唯一，不存在重复)。
        """
        number_to_cards: Dict[int, List[str]] = {}
        for c in cards:
            card_id = c.card_id if hasattr(c, 'card_id') else ""
            if "_of_" not in card_id:
                continue
            val = ElementalDignityEngine.get_numeric_value(c)
            if val is not None:
                name = c.card_name if hasattr(c, 'card_name') else str(c)
                number_to_cards.setdefault(val, []).append(name)

        doublings = {
            num: names for num, names in number_to_cards.items() if len(names) >= 2
        }
        return {
            "doublings": doublings,
            "has_doubling": bool(doublings),
        }

    # ── NEW 5：正逆位统计 Reversal Statistics ─────────────────────────────

    @staticmethod
    def reversal_statistics(cards: List[Any]) -> Dict[str, Any]:
        """
        统计整手牌的逆位数量/占比。逆位比例≥70%时标记"能量受阻"信号——
        传统塔罗读法里，全逆位或高比例逆位常被解读为问题指向内在、
        事情卡在某个环节尚未外显。读取 card.orientation，找不到则跳过。
        """
        total = len(cards)
        if total == 0:
            return {}

        reversed_count = 0
        for c in cards:
            orientation = getattr(c, "orientation", None)
            val = getattr(orientation, "value", orientation)
            if val and str(val).lower() in ("reversed", "reverse"):
                reversed_count += 1

        ratio = round(reversed_count / total, 2)
        blocked = ratio >= 0.7

        return {
            "reversed_count": reversed_count,
            "upright_count": total - reversed_count,
            "reversal_ratio": ratio,
            "blocked_energy_signal": blocked,
            "note": "逆位占比过高，传统读法视为能量受阻、问题指向内在" if blocked else "",
        }

    # ── 一键综合 ──────────────────────────────────────────────────────────

    @staticmethod
    def full_analysis(cards: List[Any]) -> Dict[str, Any]:
        """一键运行所有元素尊贵法 + 数字学/构成/缺席/共振/正逆位统计"""
        spread = ElementalDignityEngine.analyze_spread(cards)
        elements = [r["primary_element"] for r in spread]
        stats = ElementalDignityEngine.element_statistics(elements)
        chain = ElementalDignityEngine.detect_element_chain(elements)
        island = ElementalDignityEngine.detect_element_island(elements)

        numerology = ElementalDignityEngine.numerological_reduction(cards)
        composition = ElementalDignityEngine.arcana_composition_stats(cards)
        absence = ElementalDignityEngine.absence_reading(cards)
        doubling = ElementalDignityEngine.detect_doubling(cards)
        reversal = ElementalDignityEngine.reversal_statistics(cards)

        return {
            "spread_dignity": spread,
            "statistics": stats,
            "chain_analysis": chain,
            "island_detection": island,
            "numerology": numerology,
            "composition": composition,
            "absence": absence,
            "doubling": doubling,
            "reversal": reversal,
        }
