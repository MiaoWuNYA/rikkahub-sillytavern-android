"""Route:  kabbalah"""
import json, sys, os
from ._shared import _js, _js_load

def _safe_js(lib, code):
    raw = _js(lib, code)
    if not raw or raw.strip() in ("", "{}", '""', "null", "undefined"):
        return {}
    try:
        return json.loads(raw)
    except:
        return {}

# ===== 灵数卡巴拉 =====
def _kabbalah(year,month,day,word=None,feature="numerology"):
    _js_load("kaabalah-engine")
    result={"system":"kabbalah","engine":"kaabalah-js","_hint":"Kaabalah已返回灵数6核心+个人年/月/周期/月份+挑战+斐波那契+Gematria正反查+Ifa Odu+生命之树+塔罗卡巴拉+年/龄/月周期(cycles_info)。自探索:Object.keys(Kaabalah)"}
    base_date=f"new Date({year},{month-1},{day},12)"
    if feature in ("numerology","all"):
        result["life_path"]=_safe_js("kaabalah-engine",f"JSON.stringify(Kaabalah.calculateKaabalisticLifePath({base_date}))")
        result["personal"]=_safe_js("kaabalah-engine",f"JSON.stringify({{personalYear:Kaabalah.calculatePersonalYear({base_date},new Date()),challenges:Kaabalah.calculateChallenges({base_date}),fibonacci:Kaabalah.calculateFibonacciCycle({base_date},new Date()),dateEnergies:Kaabalah.getDateEnergies({base_date}),personalMonths:Kaabalah.calculatePersonalMonths({base_date},Kaabalah.calculatePersonalYear({base_date},new Date()),new Date())}})")
        result["cycles_info"]=_safe_js("kaabalah-engine",f"JSON.stringify(Kaabalah.calculateCycles({base_date},new Date()))")
    if feature in ("gematria","all") and word:
        import json as _json
        word_safe=_json.dumps(word)
        result["gematria"]=_safe_js("kaabalah-engine",f"JSON.stringify({{forward:Kaabalah.calculateGematria({word_safe}),reverse:Kaabalah.reverseGematria(Kaabalah.calculateGematria({word_safe})?.value||0)}})")
    if feature in ("odu","all"):
        result["odu"]=_safe_js("kaabalah-engine",f"JSON.stringify(Kaabalah.calculateOdu({base_date}))")
    if feature in ("tarot","all"):
        result["tarot_spreads"]=_safe_js("kaabalah-engine","JSON.stringify(Kaabalah.listTarotSpreads())")
    if feature in ("tree","all"):
        result["tree_of_life"]=_safe_js("kaabalah-engine","JSON.stringify(Kaabalah.buildKaabalisticMapData({}))")
    return result
