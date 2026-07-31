"""Route:  western astro"""
import json, sys, os, datetime
import time as _time
from ._shared import _js, _js_load, compute_jd, resolve_tz_checked, convert_caelus_dates, group_caelus

# Caelus 输出按源码模块分组（chart/derived/events/eclipses/firdaria/profections/directions/relational/astrocartography）
_CAELUS_GROUPS = {
    # 本命盘基础 (chart.js / brief.js / compiler.js)
    "signature": "chart", "elementBalance": "chart", "patterns": "chart", "lots": "chart",
    "isDay": "chart", "chartBrief": "chart", "housesWholeSign": "chart", "cusps": "chart",
    "vertex": "chart", "eastPoint": "chart", "planetHouses": "chart", "aspects": "chart",
    "planetDignities": "chart", "planetPositions": "chart", "angles": "chart",
    # 行星深度分析 (derived.js / electional.js 本命因子)
    "dignityScores": "bodies", "dignityOf": "bodies", "pheno": "bodies", "oob": "bodies",
    "midpoints": "bodies", "solarPhase": "bodies", "antiscionSun": "bodies",
    "antiscionMoon": "bodies", "contraAntiscionSun": "bodies", "contraAntiscionMoon": "bodies",
    "birthPlanetaryHour": "bodies", "almuten": "bodies", "natalDeclinationAspects": "bodies",
    "starConjunctions": "bodies", "chiron": "bodies", "lilith": "bodies", "natalVoidOfCourse": "bodies",
    # 天象事件 (events.js / eclipses.js)
    "lunarPhases": "events", "eclipses": "events", "crossings": "events",
    "stations": "events", "riseSet": "events",
    # 推运 (firdaria.js / profections.js / directions.js / derived.js)
    "firdaria": "progressions", "profections": "progressions", "solarArc": "progressions",
    "progressedMoon": "progressions", "progressedSun": "progressions",
    "progressedOther": "progressions", "primaryDirections": "progressions",
    "solarReturn": "progressions", "lunarReturn": "progressions",
    # 行运 (relational.js)
    "transits": "transits", "transitPositions": "transits",
    # 调和盘 (derived.js harmonicChart)
    "harmonicChart": "harmonics",
    # 占星地图 (astrocartography.js)
    "astrocartography": "astrocartography",
}
_CAELUS_ORDER = ["chart", "bodies", "events", "progressions", "transits", "harmonics", "astrocartography"]

