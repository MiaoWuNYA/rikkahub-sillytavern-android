"""Route:  western astro"""
import json, sys, os, datetime
from ._shared import _js, _js_load, compute_jd, resolve_tz

# ===== 现代西洋占星（双引擎对照） =====
def _western_astro(year,month,day,hour,tz,lat,lon,minute=0,
                   partner_year=None,partner_month=None,partner_day=None,
                   partner_hour=None,partner_tz=None,partner_lat=None,partner_lon=None,
                   partner_minute=0):
    date_str=f"{year}-{month:02d}-{day}"
    tz_num = resolve_tz(tz)
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    jd = compute_jd(year, month, day, hour, minute, tz_num)
    hour_dec = hour + minute/60
    _js_load("natalengine-engine")
    try:
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
    except Exception as e:
        return {"system":"western_astrology","engine":"natalengine-js","error":str(e)}
    result={"system":"western_astrology","engine":"natalengine-js","natal":natal}
    _engs=["natalengine-js"]
    if isinstance(natal, dict) and 'error' in natal:
        _engs[-1]="natalengine-js(ERROR)"
    # Caelus: 全量现代技法+对照+行运+推运+增补
    try:
        _js_load("caelus-engine")
        today=datetime.datetime.now()
        today_jd=compute_jd(today.year,today.month,today.day,12,0,0)
        c=json.loads(_js("caelus-engine",
            "var e=new Caelus.Engine(Caelus.embeddedData);var jd=%s;var today=%s;"
            "var _lat=%f;var _lon=%f;var chart=e.chartAt(jd,_lat,_lon,{});"
            "var isDay=Caelus.isDayChart(e,jd,_lat,_lon);"
            "var ascIdx=Math.floor(chart.angles.asc/30);"
            "var fortuneLon=0;try{fortuneLon=Caelus.lots(e,jd,_lat,_lon).fortune.lon;}catch(e){}"
            "var ctx=Caelus.interpretationContext(chart);"
            "var sf=function(f){try{return f()}catch(ex){return null}};"
            "var isDayStr=isDay?'day':'night';"
            "var p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "var p10=p7.concat(['uranus','neptune','pluto']);"
            "var dScores={};p7.forEach(function(p){try{var b=chart.bodies[p];if(b)dScores[p]=Caelus.dignityScore(p,b.lon,isDayStr)}catch(ex){}});"
            "var dignOf={};p7.forEach(function(b){dignOf[b]=sf(function(){return Caelus.dignityOf(e,b,jd)})});"
            "var ph={};p7.forEach(function(b){ph[b]=sf(function(){return Caelus.pheno(e,b,jd)})});"
            # stations for all p7 planets
            "var allStations={};p7.forEach(function(b){allStations[b]=sf(function(){return Caelus.stations(e,b,jd,jd+365,5)})});"
            # out-of-bounds for all planets
            "var oob={};p10.forEach(function(b){oob[b]=sf(function(){return{outOfBounds:Caelus.outOfBounds(e,b,jd),margin:Caelus.outOfBoundsMargin(e,b,jd)}})});"
            # midpoints
            "var mp={};mp.sunMoon=sf(function(){return Caelus.midpointLon(chart.bodies.sun.lon,chart.bodies.moon.lon)});"
            "mp.ascMc=sf(function(){return Caelus.midpointLon(chart.angles.asc,chart.angles.mc)});"
            "mp.sunAsc=sf(function(){return Caelus.midpointLon(chart.bodies.sun.lon,chart.angles.asc)});"
            "mp.moonAsc=sf(function(){return Caelus.midpointLon(chart.bodies.moon.lon,chart.angles.asc)});"
            # returns for inner planets (Mercury, Venus, Mars, Jupiter, Saturn)
            "var ret={};['mercury','venus','mars','jupiter','saturn'].forEach(function(b){"
            "ret[b]=sf(function(){return Caelus.returns(e,b,jd,jd,jd+365*3,'tropical').slice(0,3)})});"
            # rise/set times for Sun and Moon (rise and set separately)
            "var rs={};rs.sun={rise:sf(function(){return Caelus.riseSet(e,'sun',jd,_lat,_lon,'rise',{searchDays:1})}),"
            "set:sf(function(){return Caelus.riseSet(e,'sun',jd,_lat,_lon,'set',{searchDays:1})})};"
            "rs.moon={rise:sf(function(){return Caelus.riseSet(e,'moon',jd,_lat,_lon,'rise',{searchDays:1})}),"
            "set:sf(function(){return Caelus.riseSet(e,'moon',jd,_lat,_lon,'set',{searchDays:1})})};"
            # zodiac crossings: next sign entry for each planet
            "var xings={};p10.forEach(function(b){"
            "xings[b]=sf(function(){"
            "var nextSign=Math.ceil(chart.bodies[b].lon/30)*30;"
            "return Caelus.crossings(e,b,nextSign,jd,jd+365,'tropical',1)"
            "})});"
            # element balance from ALL bodies (not just Ascendant)
            "var elems=['Fire','Earth','Air','Water'];"
            "var elCount=[0,0,0,0];var allB=p10.concat(['mean_node']);"
            "allB.forEach(function(b){try{var bc=chart.bodies[b];"
            "if(bc){var si=Math.floor(bc.lon/30)%%12;elCount[Math.floor(si/4)]++}}catch(e){}});"
            "var elementBalance={};elems.forEach(function(e,i){elementBalance[e]=elCount[i]});"
            "JSON.stringify({"
            "signature:Caelus.chartSignature(chart),elementBalance:elementBalance,patterns:Caelus.detectPatterns(chart),"
            "lots:Caelus.lots(e,jd,_lat,_lon),isDay:isDay,voidOfCourse:Caelus.voidOfCourse(e,jd),chartBrief:Caelus.chartBrief(ctx),"
            "firdaria:sf(function(){return Caelus.firdaria(isDay,jd)}),"
            "profections:Caelus.profection(ascIdx,jd,jd+365),"
            "primaryDirections:sf(function(){return Caelus.primaryDirections(e,jd,_lat,_lon)}),solarArc:Caelus.solarArc(e,jd,jd),"
            "progressedMoon:sf(function(){return Caelus.progressedLongitude(e,'moon',jd,jd+365*30)}),"
            "progressedSun:sf(function(){return Caelus.progressedLongitude(e,'sun',jd,jd+365*30)}),"
            "progressedOther:{mercury:sf(function(){return Caelus.progressedLongitude(e,'mercury',jd,jd+365*30)}),"
            "venus:sf(function(){return Caelus.progressedLongitude(e,'venus',jd,jd+365*30)}),"
            "mars:sf(function(){return Caelus.progressedLongitude(e,'mars',jd,jd+365*30)}),"
            "jupiter:sf(function(){return Caelus.progressedLongitude(e,'jupiter',jd,jd+365*30)}),"
            "saturn:sf(function(){return Caelus.progressedLongitude(e,'saturn',jd,jd+365*30)})},"
            "declinationAspects:sf(function(){return Caelus.declinationAspects(e,p7,jd,1)}),"
            "oob:oob,midpoints:mp,returns:ret,stations:allStations,riseSet:rs,"
            "starConjunctions:sf(function(){return e.starConjunctions(chart,{orb:.5,maxMag:2.5})}),"
            "antiscionSun:Caelus.antiscion(chart.bodies.sun.lon),antiscionMoon:Caelus.antiscion(chart.bodies.moon.lon),"
            "contraAntiscionSun:Caelus.contraAntiscion(chart.bodies.sun.lon),contraAntiscionMoon:Caelus.contraAntiscion(chart.bodies.moon.lon),"
            "planetaryHour:Caelus.planetaryHour(e,jd,_lat,_lon),housesWholeSign:Caelus.housesWholeSign(chart.angles.asc),"
            "harmonicChart:sf(function(){return Caelus.harmonicChart(e,jd,['sun','moon','venus','mars'],5)}),"
            "dignityScores:dScores,"
            "almuten:Caelus.almuten(chart.angles.asc,isDayStr),"
            "lunarPhases:Caelus.lunarPhases(e,jd,jd+30,8),"
            "eclipses:sf(function(){return{solar:Caelus.solarEclipses(e,jd,jd+365).slice(0,2),"
            "lunar:Caelus.lunarEclipses(e,jd,jd+365).slice(0,2)}}),"
            "pheno:ph,"
            "solarPhase:{mercury:sf(function(){return Caelus.solarPhase(e,'mercury',jd)}),"
            "venus:sf(function(){return Caelus.solarPhase(e,'venus',jd)}),"
            "mars:sf(function(){return Caelus.solarPhase(e,'mars',jd)})},"
            "dignityOf:dignOf,"
            "gauquelinSector:{sun:sf(function(){return Caelus.gauquelinSector(e,'sun',jd,_lat,_lon)}),"
            "moon:sf(function(){return Caelus.gauquelinSector(e,'moon',jd,_lat,_lon)}),"
            "mercury:sf(function(){return Caelus.gauquelinSector(e,'mercury',jd,_lat,_lon)}),"
            "venus:sf(function(){return Caelus.gauquelinSector(e,'venus',jd,_lat,_lon)}),"
            "mars:sf(function(){return Caelus.gauquelinSector(e,'mars',jd,_lat,_lon)}),"
            "jupiter:sf(function(){return Caelus.gauquelinSector(e,'jupiter',jd,_lat,_lon)}),"
            "saturn:sf(function(){return Caelus.gauquelinSector(e,'saturn',jd,_lat,_lon)})},"
            "vertex:chart.angles.vertex,eastPoint:chart.angles.eastPoint,"
            "parans:sf(function(){return Caelus.parans(e,jd,_lat,p7,30)}),"
            "transits:sf(function(){return Caelus.transitAspects(chart,e,today,{bodies:p7})}),"
            # 宫位头(Placidus) + 当前行运位置
            "cusps:chart.cusps,"
            "transitPositions:(function(){var tp={};p10.concat(['mean_node','chiron']).forEach(function(b){try{tp[b]={lon:e.longitude(b,today,{zodiac:'tropical'}),"
            "sign:['Aries','Taurus','Gemini','Cancer','Leo','Virgo','Libra','Scorpio','Sagittarius','Capricorn','Aquarius','Pisces'][Math.floor(e.longitude(b,today,{zodiac:'tropical'})/30)%%12]}}}catch(ex){});return tp})(),"
            "chiron:sf(function(){return{lon:e.longitude('chiron',jd,{zodiac:'tropical'}),"
            "sign:['Aries','Taurus','Gemini','Cancer','Leo','Virgo','Libra','Scorpio','Sagittarius','Capricorn','Aquarius','Pisces'][Math.floor(e.longitude('chiron',jd,{zodiac:'tropical'})/30)%%12]}}),"
            "astrocartography:sf(function(){return Caelus.astrocartography(e,jd,['sun','moon','venus','mars','jupiter','saturn'],-60,60,5)}),"
            "solarReturn:sf(function(){return Caelus.solarReturn(e,jd,jd,jd+365*3)}),"
            "lunarReturn:sf(function(){return Caelus.lunarReturn(e,jd,jd+27,jd+27*3)})"
            "})"
            %(jd,today_jd,lat,lon)))
        if isinstance(c, dict) and 'error' not in c:
            result["caelus"]=c; _engs.append("Caelus")
    except: pass
    # 合盘: 仅当传入 partner_year 时触发
    if partner_year is not None:
        try:
            partner_date=f"{partner_year}-{partner_month or month:02d}-{partner_day or day}"
            pt=resolve_tz(partner_tz, resolve_tz(tz))
            ph=partner_hour + partner_minute/60
            pl=float(partner_lat) if partner_lat else lat or 0
            pn=float(partner_lon) if partner_lon else lon or 0
            syn=json.loads(_js("natalengine-engine",
                "var a=NatalEngine.calculateAstrology('%s',%f,%f,%f,%f);"
                "var b=NatalEngine.calculateAstrology('%s',%f,%f,%f,%f);"
                "JSON.stringify(NatalEngine.compareAstrology(a,b))"
                % (date_str, hour_dec, tz_num, lat, lon,
                   partner_date, ph, pt, pl, pn)))
            if isinstance(syn, dict) and 'error' not in syn:
                result["synastry"]=syn
                _engs.append("合盘")
        except: pass
    result["engine"]="+".join(_engs)
    result["_hint"]=("NatalEngine:日月升+7星+元素+相位+合盘(synastry)。"
        "Caelus全量:bodies/cusps(Placidus)/angles/patterns/lots/空亡/映点/赤纬/越界(全10星)/恒星合相/尊贵/almuten/月相/行星留(全7星)/日月食/"
        "firdaria/profections/primaryDirections/parans/调和盘/行运方位相位+Aspects(当前)/行运行星位置(全13星含凯龙)+凯龙本命/ACG(简)/太阳返照/月亮返照/"
        "次级推运(全7星30年)/genericReturns(水金火木土3yr)/midpoints(日月+Asc+MC)/riseSet(日月)/signCrossings(全10星)。"
        "合盘:传partner_year/partner_month/partner_day/partner_hour触发。"
        "自探索:Object.keys(Caelus)")
    return result
