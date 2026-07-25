"""Route:  vedic"""
import json, sys, os, datetime
from ._shared import _js, _js_load, compute_jd, resolve_tz

# ===== 吠陀 =====
def _vedic(year,month,day,hour,tz,lat=None,lon=None,minute=0):
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    tz_vd = resolve_tz(tz)
    date_str=f"{year}-{month:02d}-{day}"
    tz_vd_sign = "+" if tz_vd >= 0 else "-"
    tz_vd_abs = abs(tz_vd)
    tz_vd_str = f"{tz_vd_sign}{int(tz_vd_abs):02d}:{int((tz_vd_abs - int(tz_vd_abs)) * 60 + 0.5):02d}"
    iso_vd_date = f"{date_str}T{hour:02d}:{minute:02d}:00{tz_vd_str}"
    jd_vd = compute_jd(year, month, day, hour, minute, tz_vd)
    hour_dec = hour + minute/60

    result={"system":"vedic"}
    jd_local=None; place=None
    # ===== 默认主力: PyJHora (Python/Chaquopy) =====
    try:
        from jhora import const, utils
        from jhora.panchanga import drik
        from jhora.horoscope.chart import house, strength, raja_yoga, yoga, dosha, ashtakavarga, arudhas, charts
        from jhora.horoscope.dhasa.graha import vimsottari
        from jhora.horoscope.transit import tajaka as _tj
        from jhora.horoscope.transit import tajaka_yoga as _tjy
        from jhora.horoscope.transit import saham as _sh
        from jhora.panchanga.eclipse import next_solar_eclipse, next_lunar_eclipse
        place=drik.Place("loc",lat or 0,lon or 0,tz_vd)
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
        # 11b. 行星擢升/落陷
        try:
            from jhora import const as _jc
            _exalt_lons=_jc.planet_deep_exaltation_longitudes
            _tolerance=_jc.planet_deep_exaltation_tolerance
            _planet_names={0:"Sun",1:"Moon",2:"Mars",3:"Mercury",4:"Jupiter",5:"Venus",6:"Saturn"}
            _exalted=[]; _debilitated=[]
            for p_id,(_rasi,_deg) in pp:
                if p_id in _planet_names:
                    _pn=_planet_names[p_id]
                    _abs_lon=(_rasi*30+_deg)%360  # 将rasi+度转换为绝对经度
                    _ex=_exalt_lons[p_id]
                    _de=(_ex+180)%360
                    _diff_ex=abs(_abs_lon-_ex)
                    if _diff_ex>180: _diff_ex=360-_diff_ex
                    _diff_de=abs(_abs_lon-_de)
                    if _diff_de>180: _diff_de=360-_diff_de
                    if _diff_ex<=_tolerance:
                        _exalted.append({"planet":_pn,"id":p_id,"longitude":_abs_lon,"deep_exaltation":_ex})
                    if _diff_de<=_tolerance:
                        _debilitated.append({"planet":_pn,"id":p_id,"longitude":_abs_lon,"deep_debilitation":_de})
            result["planet_dignity"]={"exalted":_exalted,"debilitated":_debilitated}
        except: pass
        # 12. 全部分盘
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
    _eng = lambda: (result.get("engine","") or "")
    # ===== 辅助: NodeJhora (合并一次eval, 新增InduLagna+DhumadiUpagrahas) =====
    if lat and lon:
        try:
            _js_load("node-jhora-engine")
            nj=json.loads(_js("node-jhora-engine",
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
                "var tradPlanets=planets.filter(function(p){return p.id>=0&&p.id<=6});"
                "var charaKarakas=NodeJhora.JaiminiCore?NodeJhora.JaiminiCore.calculateCharaKarakas(tradPlanets):null;"
                "var atmakaraka=charaKarakas&&charaKarakas.length?charaKarakas[0]:null;"
                "var planetsSAV=planets.map(function(p){return p.id===99?{id:99,name:'Lagna',longitude:houses.ascendant}:p});"
                "var ashtakavarga=NodeJhora.Ashtakavarga?NodeJhora.Ashtakavarga.calculateSAV(planetsSAV):null;"
                "var yogini=NodeJhora.YoginiDasha&&moon.longitude?NodeJhora.YoginiDasha.calculate(moon.longitude,dt,50):null;"
                "var yogas=NodeJhora.YogaEngine?NodeJhora.YogaEngine.findYogas(chart,NodeJhora.YOGA_LIBRARY||[]):null;"
                "var safe=function(f){try{return f()}catch(e){return null}};"
                "var panchanga=safe(function(){return NodeJhora.calculatePanchanga(sun.longitude,moon.longitude,dt,6)});"
                "var vimshottari=safe(function(){return typeof NodeJhora.generateVimshottari==='function'?NodeJhora.generateVimshottari(dt,moon.longitude,2):null});"
                "var dashaBalance=safe(function(){return typeof NodeJhora.calculateDashaBalance==='function'?NodeJhora.calculateDashaBalance(moon.longitude):null});"
                "var chart2={planets:planets.map(function(p){return{name:p.id,longitude:p.longitude}}),houses:{}};"
                "var narayanaDasha=safe(function(){return NodeJhora.NarayanaDasha?NodeJhora.NarayanaDasha.calculate(chart2,dt,80):null});"
                "var vargaD9=safe(function(){return typeof NodeJhora.calculateVarga==='function'?NodeJhora.calculateVarga(moon.longitude,9):null});"
                "var _as=Math.floor(houses.ascendant/30)+1;var _ms=Math.floor((moon.longitude||0)/30)+1;"
                "var induLagna=NodeJhora.calculateInduLagna?safe(function(){return NodeJhora.calculateInduLagna(_as,_ms,planets)}):null;"
                "var dhumadiUpagrahas=NodeJhora.calculateDhumadiUpagrahas?NodeJhora.calculateDhumadiUpagrahas(sun.longitude||0):null;"
                "JSON.stringify({planets:planets,houses:houses,moonLon:moon.longitude||0,sunLon:sun.longitude||0,"
                "charaKarakas:charaKarakas,atmakaraka:atmakaraka,ashtakavarga:ashtakavarga,"
                "yogini:yogini,yogas:yogas,panchanga:panchanga,vimshottari:vimshottari,"
                "dashaBalance:dashaBalance,narayanaDasha:narayanaDasha,vargaD9:vargaD9,"
                "induLagna:induLagna,dhumadiUpagrahas:dhumadiUpagrahas})}"
                "}catch(e){JSON.stringify({error:e.message})}" % (
                    iso_vd_date, lat, lon, lat, lon)))
            if isinstance(nj, dict) and 'error' not in nj:
                result["nodejhora"]=nj
                result["engine"] = (result.get("engine","") or "") + "+NodeJhora"
        except: pass
    # ===== NatalEngine(文本) =====
    try:
        _js_load("natalengine-engine")
        v=json.loads(_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateVedic('{date_str}',{hour_dec},{tz},{lat or 0},{lon or 0}))"))
        if isinstance(v, dict) and 'error' not in v: result["natal"]=v; result["engine"]=_eng()+"+NatalEngine"
    except: pass
    # ===== Caelus (合并吠陀+行运, 复用Engine) =====
    try:
        _js_load("caelus-engine")
        if lat and lon:
            today=datetime.datetime.now()
            today_jd=compute_jd(today.year,today.month,today.day,12,0,0)
            c=json.loads(_js("caelus-engine",
                "var e=new Caelus.Engine(Caelus.embeddedData);var jd=%s;var today=%s;var _lat=%f;var _lon=%f;"
                "var bodies=['sun','moon','mercury','venus','mars','jupiter','saturn','uranus','neptune','pluto','chiron'];"
                "var sf=function(f){try{return f()}catch(e){return null}};"
                "var moonLon=e.longitude('moon',jd,{zodiac:'sidereal:lahiri'});"
                "var r={vimshottari:Caelus.vimshottariDashas(moonLon,jd)};"
                "try{r.vargaD9=Caelus.vargaChart(e,jd,9,bodies,'sidereal:lahiri');}catch(ex){}"
                "try{r.vargaD3=Caelus.vargaChart(e,jd,3,bodies,'sidereal:lahiri');}catch(ex){}"
                "try{r.vargaD10=Caelus.vargaChart(e,jd,10,bodies,'sidereal:lahiri');}catch(ex){}"
                "try{r.vargaD12=Caelus.vargaChart(e,jd,12,bodies,'sidereal:lahiri');}catch(ex){}"
                "try{r.vargaD30=Caelus.vargaChart(e,jd,30,bodies,'sidereal:lahiri');}catch(ex){}"
                "try{r.ashtottariDashas=Caelus.ashtottariDashas(moonLon,jd);}catch(ex){}"
                "try{r.yoginiDashas=Caelus.yoginiDashas(moonLon,jd);}catch(ex){}"
                "try{r.nakshatraBodies={};bodies.forEach(function(b){r.nakshatraBodies[b]=Caelus.nakshatraAt(e,jd,b,'sidereal:lahiri')})}catch(ex){}"
                "try{r.yogas=Caelus.yogasAt(e,jd,_lat,_lon,'sidereal:lahiri');}catch(ex){}"
                "try{r.kemadruma=Caelus.kemadrumaAt(e,jd,_lat,_lon,false,false,'sidereal:lahiri');}catch(ex){}"
                "try{r.vimshottariNow=Caelus.vimshottariAt(e,jd,today,'sidereal:lahiri');}catch(ex){}"
                "try{r.rajaYogas=Caelus.rajaYogasAt(e,jd,_lat,_lon,'sidereal:lahiri');}catch(ex){}"
                "try{r.dhanaYogas=Caelus.dhanaYogasAt(e,jd,_lat,_lon,'sidereal:lahiri');}catch(ex){}"
                # stations for graha motion
                "try{var gr=['sun','moon','mars','mercury','jupiter','venus','saturn'];"
                "r.stations={};gr.forEach(function(b){"
                "r.stations[b]=sf(function(){return Caelus.stations(e,b,jd,jd+365,5)})})}catch(ex){}"
                # returns for transit timing
                "try{r.returns={};['mars','jupiter','saturn'].forEach(function(b){"
                "r.returns[b]=sf(function(){return Caelus.returns(e,b,jd,jd,jd+365*3,'sidereal:lahiri').slice(0,3)})})}catch(ex){}"
                # 行运 Gochara: 全部行星当前实时西达尔黄道位置 + nakshatra + 相位
                "var trBodies=['sun','moon','mars','mercury','jupiter','venus','saturn','north_node','south_node'];"
                "r.transit={}; trBodies.forEach(function(b){"
                "var lon=e.longitude(b,today,{zodiac:'sidereal:lahiri'});"
                "var sg=Math.floor(lon/30);"
                "r.transit[b]={longitude:lon,sign:sg,degree:lon%30};"
                "});"
                # Sade Sati: 详细阶段判断
                "var _ms=Math.floor(moonLon/30);var _ss=Math.floor(r.transit.saturn.longitude/30);"
                "if(_ss===_ms)r.transit.sadeSati='peak';"
                "else if(_ss===((_ms+11)%%12))r.transit.sadeSati='rising';"
                "else if(_ss===((_ms+1)%%12))r.transit.sadeSati='setting';"
                # Transit nakshatra: 每颗行运行星的宿信息(用于行运时间判断)
                "try{r.transitNakshatra={};"
                "var _trNakBodies=['sun','moon','mars','mercury','jupiter','venus','saturn','north_node','south_node'];"
                "_trNakBodies.forEach(function(b){r.transitNakshatra[b]=Caelus.nakshatraAt(e,today,b,'sidereal:lahiri');});"
                "}catch(ex){}"
                # Transit aspects: 行运行星对本命行星的相位 + 经过的本命宫
                "try{"
                "var _allB=Caelus.BODIES.filter(function(b){return b!=='mean_node'&&b!=='true_node';});"
                "var _natalB={};"
                "_allB.forEach(function(b){_natalB[b]={lon:e.longitude(b,jd,{zodiac:'sidereal:lahiri'})};});"
                "var _asc=Caelus.angles(Caelus.embeddedData,jd,_lat,_lon)[0];"
                "var _wh=Caelus.housesWholeSign(_asc);"
                "var _natalChart={bodies:_natalB,cusps:_wh.map(function(c){return c/Caelus.DEG;}),zodiac:'sidereal:lahiri'};"
                "r.transitAspects=Caelus.transitAspects(_natalChart,e,today,{zodiac:'sidereal:lahiri'});"
                "}catch(ex){}"
                "JSON.stringify(r)"
                %(jd_vd,today_jd,lat,lon)))
            if isinstance(c, dict) and 'error' not in c:
                result["caelus"]=c
                result["engine"]=_eng()+"+Caelus"
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
    result["_hint"]=("PyJHora全量:Panchanga/Shadbala/Ashtakavarga/RajaYoga/Dosha7/Arudha/Vimshottari/House/行星状态/行星擢升落陷(planet_dignity)/全部分盘(D2-D60)。"
        "NodeJhora(DE440):行星/宫位/Jaimini/Ashtakavarga/Yogini/Yoga/Panchanga/Vimshottari+NarayanaDasha/VargaD9/InduLagna/DhumadiUpagrahas。"
        "Caelus:Vimshottari+Varga(D3/D9/D10/D12/D30)/NakshatraBodies/Yogas/Kemadruma/RajaYogas/DhanaYogas/Ashtottari/Yogini/行运(全9星西达尔经度/星座/度数)+SadeSati/留(全7星)/returns(火木土)/行运Nakshatra/行运对本命相位(含本命宫位)。"
        "自探索:dir(jhora)/Object.keys(NodeJhora)/Object.keys(Caelus)")
    return result
