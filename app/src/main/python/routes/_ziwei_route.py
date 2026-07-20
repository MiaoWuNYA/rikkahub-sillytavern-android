"""Route:  ziwei"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 紫微 =====
def _ziwei(year,month,day,hour,minute=0,gender="male",engine="iztro"):
    date_str=f"{year}-{month:02d}-{day}"
    hour_dec = hour + minute/60
    if isinstance(gender, int):
        gender = "male" if gender == 1 else "female"
    result={"system":"ziwei","engine":engine}
    if engine in ("iztro","all"):
        _js_load("iztro-engine")
        r=_js("iztro-engine",f"JSON.stringify(Iztro.astro.bySolar('{date_str}',{hour_dec},'{gender}'))")
        result["iztro"]=r
        result["iztro_extra"]=_js("iztro-engine",
            "var a=Iztro.astro.bySolar('%s',%d,'%s');"
            "JSON.stringify({"
            "soul:Iztro.astro.soul,body:Iztro.astro.body,"
            "horoscope:Iztro.astro.horoscope(),"
            "surroundedPalaces:[0,1,2,3,4,5,6,7,8,9,10,11].map(function(i){return Iztro.astro.surroundedPalaces(i)})"
            "})" % (date_str, hour_dec, gender))
    if engine in ("nihai","all"):
        _js_load("ziwei-nihai")
        result["nihai"]=_js("ziwei-nihai",f"JSON.stringify(ZiweiNihai.generateChart({{year:{year},month:{month},day:{day},hour:{hour_dec},gender:'{gender}'}}))")
    if engine in ("python","all"):
        try:
            sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
            from ziwei_paipan import by_solar
            result["ziwei_paipan"]=by_solar(date_str,int(hour_dec),gender)
        except Exception as e: result["ziwei_paipan_error"]=str(e)
    result["_hint"]="Iztro全量已返回。另:surroundedPalaces三方四正/horoscope大限/soul+body。ZiweiNihai含倪海夏天纪+古籍。自探索:Object.keys(Iztro.astro)/dir(ziwei_paipan)"
    return result
