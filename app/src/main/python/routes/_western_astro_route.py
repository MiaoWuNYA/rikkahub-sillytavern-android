"""Route:  western astro"""
import json, sys, os
from ._shared import _js, _js_load, compute_jd

# ===== 现代西洋占星 =====
def _western_astro(year,month,day,hour,tz,lat,lon,minute=0):
    date_str=f"{year}-{month:02d}-{day}"
    tz_num = float(tz) if tz is not None else 8.0
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    jd = compute_jd(year, month, day, hour, minute, tz_num)
    hour_dec = hour + minute/60
    _js_load("natalengine-engine")
    natal=json.loads(_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateAstrology('{date_str}',{hour_dec},{tz_num},{lat},{lon}))"))
    result={"system":"western_astrology","engine":"natalengine-js","natal":natal}
    _engs=["natalengine-js"]
    if isinstance(natal, dict) and 'error' in natal:
        _engs[-1]="natalengine-js(ERROR)"
    # NatalEngine额外功能: ACG占星地图
    try:
        result["acg"]=json.loads(_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateAstroCartography('{date_str}',{hour_dec},{tz_num},{lat},{lon}))"))
    except: pass
    # Caelus: consolidated, each field individually guarded against cascading failure
    try:
        _js_load("caelus-engine")
        c=json.loads(_js("caelus-engine",
            "var e=new Caelus.Engine(Caelus.embeddedData);var jd=%s;"
            "var chart=e.chartAt(jd,%f,%f,{});"
            "var isDay=Caelus.isDayChart(e,jd,%f,%f);"
            "var ascIdx=Math.floor((chart.angles.ascendant.lon||0)/30);"
            "var fortuneLon=0;try{fortuneLon=Caelus.lots(e,jd,%f,%f).fortune.lon;}catch(e){}"
            "var ctx=Caelus.interpretationContext(chart);"
            "var safe=function(f){try{return f()}catch(ex){return null}};"
            "var bodies_={};if(chart.bodies)chart.bodies.forEach(function(b){bodies_[b.id||b.name]=b});"
            "var cusps_=Caelus.housesPlacidus(e,jd,%f,%f);"
            "var natal_={bodies:bodies_,cusps:cusps_,zodiac:'tropical'};"
            "var transitJd_=jd+365;"
            "var b0=safe(function(){return chart.bodies[0]})||{};"
            "var b1=safe(function(){return chart.bodies[1]})||{};"
            "JSON.stringify({"
            "signature:Caelus.chartSignature(chart),patterns:Caelus.detectPatterns(chart),"
            "bodies:chart.bodies,cusps:chart.cusps,angles:chart.angles,"
            "lots:Caelus.lots(e,jd,%f,%f),isDay:isDay,voidOfCourse:Caelus.voidOfCourse(e,jd),chartBrief:Caelus.chartBrief(ctx),"
            "firdaria:safe(function(){return Caelus.firdariaAt(e,jd,jd,%f,%f)}),profections:safe(function(){return Caelus.profectionAt(e,jd,jd,%f,%f)}),"
            "primaryDirections:safe(function(){return Caelus.primaryDirections(e,jd,%f,%f)}),solarArc:Caelus.solarArc(e,jd,jd),"
            "declinationAspects:safe(function(){return Caelus.declinationAspects(e,['sun','moon','mercury','venus','mars','jupiter','saturn'],jd,1)}),"
            "outOfBounds:Caelus.outOfBounds(e,'moon',jd),"
            "profections_new:Caelus.profection(ascIdx,jd,jd+365),firdaria_new:Caelus.firdaria(isDay,jd),"
            "solarReturn:safe(function(){return Caelus.solarReturn(e,jd,jd+3650,jd+4015)}),lunarReturn:safe(function(){return Caelus.lunarReturn(e,jd,jd+27,jd+54)}),"
            "transits:Caelus.transitAspects(natal_,e,transitJd_),"
            "zrRelease:Caelus.zrRelease(Math.floor(fortuneLon/30),jd,2,100),"
            "vargaChart:Caelus.vargaChart(e,jd,9),"
            "antiscionSun:Caelus.antiscion(b0.lon||0),antiscionMoon:Caelus.antiscion(b1.lon||0),"
            "contraAntiscionSun:Caelus.contraAntiscion(b0.lon||0),contraAntiscionMoon:Caelus.contraAntiscion(b1.lon||0),"
            "planetaryHour:Caelus.planetaryHour(e,jd,%f,%f),housesWholeSign:Caelus.housesWholeSign(e,jd,%f),"
            "harmonicChart:Caelus.harmonicChart(e,jd,['sun','moon','venus','mars'],5),"
            "astrocartography:Caelus.astrocartography(e,jd,['sun','moon','venus','mars','jupiter','saturn']),"
            "outOfBoundsMoon:Caelus.outOfBounds(e,'moon',jd,1)"
            "})"
            %(jd,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat)))
        if isinstance(c, dict) and 'error' not in c:
            result["caelus"]=c; _engs.append("Caelus")
    except: pass
    result["engine"]="+".join(_engs)
    result["_hint"]="NatalEngine已返回日月升+7星+元素+相位+合盘+ACG。Caelus已返回12宫位+逆行+尊贵+格局+7点+空亡+推运。" "自探索:Object.keys(Caelus)含推运7种/合盘3种/行运12/恒星2/ACG/赤纬/越界/映点/调和盘"
    return result
