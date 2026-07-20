"""Route:  vedic"""
import json, sys, os
from ._shared import _js, _js_load, compute_jd

# ===== 吠陀 =====
def _vedic(year,month,day,hour,tz,lat=None,lon=None,minute=0,depth="standard"):
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    if isinstance(tz, str): tz = float(tz)
    date_str=f"{year}-{month:02d}-{day}"
    tz_vd = float(tz) if tz is not None else 8.0
    tz_vd_sign = "+" if tz_vd >= 0 else "-"
    tz_vd_abs = abs(tz_vd)
    tz_vd_str = f"{tz_vd_sign}{int(tz_vd_abs):02d}:{int((tz_vd_abs - int(tz_vd_abs)) * 60 + 0.5):02d}"
    iso_vd_date = f"{date_str}T{hour:02d}:{minute:02d}:00{tz_vd_str}"
    jd_vd = compute_jd(year, month, day, hour, minute, tz_vd)
    hour_dec = hour + minute/60

    result={"system":"vedic"}
    # ===== 默认主力: PyJHora (Python/Chaquopy) =====
    try:
        from jhora import const, utils
        from jhora.panchanga import drik
        from jhora.horoscope.chart import house, strength, raja_yoga, yoga, dosha, ashtakavarga, arudhas, charts
        from jhora.horoscope.dhasa.graha import vimsottari
        from jhora.horoscope.prediction.general import get_prediction_details, get_prediction_resources
        from jhora.horoscope.prediction.longevity import life_span_range
        from jhora.horoscope.transit import tajaka as _tj
        from jhora.horoscope.transit import tajaka_yoga as _tjy
        from jhora.horoscope.transit import saham as _sh
        from jhora.panchanga.eclipse import next_solar_eclipse, next_lunar_eclipse
        place=drik.Place("loc",lat or 0,lon or 0,float(tz) if tz and str(tz).lstrip('-+').replace('.','',1).isdigit() else 0)
        jd_local=utils.julian_day_number(drik.Date(year,month,day),(hour,minute,0))
        # 1. 排盘
        pp=drik.dhasavarga(jd_local,place,1)
        asc_raw=drik.ascendant(jd_local,place)
        asc_house,asc_long=drik.dasavarga_from_long(asc_raw[0]*30+asc_raw[1],1)
        pp+=[[const._ascendant_symbol,(asc_house,asc_long)]]
        p_to_h={p:h for p,(h,_) in pp}
        h_to_p=utils.get_house_planet_list_from_planet_positions(pp)
        result["pyjhora"]={"planets":pp[:9],"lagna":{"rasi":asc_raw[0],"deg":asc_raw[1],"nak":asc_raw[2],"pada":asc_raw[3]}}
        result["engine"]="PyJHora"
        # 2. Panchanga
        try: result["predictions"]=get_prediction_details(jd_local,place)
        except: pass
        try: result["longevity"]=life_span_range(jd_local,place)
        except: pass
        try: result["tajaka"]={"varsha_pravesh":_tj.varsha_pravesh(jd_local,place),"annual_chart":_tj.annual_chart(jd_local,place),"lord_of_year":_tj.lord_of_the_year(jd_local,place,0)}
        except: pass
        try: result["tajaka_yoga"]={"ishkavala":_tjy.ishkavala_yoga(p_to_h),"induvara":_tjy.induvara_yoga(p_to_h),"nakta":_tjy.get_nakta_yoga_planet_triples(pp),"ithasala":_tjy.get_ithasala_yoga_planet_pairs(pp),"eesarpha":_tjy.get_eesarpha_yoga_planet_pairs(pp),"yamaya":_tjy.get_yamaya_yoga_planet_triples(pp),"manahoo":_tjy.get_manahoo_yoga_planet_pairs(pp),"kamboola":_tjy.get_kamboola_yoga_planet_pairs(pp)}
        except: pass
        try: result["saham"]={"punya":_sh.punya_saham(pp),"vidya":_sh.vidya_saham(pp),"yasas":_sh.yasas_saham(pp),"mitra":_sh.mitra_saham(pp),"mahatmaya":_sh.mahatmaya_saham(pp),"asha":_sh.asha_saham(pp),"samartha":_sh.samartha_saham(pp),"bhratri":_sh.bhratri_saham(pp),"gaurava":_sh.gaurava_saham(pp),"pithri":_sh.pithri_saham(pp),"rajya":_sh.rajya_saham(pp),"maathri":_sh.maathri_saham(pp),"puthra":_sh.puthra_saham(pp),"jeeva":_sh.jeeva_saham(pp),"karma":_sh.karma_saham(pp),"roga":_sh.roga_saham(pp),"kali":_sh.kali_saham(pp),"sastra":_sh.sastra_saham(pp),"bandhu":_sh.bandhu_saham(pp)}
        except: pass
        try: result["eclipses"]={"next_solar":next_solar_eclipse(jd_local),"next_lunar":next_lunar_eclipse(jd_local)}
        except: pass
        try: result["panchanga"]={"tithi":drik.tithi(jd_local,place),"nakshatra":drik.nakshatra(jd_local,place),"yogam":drik.yogam(jd_local,place),"karana":drik.karana(jd_local,place),"vaara":drik.vaara(jd_local,place),"sunrise":drik.sunrise(jd_local,place),"sunset":drik.sunset(jd_local,place)}
        except: pass
        # 3. 宫位分析
        try: result["houses"]={"planets_in_quadrants":house.get_planets_in_quadrants(p_to_h),"planets_in_trines":house.get_planets_in_trines(p_to_h),"planets_in_dushthanas":house.get_planets_in_dushthanas(p_to_h)}
        except: pass
        # 4. Shadbala
        try: result["shadbala"]=strength.shad_bala(jd_local,place)
        except: pass
        try: result["bhava_bala"]=strength.bhava_bala(jd_local,place)
        except: pass
        # 5. Ashtakavarga
        try: result["ashtakavarga"]=ashtakavarga.get_ashtaka_varga(p_to_h)
        except: pass
        # 6. Raja Yoga
        try:
            ry_data,ry_found,ry_total=raja_yoga.get_raja_yoga_details(jd_local,place)
            result["raja_yoga"]={"yogas":ry_data,"found":ry_found,"total_checked":ry_total}
        except: pass
        try:
            yd_data,yd_found,yd_total=yoga.get_yoga_details(jd_local,place)
            result["yoga_details"]={"yogas":yd_data,"found":yd_found,"total_checked":yd_total}
        except: pass
        # 7. Dosha 全7项
        try:
            moon_nak=asc_raw[2] if asc_raw else 0
            result["dosha"]={
                "manglik":dosha.manglik(pp),
                "kala_sarpa":dosha.kala_sarpa(pp),
                "guru_chandala":dosha.guru_chandala_dosha(pp),
                "pitru_dosha":dosha.pitru_dosha(pp),
                "kalathra":dosha.kalathra(pp),
                "ganda_moola":dosha.ganda_moola(moon_nak),
                "shrapit":dosha.shrapit(pp),
            }
        except: pass
        # 8. Arudha
        try: result["arudha"]=arudhas.bhava_arudhas_from_planet_positions(pp)
        except: pass
        # 9. Vimshottari Dasha
        try: result["vimshottari"]=vimsottari.get_vimsottari_dhasa_bhukthi(jd_local,place)
        except: pass
        # 10. House 关键分析
        try:
            result["house_analysis"]={
                "chara_karakas":house.chara_karakas(pp),
                "marakas":house.marakas(h_to_p),
                "functional_benefic":house.functional_benefic_lord_houses(asc_house),
                "functional_malefic":house.functional_malefic_lord_houses(asc_house),
                "argala":house.get_argala(p_to_h),
                "brahma":house.brahma(pp),
                "rudra":house.rudra(pp),
                "yoga_kaaraka":{str(p):house.is_yoga_kaaraka(asc_house,p,h) for p,(h,_) in pp if p!=const._ascendant_symbol},
            }
        except: pass
        # 11. 行星状态
        try:
            result["planet_status"]={
                "combustion":charts.planets_in_combustion(pp),
                "retrograde":charts.planets_in_retrograde(pp),
                "marana_karaka_sthana":charts.get_planets_in_marana_karaka_sthana(pp),
                "kp_lords":charts.get_KP_lords_from_planet_positions(pp),
            }
        except: pass
        # 12. 全部分盘 (drik.dhasavarga 通用函数, 已验证)
        try:
            for dnum, dkey in [(2,"d2"),(3,"d3"),(4,"d4"),(7,"d7"),(9,"d9"),(10,"d10"),
                                (12,"d12"),(16,"d16"),(20,"d20"),(24,"d24"),(27,"d27"),
                                (30,"d30"),(40,"d40"),(45,"d45"),(60,"d60")]:
                try: result[f"varga_{dkey}"]=drik.dhasavarga(jd_local,place,dnum)
                except: pass
        except: pass
    except Exception as e:
        result["pyjhora_error"]=str(e)
        result["engine"]=""
    # ===== 辅助: NodeJhora =====
    if lat and lon:
        _js_load("node-jhora-engine")
        nj=_js("node-jhora-engine",
                "try{"
                "var dt=NodeJhora.DateTime.fromISO('%s');"
                "var nj=NodeJhora.EphemerisEngine.getInstance();"
                "var planets=nj.getPlanets(dt,{latitude:%f,longitude:%f},{ayanamsaOrder:1});"
                "if(!planets||!planets.length){JSON.stringify({error:'getPlanets returned empty'})}else{"
                "var jd=nj.julday(dt);"
                "var houses=nj.getHouses(jd,%f,%f,'W',true);"
                "var moon=planets.find(function(p){return p.id===1})||{};"
                "var sun=planets.find(function(p){return p.id===0})||{};"
                "var idToName={0:'Sun',1:'Moon',2:'Mercury',3:'Venus',4:'Mars',5:'Jupiter',6:'Saturn',10:'Rahu',99:'Ketu'};"
                "var chart={planets:planets.map(function(p){return{name:idToName[p.id]||'Unknown',longitude:p.longitude}}),houses:{ascendant:houses.ascendant}};"
                "var charaKarakas=NodeJhora.JaiminiCore?NodeJhora.JaiminiCore.calculateCharaKarakas(planets):null;"
                "var atmakaraka=charaKarakas?charaKarakas[0]:null;"
                "var ashtakavarga=NodeJhora.Ashtakavarga?NodeJhora.Ashtakavarga.calculateSAV(planets):null;"
                "var yogini=NodeJhora.YoginiDasha&&moon.longitude?NodeJhora.YoginiDasha.calculate(moon.longitude,dt,50):null;"
                "var yogas=NodeJhora.YogaEngine?NodeJhora.YogaEngine.findYogas(chart,NodeJhora.YOGA_LIBRARY||[]):null;"
                "JSON.stringify({planets:planets,houses:houses,moonLon:moon.longitude||0,sunLon:sun.longitude||0,charaKarakas:charaKarakas,atmakaraka:atmakaraka,ashtakavarga:ashtakavarga,yogini:yogini,yogas:yogas})}"
                "}catch(e){JSON.stringify({error:e.message})}" % (
                    iso_vd_date, lat, lon, lat, lon))
        result["nodejhora"]=nj
        result["engine"]+="+NodeJhora"
        # NodeJhora 顶层函数: Panchanga/Vimshottari/DashaBalance/Varga/NarayanaDasha
        try:
            njt=_js("node-jhora-engine",
                "try{"
                "var dt=NodeJhora.DateTime.fromISO('%s');"
                "var nj=NodeJhora.EphemerisEngine.getInstance();"
                "var planets=nj.getPlanets(dt,{latitude:%f,longitude:%f},{ayanamsaOrder:1});"
                "if(planets&&planets.length){"
                "var moon=planets.find(function(p){return p.id===1});"
                "var sun=planets.find(function(p){return p.id===0});"
                "if(moon&&sun){"
                "var chart2={planets:planets.map(function(p){return{name:p.id,longitude:p.longitude}}),houses:{}};"
                "JSON.stringify({"
                "panchanga:NodeJhora.calculatePanchanga(sun.longitude,moon.longitude,dt,6),"
                "vimshottari:NodeJhora.generateVimshottari(dt,moon.longitude,2),"
                "dashaBalance:NodeJhora.calculateDashaBalance(moon.longitude),"
                "narayanaDasha:NodeJhora.NarayanaDasha?NodeJhora.NarayanaDasha.calculate(chart2,dt,80):null," "varga:NodeJhora.calculateVarga?NodeJhora.calculateVarga(moon.longitude,9):null"
                "})}else{JSON.stringify({error:'moon/sun not found'})}"
                "}else{JSON.stringify({error:'no planets'})}"
                "}catch(e){JSON.stringify({error:e.message})}" % (
                    iso_vd_date, lat, lon))
            if njt and 'error' not in njt:
                result["nodejhora_top"]=njt
        except: pass
    result["_hint"]=("PyJHora已全量:Panchanga/Shadbala/Ashtakavarga/RajaYoga774/Dosha7/Arudha/Vimshottari/House分析/行星状态/VargaD3-30。\\nNodeJhora(DE440):行星/宫位/Jaimini/Ashtakavarga+Yogini+Yoga+Panchanga+Vimshottari+DashaBalance+NarayanaDasha。\\nCaelus/NatalEngine已预取。自探索:dir(jhora)/Object.keys(NodeJhora)/Object.keys(Caelus)")
    # ===== NatalEngine(文本) + Caelus(分盘) + PyJHora深度 =====
    try:
        _js_load("natalengine-engine")
        v=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateVedic('{date_str}',{hour_dec},{tz},{lat or 0},{lon or 0}))")
        if v and 'bridge not available' not in v: result["natal"]=v; result["engine"]+="+NatalEngine"
    except: pass
    try:
        _js_load("caelus-engine")
        if lat and lon:
            c=_js("caelus-engine",
                "var e=new Caelus.Engine(Caelus.embeddedData);var jd=%s;"
                "var bodies=['sun','moon','mercury','venus','mars','jupiter','saturn','uranus','neptune','pluto','chiron'];"
                "var moonLon=e.longitude('moon',jd,{zodiac:'sidereal:lahiri'});"
                "JSON.stringify({vargaD9:Caelus.vargaChart(e,jd,9,bodies,'sidereal:lahiri'),"
                "vimshottari:Caelus.vimshottariDashas(moonLon,jd),"
                "ashtottari:Caelus.ashtottariAt(e,jd,jd,%f,%f),"
                "yogini:Caelus.yoginiAt(e,jd,jd,%f,%f)})"
                %(jd_vd,lat,lon,lat,lon))
            if c and 'bridge not available' not in c: result["caelus"]=c; result["engine"]+="+Caelus"
    except: pass
    # Transit + Sade Sati (当前行运)
    try:
        import datetime
        today=datetime.datetime.now()
        today_jd = compute_jd(today.year, today.month, today.day, 12, 0, 0)
        transit=_js("caelus-engine",
            "var e=new Caelus.Engine(Caelus.embeddedData);"
            "var natalJd=%s;"
            "var transitJd=%s;"
            "var natalMoon=e.longitude('moon',natalJd,{zodiac:'sidereal:lahiri'});"
            "var transitSaturn=e.longitude('saturn',transitJd,{zodiac:'sidereal:lahiri'});"
            "var transitJupiter=e.longitude('jupiter',transitJd,{zodiac:'sidereal:lahiri'});"
            "var transitRahu=e.longitude('north_node',transitJd,{zodiac:'sidereal:lahiri'});"
            "function signIdx(lon){return Math.floor(lon/30);}"
            "var moonSign=signIdx(natalMoon);"
            "var satSign=signIdx(transitSaturn);"
            "var sadeSati=null;"
            "if(satSign===moonSign)sadeSati='peak';"
            "else if(satSign===(moonSign+11)%%12)sadeSati='rising';"
            "else if(satSign===(moonSign+1)%%12)sadeSati='setting';"
            "JSON.stringify({sadeSati:sadeSati,moonSign:moonSign,saturnSign:satSign,saturnLon:transitSaturn,jupiterSign:signIdx(transitJupiter),rahuSign:signIdx(transitRahu)})"
            %(jd_vd,today_jd))
        if transit and 'error' not in transit: result["transit"]=transit
    except: pass
    # PyJHora深度
    try:
        from jhora.horoscope.dhasa.graha import ashtottari as a_py, yogini as y_py
        result["ashtottari_dasha"]=a_py.get_ashtottari_dhasa_bhukthi(jd_local,place)
        result["yogini_dasha"]=y_py.get_dhasa_bhukthi(drik.Date(year,month,day),(hour,minute,0),place)
        result["engine"]+="+PyJHora_deep"
    except: pass
    try:
        from jhora.horoscope.dhasa.raasi import narayana, chara
        result["narayana_dasha"]=narayana.narayana_dhasa_for_rasi_chart(drik.Date(year,month,day),(hour,minute,0),place)
        result["chara_dasha"]=chara.get_dhasa_antardhasa(drik.Date(year,month,day),(hour,minute,0),place)
        result["engine"]+="+raasi"
    except: pass
    return result
