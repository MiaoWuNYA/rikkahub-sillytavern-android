"""Route:  western astro"""
import json, sys, os
from ._shared import _js, _js_load, compute_jd

# ===== 现代西洋占星 =====
def _western_astro(year,month,day,hour,tz,lat,lon,minute=0):
    date_str=f"{year}-{month:02d}-{day}"
    # 构建时区偏移字符串，兼容数字和字符串
    tz_num = float(tz) if tz is not None else 8.0
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    jd = compute_jd(year, month, day, hour, minute, tz_num)
    hour_dec = hour + minute/60
    _js_load("natalengine-engine")
    natal=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateAstrology('{date_str}',{hour_dec},{tz_num},{lat},{lon}))")
    result={"system":"western_astrology","engine":"natalengine-js","natal":natal}
    # NatalEngine额外功能: ACG占星地图+合盘比较
    try:
        result["acg"]=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateAstroCartography('{date_str}',{hour_dec},{tz_num},{lat},{lon}))")
    except: pass
    try:
        result["synastry"]=_js("natalengine-engine",f"JSON.stringify(NatalEngine.compareAstrology(JSON.parse({natal}),JSON.parse({natal})))")
    except: pass
    # Caelus: 本命盘(宫位+逆行+尊贵) standard即提供
    _js_load("caelus-engine")
    c=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=%s;var chart=e.chartAt(jd,%f,%f,{});JSON.stringify({signature:Caelus.chartSignature(chart),patterns:Caelus.detectPatterns(chart),bodies:chart.bodies,cusps:chart.cusps,angles:chart.angles,lots:Caelus.lots(e,jd,%f,%f),isDay:Caelus.isDayChart(e,jd,%f,%f),voidOfCourse:Caelus.voidOfCourse(e,jd),chartBrief:Caelus.chartBrief(chart)})"%(jd,lat,lon,lat,lon,lat,lon))
    result["caelus"]=c
    result["engine"]+="+Caelus"
    result["_hint"]="NatalEngine已返回日月升+7星+元素+相位+合盘+ACG。Caelus已返回12宫位+逆行+尊贵+格局+7点+空亡。" "自探索:Object.keys(Caelus)含推运7种/合盘3种/行运12/恒星2/ACG/赤纬/越界/映点/调和盘"
    # always run
    c2=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=%s;var chart=e.chartAt(jd,%f,%f,{});var isDay=Caelus.isDayChart(e,jd,%f,%f);var ascIdx=chart.angles.ascendant.sign_index||0;var fortuneLon=0;try{fortuneLon=Caelus.lots(e,jd,%f,%f).fortune.lon;}catch(e){}var bodies_={};if(chart.bodies)chart.bodies.forEach(function(b){bodies_[b.id||b.name]=b});var cusps_=Caelus.housesPlacidus(e,jd,%f,%f);var natal_={bodies:bodies_,cusps:cusps_,zodiac:'tropical'};var transitJd_=jd+365;JSON.stringify({firdaria:Caelus.firdariaAt(e,jd,jd,%f,%f),profections:Caelus.profectionAt(e,jd,jd,%f,%f),primaryDirections:Caelus.primaryDirections(e,jd,%f,%f),solarArc:Caelus.solarArc(e,jd,jd),declinationAspects:Caelus.declinationAspects(e,Caelus.DEFAULT_BODIES,jd,1),outOfBounds:Caelus.outOfBounds(e,'moon',jd),profections_new:Caelus.profection(ascIdx,jd,jd+365),firdaria_new:Caelus.firdaria(isDay,jd),primaryDirections_new:Caelus.primaryDirections(e,jd,%f,%f),solarReturn:Caelus.solarReturn(e,jd,jd+3650,jd+4015),lunarReturn:Caelus.lunarReturn(e,jd,jd+27,jd+54),transits:Caelus.transitAspects(natal_,e,transitJd_),zrRelease:Caelus.zrRelease(fortuneLon,jd,2,100),vargaChart:Caelus.vargaChart(e,jd,9),antiscionSun:Caelus.antiscion(chart.bodies[0].lon),antiscionMoon:Caelus.antiscion(chart.bodies[1].lon),contraAntiscionSun:Caelus.contraAntiscion(chart.bodies[0].lon),contraAntiscionMoon:Caelus.contraAntiscion(chart.bodies[1].lon),voidOfCourse_new:Caelus.voidOfCourse(e,jd),planetaryHour:Caelus.planetaryHour(e,jd,%f,%f),housesWholeSign:Caelus.housesWholeSign(e,jd,%f),harmonicChart:Caelus.harmonicChart(e,jd,['sun','moon','venus','mars'],5),declinationAspects_new:Caelus.declinationAspects(e,['sun','moon','mercury','venus','mars','jupiter','saturn'],jd,1),astrocartography:Caelus.astrocartography(e,jd,['sun','moon','venus','mars','jupiter','saturn']),synastry:Caelus.synastryAspects(chart,chart,4),outOfBoundsMoon:Caelus.outOfBounds(e,'moon',jd,1)})"%(jd,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat))
    result["caelus_deep"]=c2
    result["engine"]+="+deep"
    return result
