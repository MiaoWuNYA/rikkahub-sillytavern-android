"""Route:  western astro"""
import json, sys, os
from ._shared import _js, _js_load, compute_jd

# ===== 现代西洋占星（双引擎对照） =====
def _western_astro(year,month,day,hour,tz,lat,lon,minute=0):
    date_str=f"{year}-{month:02d}-{day}"
    tz_num = float(tz) if tz is not None else 8.0
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    jd = compute_jd(year, month, day, hour, minute, tz_num)
    hour_dec = hour + minute/60
    _js_load("natalengine-engine")
    natal=json.loads(_js("natalengine-engine",
        "function _c(o){if(o&&o.name){delete o.startMonth;delete o.startDay;delete o.endMonth;delete o.endDay;delete o.traits;delete o.shadow}return o}"
        "var r=NatalEngine.calculateAstrology('%s',%f,%f,%f,%f);"
        "if(r.sun&&r.sun.sign)_c(r.sun.sign);"
        "if(r.moon&&r.moon.sign)_c(r.moon.sign);"
        "if(r.rising&&r.rising.sign)_c(r.rising.sign);"
        "Object.values(r.planets||{}).forEach(function(p){if(p.sign)_c(p.sign)});"
        "Object.values(r.nodes||{}).forEach(function(n){if(n.sign)_c(n.sign)});"
        "if(r.midheaven&&r.midheaven.sign)_c(r.midheaven.sign);"
        "delete r.bigThree;delete r.summary;delete r.useEphemeris;delete r.hasLocation;delete r.allAspects;"
        "JSON.stringify(r)"
        % (date_str, hour_dec, tz_num, lat, lon)))
    result={"system":"western_astrology","engine":"natalengine-js","natal":natal}
    _engs=["natalengine-js"]
    if isinstance(natal, dict) and 'error' in natal:
        _engs[-1]="natalengine-js(ERROR)"
    # Caelus: 全量现代技法+对照+行运+推运
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
            "var isDayStr=isDay?'day':'night';"
            "var p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "var dScores={};p7.forEach(function(p){try{var b=chart.bodies[p];if(b)dScores[p]=Caelus.dignityScore(p,b.lon,isDayStr)}catch(ex){}});"
            "var dignOf={};p7.forEach(function(b){dignOf[b]=safe(function(){return Caelus.dignityOf(e,b,jd)})});"
            "var ph={};p7.forEach(function(b){ph[b]=safe(function(){return Caelus.pheno(e,b,jd)})});"
            "JSON.stringify({"
            "signature:Caelus.chartSignature(chart),patterns:Caelus.detectPatterns(chart),"
            "bodies:chart.bodies,cusps:chart.cusps,angles:chart.angles,"
            "lots:Caelus.lots(e,jd,_lat,_lon),isDay:isDay,voidOfCourse:Caelus.voidOfCourse(e,jd),chartBrief:Caelus.chartBrief(ctx),"
            "firdaria:safe(function(){return Caelus.firdaria(isDay,jd)}),"
            "profections:Caelus.profection(ascIdx,jd,jd+365),"
            "primaryDirections:safe(function(){return Caelus.primaryDirections(e,jd,_lat,_lon)}),solarArc:Caelus.solarArc(e,jd,jd),"
            "progressedMoon:safe(function(){return Caelus.progressedLongitude(e,'moon',jd,jd+365*30)}),"
            "progressedSun:safe(function(){return Caelus.progressedLongitude(e,'sun',jd,jd+365*30)}),"
            "declinationAspects:safe(function(){return Caelus.declinationAspects(e,['sun','moon','mercury','venus','mars','jupiter','saturn'],jd,1)}),"
            "outOfBoundsMoon:Caelus.outOfBounds(e,'moon',jd,1),"
            "starConjunctions:e.starConjunctions(chart,{orb:.5,maxMag:2.5}),"
            "antiscionSun:Caelus.antiscion(chart.bodies.sun.lon),antiscionMoon:Caelus.antiscion(chart.bodies.moon.lon),"
            "contraAntiscionSun:Caelus.contraAntiscion(chart.bodies.sun.lon),contraAntiscionMoon:Caelus.contraAntiscion(chart.bodies.moon.lon),"
            "planetaryHour:Caelus.planetaryHour(e,jd,_lat,_lon),housesWholeSign:Caelus.housesWholeSign(chart.angles.asc),"
            "harmonicChart:safe(function(){return Caelus.harmonicChart(e,jd,['sun','moon','venus','mars'],5)}),"
            "dignityScores:dScores,"
            "almuten:Caelus.almuten(chart.angles.asc,isDayStr),"
            "lunarPhases:Caelus.lunarPhases(e,jd,jd+30,8),"
            "stations:safe(function(){return Caelus.stations(e,'mars',jd,jd+365,5)}),"
            "eclipses:safe(function(){return{solar:Caelus.solarEclipses(e,jd,jd+365).slice(0,2),lunar:Caelus.lunarEclipses(e,jd,jd+365).slice(0,2)}}),"
            "pheno:ph,"
            "solarPhase:{mercury:safe(function(){return Caelus.solarPhase(e,'mercury',jd)}),venus:safe(function(){return Caelus.solarPhase(e,'venus',jd)}),mars:safe(function(){return Caelus.solarPhase(e,'mars',jd)})},"
            "dignityOf:dignOf,"
            "gauquelinSector:{sun:safe(function(){return Caelus.gauquelinSector(e,'sun',jd,_lat,_lon)}),moon:safe(function(){return Caelus.gauquelinSector(e,'moon',jd,_lat,_lon)}),mercury:safe(function(){return Caelus.gauquelinSector(e,'mercury',jd,_lat,_lon)}),venus:safe(function(){return Caelus.gauquelinSector(e,'venus',jd,_lat,_lon)}),mars:safe(function(){return Caelus.gauquelinSector(e,'mars',jd,_lat,_lon)}),jupiter:safe(function(){return Caelus.gauquelinSector(e,'jupiter',jd,_lat,_lon)}),saturn:safe(function(){return Caelus.gauquelinSector(e,'saturn',jd,_lat,_lon)})},"
            "vertexEastPoint:safe(function(){return Caelus.vertexEastPoint(e,jd,_lat,_lon)}),"
            "parans:safe(function(){return Caelus.parans(e,jd,_lat,p7,30)}),"
            "transits:safe(function(){return Caelus.transitAspects(chart,e,jd+45,{bodies:p7})}),"
            "astrocartography:safe(function(){return Caelus.astrocartography(e,jd,['sun','moon','venus','mars','jupiter','saturn'],-60,60,5)}),"
            "solarReturn:safe(function(){return Caelus.solarReturn(e,jd,jd+365,jd+365*3)}),"
            "lunarReturn:safe(function(){return Caelus.lunarReturn(e,jd,jd+27,jd+27*3)})"
            "})"
            %(jd,lat,lon)))
        if isinstance(c, dict) and 'error' not in c:
            result["caelus"]=c; _engs.append("Caelus")
    except: pass
    result["engine"]="+".join(_engs)
    result["_hint"]=("NatalEngine已返回日月升+7星+元素+相位+合盘。"
        "Caelus:bodies/cusps/angles/patterns/lots/空亡/映点/赤纬/越界/恒星合相/尊贵/almuten/月相/行星留/日月食/"
        "firdaria/profections/primaryDirections/parans/调和盘/行运(90d)/ACG(简)/太阳返照/月亮返照。"
        "新增:progressedMoon+progressedSun(次级推运30年)。"
        "自探索:Object.keys(Caelus)含returns/searchConfigurations/riseSet/crossings/"
        "synastryAspects/compositePlacements/davisonParams/skyView/electional")
    return result
