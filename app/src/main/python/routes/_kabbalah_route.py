"""Route:  kabbalah"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 灵数卡巴拉 =====
def _kabbalah(year,month,day,word=None,feature="numerology"):
    _js_load("kaabalah-engine")
    result={"system":"kabbalah","engine":"kaabalah-js","_hint":"Kaabalah已返回灵数6核心+个人年/月/周期+挑战+斐波那契+Gematria正反查+Ifa Odu+生命之树+塔罗卡巴拉。自探索:Object.keys(Kaabalah)"}
    base_date=f"new Date({year},{month-1},{day},12)"
    if feature in ("numerology","all"):
        result["life_path"]=json.loads(_js("kaabalah-engine",f"JSON.stringify(Kaabalah.calculateKaabalisticLifePath({base_date}))"))
        result["personal"]=json.loads(_js("kaabalah-engine",f"JSON.stringify({{personalYear:Kaabalah.calculatePersonalYear({base_date},new Date()),challenges:Kaabalah.calculateChallenges({base_date}),fibonacci:Kaabalah.calculateFibonacciCycle({base_date},new Date()),dateEnergies:Kaabalah.getDateEnergies({base_date})}})"))    if feature in ("gematria","all") and word:
        import json as _json
        word_safe=_json.dumps(word)
        result["gematria"]=json.loads(_js("kaabalah-engine",f"JSON.stringify({{forward:Kaabalah.calculateGematria({word_safe}),reverse:Kaabalah.reverseGematria(Kaabalah.calculateGematria({word_safe})?.value||0)}})"))    if feature in ("odu","all"):
        result["odu"]=json.loads(_js("kaabalah-engine",f"JSON.stringify(Kaabalah.calculateOdu({base_date}))"))
    if feature in ("tarot","all"):
        result["tarot_spreads"]=json.loads(_js("kaabalah-engine","JSON.stringify(Kaabalah.listTarotSpreads())"))
    if feature in ("tree","all"):
        result["tree_of_life"]=json.loads(_js("kaabalah-engine","JSON.stringify(Kaabalah.buildKaabalisticMapData({}))"))
    return result
