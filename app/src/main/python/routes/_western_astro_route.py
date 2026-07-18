"""Route:  western astro"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 现代西洋占星 =====
def _western_astro(year,month,day,hour,tz,lat,lon,depth="standard"):
    date_str=f"{year}-{month:02d}-{day}"
    _js_load("natalengine-engine")
    natal=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateAstrology('{date_str}',{hour},{tz},{lat},{lon}))")
    result={"system":"western_astrology","engine":"natalengine-js","natal":natal}
    # Caelus: 本命盘(宫位+逆行+尊贵) standard即提供
    _js_load("caelus-engine")
    c=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%sT%02d:00:00+08:00');var chart=e.chartAt(jd,%f,%f,{});JSON.stringify({signature:Caelus.chartSignature(chart),patterns:Caelus.detectPatterns(chart),bodies:chart.bodies,cusps:chart.cusps,angles:chart.angles,lots:Caelus.lots(e,jd,%f,%f),isDay:Caelus.isDayChart(e,jd,%f,%f),voidOfCourse:Caelus.voidOfCourse(e,jd)})"%(date_str,hour,lat,lon,lat,lon,lat,lon))
    result["caelus"]=c
    result["engine"]+="+Caelus"
    result["_hint"]="NatalEngine已返回日月升+7星+元素+相位+合盘+ACG。Caelus已返回12宫位+逆行+尊贵+格局+7点+空亡。"
    "自探索:Object.keys(Caelus)含推运7种/合盘3种/行运12/恒星2/ACG/赤纬/越界/映点/调和盘"
    if depth=="deep":
        c2=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%sT%02d:00:00+08:00');var chart=e.chartAt(jd,%f,%f,{});JSON.stringify({firdaria:Caelus.firdariaAt(e,jd,jd,%f,%f),profections:Caelus.profectionAt(e,jd,jd,%f,%f),primaryDirections:Caelus.primaryDirections(e,jd,%f,%f),solarArc:Caelus.solarArc(e,jd,jd),declinationAspects:Caelus.declinationAspects(e,Caelus.DEFAULT_BODIES,jd,1),outOfBounds:Caelus.outOfBounds(e,'moon',jd)})"%(date_str,hour,lat,lon,lat,lon,lat,lon,lat,lon))
        result["caelus_deep"]=c2
        result["engine"]+="+deep"
    return result
