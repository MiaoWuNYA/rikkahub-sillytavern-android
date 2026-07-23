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
            "var _lat=%f;var _lon=%f;var chart=e.chartAt(jd,_lat,_lon,{});"
            "var isDay=Caelus.isDayChart(e,jd,_lat,_lon);"
            "var ascIdx=Math.floor(chart.angles.asc/30);"
            "var fortuneLon=0;try{fortuneLon=Caelus.lots(e,jd,_lat,_lon).fortune.lon;}catch(e){}"
            "var ctx=Caelus.interpretationContext(chart);"
            "var safe=function(f){try{return f()}catch(ex){return null}};"
            "var transitJd_=jd+365;"
            "var isDayStr=isDay?'day':'night';"
            "var p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "var dScores={};p7.forEach(function(p){try{var b=chart.bodies[p];if(b)dScores[p]=Caelus.dignityScore(p,b.lon,isDayStr)}catch(ex){}});"
            "var dignOf={};p7.forEach(function(b){dignOf[b]=safe(function(){return Caelus.dignityOf(e,b,jd)})});"
            "var ph={};p7.forEach(function(b){ph[b]=safe(function(){return Caelus.pheno(e,b,jd)})});"
            "JSON.stringify({"
            "signature:Caelus.chartSignature(chart),patterns:Caelus.detectPatterns(chart),"
            "bodies:chart.bodies,cusps:chart.cusps,angles:chart.angles,"
            "lots:Caelus.lots(e,jd,_lat,_lon),isDay:isDay,voidOfCourse:Caelus.voidOfCourse(e,jd),chartBrief:Caelus.chartBrief(ctx),"
            "firdaria:safe(function(){return Caelus.firdariaAt(e,jd,jd,_lat,_lon)}),profections:safe(function(){return Caelus.profectionAt(e,jd,jd,_lat,_lon)}),"
            "primaryDirections:safe(function(){return Caelus.primaryDirections(e,jd,_lat,_lon)}),solarArc:Caelus.solarArc(e,jd,jd),"
            "declinationAspects:safe(function(){return Caelus.declinationAspects(e,['sun','moon','mercury','venus','mars','jupiter','saturn'],jd,1)}),"
            "outOfBounds:Caelus.outOfBounds(e,'moon',jd),"
            "profections_new:Caelus.profection(ascIdx,jd,jd+365),firdaria_new:Caelus.firdaria(isDay,jd),"
            "solarReturn:safe(function(){return Caelus.solarReturn(e,jd,jd+3650,jd+4015)}),lunarReturn:safe(function(){return Caelus.lunarReturn(e,jd,jd+27,jd+54)}),"
            "transits:Caelus.transitAspects(chart,e,transitJd_),"
            "zrRelease:Caelus.zrRelease(Math.floor(fortuneLon/30),jd,2,100),"
            "vargaChart:Caelus.vargaChart(e,jd,9),"
            "antiscionSun:Caelus.antiscion(chart.bodies.sun.lon),antiscionMoon:Caelus.antiscion(chart.bodies.moon.lon),"
            "contraAntiscionSun:Caelus.contraAntiscion(chart.bodies.sun.lon),contraAntiscionMoon:Caelus.contraAntiscion(chart.bodies.moon.lon),"
            "planetaryHour:Caelus.planetaryHour(e,jd,_lat,_lon),housesWholeSign:Caelus.housesWholeSign(chart.angles.asc),"
            "harmonicChart:Caelus.harmonicChart(e,jd,['sun','moon','venus','mars'],5),"
            "astrocartography:Caelus.astrocartography(e,jd,['sun','moon','venus','mars','jupiter','saturn']),"
            "outOfBoundsMoon:Caelus.outOfBounds(e,'moon',jd,1),"
            "starConjunctions:e.starConjunctions(chart,{orb:1,maxMag:2.5}),"
            "dignityScores:dScores,"
            "almuten:Caelus.almuten(chart.angles.asc,isDayStr),"
            "lunarPhases:Caelus.lunarPhases(e,jd,jd+30,8),"
            "stations:Caelus.stations(e,'mars',jd,jd+365,5),"
            "eclipses:safe(function(){return{solar:Caelus.solarEclipses(e,jd,jd+365).slice(0,3),lunar:Caelus.lunarEclipses(e,jd,jd+365).slice(0,3)}}),"
            "pheno:ph,"
            "solarPhase:{mercury:safe(function(){return Caelus.solarPhase(e,'mercury',jd)}),venus:safe(function(){return Caelus.solarPhase(e,'venus',jd)}),mars:safe(function(){return Caelus.solarPhase(e,'mars',jd)})},"
            "dignityOf:dignOf,"
            "gauquelinSector:{sun:safe(function(){return Caelus.gauquelinSector(e,'sun',jd,_lat,_lon)}),moon:safe(function(){return Caelus.gauquelinSector(e,'moon',jd,_lat,_lon)}),mercury:safe(function(){return Caelus.gauquelinSector(e,'mercury',jd,_lat,_lon)}),venus:safe(function(){return Caelus.gauquelinSector(e,'venus',jd,_lat,_lon)}),mars:safe(function(){return Caelus.gauquelinSector(e,'mars',jd,_lat,_lon)}),jupiter:safe(function(){return Caelus.gauquelinSector(e,'jupiter',jd,_lat,_lon)}),saturn:safe(function(){return Caelus.gauquelinSector(e,'saturn',jd,_lat,_lon)})},"
            "vertexEastPoint:safe(function(){return Caelus.vertexEastPoint(e,jd,_lat,_lon)}),"
            "parans:safe(function(){return Caelus.parans(e,jd,_lat,p7,30)})"
            "})"
            %(jd,lat,lon)))
        if isinstance(c, dict) and 'error' not in c:
            result["caelus"]=c; _engs.append("Caelus")
    except: pass
    result["engine"]="+".join(_engs)
    result["_hint"]="NatalEngine已返回日月升+7星+元素+相位+合盘+ACG。Caelus已返回12宫位+逆行+尊贵+格局+7点+空亡+推运+恒星合相+本质评分(Ptolemy terms/faces)+almuten+月相+行星留+日月食。" "Caelus新增:pheno(行星可见性/星等/视直径)/solarPhase(Cazimi/Combust)/dignityOf(完整尊贵)/gauquelinSector(高奎林)/vertexEastPoint(宿命点/东点)/parans(共升共落)。" "自探索:Object.keys(Caelus)含推运7种/合盘3种/行运12/恒星2/ACG/赤纬/越界/映点/调和盘"
    return result