# ===== 现代西洋占星（双引擎对照） =====
def _western_astro(year,month,day,hour,tz,lat,lon,minute=0,
                   partner_year=None,partner_month=None,partner_day=None,
                   partner_hour=None,partner_tz=None,partner_lat=None,partner_lon=None,
                   partner_minute=0):
    try:
        tz_num, _ = resolve_tz_checked(tz, at=(year, month, day, hour, minute))
    except ValueError as e:
        return {"system": "western_astrology", "error": f"时区参数错误: {e}"}
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    jd = compute_jd(year, month, day, hour, minute, tz_num)
    result={"system":"western_astrology","engine":"caelus-js"}
    _engs=["caelus-js"]
    # Caelus: 分阶段JS评估 (全量现代技法+对照+行运+推运+增补)
    caelus_data = {}
    caelus_errors = []
    _caelus_start = _time.monotonic()
    _caelus_budget = 45.0  # Caelus 总预算秒数（QuickJS 实测约 Node 的 30-70 倍），超预算跳过剩余阶段
    try:
        _js_load("caelus-engine")
        today = datetime.datetime.now(datetime.timezone.utc)
        today_jd = compute_jd(today.year, today.month, today.day, today.hour, today.minute, 0)

        def _cp(name, js):
            try:
                if _time.monotonic() - _caelus_start > _caelus_budget:
                    caelus_errors.append(f"{name}: skipped (Caelus 预算 {_caelus_budget:.0f}s 已用尽)")
                    return
                _js_load("caelus-engine")  # re-init guard — no-op when cached, re-loads after timeout
                raw = _js("caelus-engine", js)
                p = json.loads(raw)
                if isinstance(p, dict) and 'error' not in p:
                    caelus_data.update(p)
                elif isinstance(p, dict) and 'error' in p:
                    caelus_errors.append(f"{name}: {p['error']}")
                else:
                    caelus_errors.append(f"{name}: type={type(p).__name__}")
            except Exception as e:
                caelus_errors.append(f"{name}: {e}")

        # Phase 1 (~5s): Engine init + chart + fast scalar data
        _cp("p1",
            "var __cr;try{"
            "var e=new Caelus.Engine(Caelus.embeddedData);var jd=%s;var today=%s;"
            "var _lat=%f;var _lon=%f;"
            "var chart=e.chartAt(jd,_lat,_lon,{});"
            "var isDay=Caelus.isDayChart(e,jd,_lat,_lon);"
            "var ascIdx=Math.floor(chart.angles.asc/30);"
            "var ctx=Caelus.interpretationContext(chart);"
            "var sf=function(f){try{return f()}catch(ex){return null}};"
            "var isDayStr=isDay?'day':'night';"
            "var p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "var p10=p7.concat(['uranus','neptune','pluto']);"
            "var _allBodies=p10.concat(['mean_node','chiron']);"
            "var _elBodies=p10.concat(['chiron']);"
            "var _elems=['Fire','Earth','Air','Water'];var _elCount=[0,0,0,0];"
            "_elBodies.forEach(function(b){try{var bc=chart.bodies[b];"
            "if(bc){var si=Math.floor(bc.lon/30)%%12;_elCount[si%%4]++}}catch(ex){}});"
            "var elementBalance={};_elems.forEach(function(e,i){elementBalance[e]=_elCount[i]});"
            "var _ph={};_allBodies.forEach(function(b){try{var bc=chart.bodies[b];if(bc)_ph[b]=bc.house}catch(ex){}});"
            "var _pd={};_allBodies.forEach(function(b){try{var bc=chart.bodies[b];if(bc)_pd[b]=bc.dignities}catch(ex){}});"
            "var _pos={};_allBodies.forEach(function(b){try{var bc=chart.bodies[b];"
            "if(bc)_pos[b]={lon:bc.lon,sign:bc.sign,signDeg:bc.signDeg,house:bc.house,retrograde:bc.retrograde,speed:bc.speed}}catch(ex){}});"
            "__cr=JSON.stringify({"
            "signature:Caelus.chartSignature(chart),elementBalance:elementBalance,"
            "patterns:Caelus.detectPatterns(chart),"
            "lots:Caelus.lots(e,jd,_lat,_lon),isDay:isDay,"
            "natalVoidOfCourse:Caelus.voidOfCourse(e,jd),"
            "chartBrief:Caelus.chartBrief(ctx),"
            "housesWholeSign:Caelus.housesWholeSign(chart.angles.asc*(Math.PI/180)),"
            "cusps:chart.cusps,"
            "vertex:chart.angles.vertex,"
            "eastPoint:chart.angles.eastPoint,"
            "planetHouses:_ph,aspects:chart.aspects,planetDignities:_pd,"
            "planetPositions:_pos,angles:chart.angles,"
            "lilith:{mean:sf(function(){return e.longitude('mean_lilith',jd,{zodiac:'tropical'})}),"
            "true:sf(function(){return e.longitude('true_lilith',jd,{zodiac:'tropical'})})}"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p1:'+ex.message})};__cr"
            % (jd, today_jd, lat, lon))

        # Phase 2a (~8s): Per-planet computations (dignity, pheno, oob, midpoints, solarPhase, antiscion, planetaryHour, almuten)
        _cp("p2a",
            "var __cr;try{"
            "var jd=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "p10=p7.concat(['uranus','neptune','pluto']);"
            "ascIdx=Math.floor(chart.angles.asc/30);}"
            "var dScores={};p7.forEach(function(p){try{var b=chart.bodies[p];if(b)dScores[p]=Caelus.dignityScore(p,b.lon,isDayStr)}catch(ex){}});"
            "var dignOf={};p7.forEach(function(b){dignOf[b]=sf(function(){return Caelus.dignityOf(e,b,jd)})});"
            "var ph={};p7.forEach(function(b){ph[b]=sf(function(){return Caelus.pheno(e,b,jd)})});"
            "var oob={};p10.forEach(function(b){oob[b]=sf(function(){return{outOfBounds:Caelus.outOfBounds(e,b,jd),margin:Caelus.outOfBoundsMargin(e,b,jd)}})});"
            "var mp={};mp.sunMoon=sf(function(){return Caelus.midpointLon(chart.bodies.sun.lon,chart.bodies.moon.lon)});"
            "mp.ascMc=sf(function(){return Caelus.midpointLon(chart.angles.asc,chart.angles.mc)});"
            "mp.sunAsc=sf(function(){return Caelus.midpointLon(chart.bodies.sun.lon,chart.angles.asc)});"
            "mp.moonAsc=sf(function(){return Caelus.midpointLon(chart.bodies.moon.lon,chart.angles.asc)});"
            "var _sp={};p7.forEach(function(b){_sp[b]=sf(function(){return Caelus.solarPhase(e,b,jd)})});"
            "__cr=JSON.stringify({"
            "dignityScores:dScores,dignityOf:dignOf,pheno:ph,oob:oob,midpoints:mp,"
            "solarPhase:_sp,"
            "antiscionSun:Caelus.antiscion(chart.bodies.sun.lon),"
            "antiscionMoon:Caelus.antiscion(chart.bodies.moon.lon),"
            "contraAntiscionSun:Caelus.contraAntiscion(chart.bodies.sun.lon),"
            "contraAntiscionMoon:Caelus.contraAntiscion(chart.bodies.moon.lon),"
            "birthPlanetaryHour:Caelus.planetaryHour(e,jd,_lat,_lon),"
            "almuten:Caelus.almuten(chart.angles.asc,isDayStr)"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p2a:'+ex.message})};__cr"
            % (jd, lat, lon, jd, lat, lon, jd, lat, lon))

        # Phase 2b (~8s): Star conjunctions, declination aspects, chiron position
        _cp("p2b",
            "var __cr;try{"
            "var jd=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "p10=p7.concat(['uranus','neptune','pluto']);"
            "ascIdx=Math.floor(chart.angles.asc/30);}"
            "__cr=JSON.stringify({"
            "natalDeclinationAspects:sf(function(){return Caelus.declinationAspects(e,p7,jd,1)}),"
            "starConjunctions:sf(function(){return e.starConjunctions(chart,{orb:.5,maxMag:2.5})}),"
            "chiron:{lon:e.longitude('chiron',jd,{zodiac:'tropical'}),"
            "sign:['Aries','Taurus','Gemini','Cancer','Leo','Virgo','Libra','Scorpio','Sagittarius','Capricorn','Aquarius','Pisces'][Math.floor(e.longitude('chiron',jd,{zodiac:'tropical'})/30)%%12]}"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p2b:'+ex.message})};__cr"
            % (jd, lat, lon, jd, lat, lon, jd, lat, lon))

        # Phase 3a (~8s): Fast timing (firdaria/profections/solarArc/progressions/lunarPhases/eclipses/riseSet/crossings 90d)
        _cp("p3a",
            "var __cr;try{"
            "var jd=%s;var today=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "p10=p7.concat(['uranus','neptune','pluto']);"
            "ascIdx=Math.floor(chart.angles.asc/30);}"
            "var _xings={};p10.forEach(function(b){"
            "_xings[b]=sf(function(){"
            "var ns=Math.ceil(chart.bodies[b].lon/30)*30;"
            "return Caelus.crossings(e,b,ns,today,today+60,'tropical',1)"
            "})});"
            "__cr=JSON.stringify({"
            "firdaria:sf(function(){return Caelus.firdaria(isDay,jd)}),"
            "profections:Caelus.profection(ascIdx,jd,today),"
            "solarArc:Caelus.solarArc(e,jd,today),"
            "progressedMoon:sf(function(){return Caelus.progressedLongitude(e,'moon',jd,today)}),"
            "progressedSun:sf(function(){return Caelus.progressedLongitude(e,'sun',jd,today)}),"
            "progressedOther:{mercury:sf(function(){return Caelus.progressedLongitude(e,'mercury',jd,today)}),"
            "venus:sf(function(){return Caelus.progressedLongitude(e,'venus',jd,today)}),"
            "mars:sf(function(){return Caelus.progressedLongitude(e,'mars',jd,today)}),"
            "jupiter:sf(function(){return Caelus.progressedLongitude(e,'jupiter',jd,today)}),"
            "saturn:sf(function(){return Caelus.progressedLongitude(e,'saturn',jd,today)})},"
            "lunarPhases:Caelus.lunarPhases(e,today,today+30,8),"
            "eclipses:sf(function(){return{solar:Caelus.solarEclipses(e,today,today+180).slice(0,2),"
            "lunar:Caelus.lunarEclipses(e,today,today+180).slice(0,2)}}),"
            "riseSet:{sun:{rise:sf(function(){return Caelus.riseSet(e,'sun',jd,_lat,_lon,'rise',{searchDays:1})}),"
            "set:sf(function(){return Caelus.riseSet(e,'sun',jd,_lat,_lon,'set',{searchDays:1})})},"
            "moon:{rise:sf(function(){return Caelus.riseSet(e,'moon',jd,_lat,_lon,'rise',{searchDays:1})}),"
            "set:sf(function(){return Caelus.riseSet(e,'moon',jd,_lat,_lon,'set',{searchDays:1})})}},"
            "crossings:_xings"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p3a:'+ex.message})};__cr"
            % (jd, today_jd, lat, lon, jd, lat, lon, jd, lat, lon))

        # Phase 3b (~5s): Stations(90d) + harmonicChart (去掉 returns 30yr: 单技法 29s+, QuickJS 不可行)
        _cp("p3b",
            "var __cr;try{"
            "var jd=%s;var today=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "p10=p7.concat(['uranus','neptune','pluto']);"
            "ascIdx=Math.floor(chart.angles.asc/30);}"
            "var _stations={};p7.forEach(function(b){_stations[b]=sf(function(){return Caelus.stations(e,b,today,today+60,5)})});"
            "__cr=JSON.stringify({"
            "stations:_stations,"
            "harmonicChart:sf(function(){return Caelus.harmonicChart(e,jd,['sun','moon','venus','mars'],5)})"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p3b:'+ex.message})};__cr"
            % (jd, today_jd, jd, lat, lon, jd, lat, lon))

        # Phase 4a (~5s): primaryDirections + transits + transitPositions + solarReturn + lunarReturn
        _cp("p4a",
            "var __cr;try{"
            "var jd=%s;var today=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "p10=p7.concat(['uranus','neptune','pluto']);"
            "ascIdx=Math.floor(chart.angles.asc/30);}"
            "var _tp={};p10.concat(['mean_node','chiron']).forEach(function(b){try{"
            "_tp[b]={lon:e.longitude(b,today,{zodiac:'tropical'}),"
            "sign:['Aries','Taurus','Gemini','Cancer','Leo','Virgo','Libra','Scorpio','Sagittarius','Capricorn','Aquarius','Pisces'][Math.floor(e.longitude(b,today,{zodiac:'tropical'})/30)%%12]}}catch(ex){}});"
            "__cr=JSON.stringify({"
            "primaryDirections:sf(function(){return Caelus.primaryDirections(e,jd,_lat,_lon)}),"
            "transits:sf(function(){return Caelus.transitAspects(chart,e,today,{bodies:p7})}),"
            "transitPositions:_tp,"
            "solarReturn:sf(function(){return Caelus.solarReturn(e,jd,today,today+366)}),"
            "lunarReturn:sf(function(){return Caelus.lunarReturn(e,jd,today,today+28)})"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p4a:'+ex.message})};__cr"
            % (jd, today_jd, lat, lon, jd, lat, lon, jd, lat, lon))

        # Phase 4b (~1s): astrocartography (去掉 parans/gauquelinSector: QuickJS 上分别 8-17s/13-26s)
        _cp("p4b",
            "var __cr;try{"
            "var jd=%s;var today=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "p10=p7.concat(['uranus','neptune','pluto']);"
            "ascIdx=Math.floor(chart.angles.asc/30);}"
            "__cr=JSON.stringify({"
            "astrocartography:sf(function(){return Caelus.astrocartography(e,jd,['sun','moon','venus','mars','jupiter','saturn'],-60,60,5)}),"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p4b:'+ex.message})};__cr"
            % (jd, today_jd, lat, lon, jd, lat, lon, jd, lat, lon))

        if caelus_data:
            result["caelus"] = group_caelus(convert_caelus_dates(caelus_data), _CAELUS_GROUPS, _CAELUS_ORDER)
            _engs.append("Caelus")
        if caelus_errors:
            result["caelus_error"] = "; ".join(caelus_errors)
    except Exception as e:
        result["caelus_error"] = f"caelus_exception: {e}"
        import traceback; result["caelus_tb"] = traceback.format_exc()
    # 合盘: 仅当传入 partner_year 时触发
    if partner_year is not None:
        try:
            pt, _ = resolve_tz_checked(
                partner_tz, tz_num,
                at=(partner_year, partner_month or month, partner_day or day,
                    hour if partner_hour is None else partner_hour,
                    partner_minute or 0))
            pl=float(partner_lat) if partner_lat else lat or 0
            pn=float(partner_lon) if partner_lon else lon or 0
            jdB = compute_jd(partner_year, partner_month or month, partner_day or day,
                             hour if partner_hour is None else partner_hour,
                             partner_minute or 0, pt)
            _js_load("caelus-engine")
            raw = _js("caelus-engine",
                "var __cr;try{"
                "var jdB=%s;var latB=%s;var lonB=%s;"
                "if(typeof e==='undefined'){"
                "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});}"
                "var chartB=e.chartAt(jdB,latB,lonB,{});"
                "var sf=function(fn){try{return fn()}catch(ex){return null}};"
                "var dv=Caelus.davisonParams(%s,%s,%s,%s,%s,%s);"
                "__cr=JSON.stringify({"
                "aspects:sf(function(){return Caelus.synastryAspects(chart,chartB)}),"
                "overlays:sf(function(){return Caelus.synastryOverlays(chart,chartB)}),"
                "composite:sf(function(){return Caelus.compositePlacements(e,%s,%s)}),"
                "davison:sf(function(){var dc=e.chartAt(dv[0],dv[1],dv[2],{});var o={};"
                "for(var k in dc.bodies){var p=dc.bodies[k];if(p)o[k]={lon:p.lon,sign:p.sign,signDeg:p.signDeg,house:p.house}};"
                "return{midJd:dv[0],midLat:dv[1],midLon:dv[2],chart:o}})"
                "})"
                "}catch(ex){__cr=JSON.stringify({error:'synastry:'+ex.message})};__cr"
                % (jdB, pl, pn, jd, lat, lon,
                   jd, jdB, lat, pl, lon, pn,
                   jd, jdB))
            syn = json.loads(raw)
            if isinstance(syn, dict) and 'error' not in syn:
                result["synastry"] = syn
                _engs.append("Caelus合盘")
            elif isinstance(syn, dict):
                result["synastry_error"] = syn.get("error")
        except Exception as e:
            result["synastry_error"] = str(e)
    result["engine"]="+".join(_engs)
    result["_hint"]=("Caelus 单引擎(本命+合盘, 已移除重复的 NatalEngine):"
        "planetPositions(全13体本命位置)/angles/cusps(Placidus)/aspects/patterns/lots/natalVoidOfCourse(本命空亡)/映点/natalDeclinationAspects(本命赤纬)/birthPlanetaryHour(出生时主星)/越界(全10星)/恒星合相/尊贵/almuten/月相/行星留(全7星60d)/日月食(180d)/"
        "firdaria/profections/primaryDirections/调和盘/行运方位相位+Aspects(当前)/行运行星位置(全13星含凯龙)+凯龙本命/ACG(简)/太阳返照/月亮返照/"
        "次级推运(全7星,推至当前年龄)/midpoints(日月+Asc+MC)/riseSet(出生后首次日月升降)/signCrossings(全10星60d)。"
        "caelus 已按 chart/bodies/events/progressions/transits/harmonics/astrocartography 分组返回。"
        "注: Caelus 已限预算(45s); 重型技法(行星回归30年/Parans/高魁林区)在移动端 QuickJS 上过慢已移除;"
        "换座/行星留窗口为60天, 太阳返照1年/月亮返照1月, 以保证全阶段在预算内返回。"
        "合盘:传partner_year/partner_month/partner_day/partner_hour触发。"
        "自探索:Object.keys(Caelus)")
    return result
