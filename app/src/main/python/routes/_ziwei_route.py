"""Route:  ziwei"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 紫微 =====
def _ziwei(year,month,day,hour,gender="male",engine="iztro"):
    # 兼容Kotlin传来的1/0整数
    if isinstance(gender, int):
        gender = "male" if gender == 1 else "female"
    date_str=f"{year}-{month:02d}-{day}"
    result={"system":"ziwei","engine":engine}
    if engine in ("iztro","all"):
        _js_load("iztro-engine")
        result["iztro"]=_js("iztro-engine",f"JSON.stringify(Iztro.astro.bySolar('{date_str}',{hour},'{gender}'))")
    if engine in ("nihai","all"):
        _js_load("ziwei-nihai")
        result["nihai"]=_js("ziwei-nihai",f"JSON.stringify(ZiweiNihai.generateChart({{year:{year},month:{month},day:{day},hour:{hour},gender:'{gender}'}}))")
    if engine in ("python","all"):
        try:
            sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
            from ziwei_paipan import by_solar
            result["ziwei_paipan"]=str(by_solar(date_str,hour,gender))
        except Exception as e: result["ziwei_paipan_error"]=str(e)
    result["_hint"]="Iztro全量已返回。另:surroundedPalaces三方四正/horoscope大限/soul+body。ZiweiNihai含倪海夏天纪+古籍。自探索:Object.keys(Iztro.astro)/dir(ziwei_paipan)"
    return result
