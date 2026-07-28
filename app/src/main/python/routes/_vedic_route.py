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
    # ===== PyJHora (Python/Chaquopy) =====
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
        pp=drik.dhasavarga(jd_local,place,1)
        asc_raw=drik.ascendant(jd_local,place)
        asc_house,asc_long=drik.dasavarga_from_long(asc_raw[0]*30+asc_raw[1],1)
        pp=[[const._ascendant_symbol,(asc_house,asc_long)]]+pp
        p_to_h={p:h for p,(h,_) in pp}
        h_to_p=utils.get_house_planet_list_from_planet_positions(pp)
        py = result["pyjhora"] = {}
        result["engine"]="PyJHora"
        # ——— 1. 排盘（最核心） ———
        py["planets"]=pp[1:]; py["lagna"]={"rasi":asc_raw[0],"deg":asc_raw[1],"nak":asc_raw[2],"pada":asc_raw[3]}
        py["ayanamsa"]=drik.get_ayanamsa_value(jd_local)
        # ——— 2. Panchanga（基础信息） ———
        try: py["panchanga"]={"tithi":drik.tithi(jd_local,place),"nakshatra":drik.nakshatra(jd_local,place),"yogam":drik.yogam(jd_local,place),"karana":drik.karana(jd_local,place),"vaara":drik.vaara(jd_local,place),"sunrise":drik.sunrise(jd_local,place),"sunset":drik.sunset(jd_local,place),"moonrise":drik.moonrise(jd_local,place),"moonset":drik.moonset(jd_local,place),"day_length":drik.day_length(jd_local,place),"night_length":drik.night_length(jd_local,place)}
        except: pass
        try: py["muhurtha"]={"rahu_kaalam":drik.trikalam(jd_local,place,'raahu kaalam'),"yamagandam":drik.trikalam(jd_local,place,'yamagandam'),"gulikai":drik.trikalam(jd_local,place,'gulikai'),"abhijit":drik.abhijit_muhurta(jd_local,place),"brahma_muhurtha":drik.brahma_muhurtha(jd_local,place)}
        except: pass
        try: py["vedic_time"]=drik.vedic_time(jd_local,place)
        except: pass
        # ——— 3. Dasha 运势（核心推运） ———
        try: py["vimshottari"]=vimsottari.get_vimsottari_dhasa_bhukthi(jd_local,place)
        except: pass
        try:
            from jhora.horoscope.dhasa.graha import ashtottari as a_py, yogini as y_py
            py["ashtottari_dasha"]=a_py.get_ashtottari_dhasa_bhukthi(jd_local,place)
            py["yogini_dasha"]=y_py.get_dhasa_bhukthi(drik.Date(year,month,day),(hour,minute,0),place)
        except: pass
        try:
            from jhora.horoscope.dhasa.raasi import narayana, chara
            py["narayana_dasha"]=narayana.narayana_dhasa_for_rasi_chart(drik.Date(year,month,day),(hour,minute,0),place)
            py["narayana_varga_dasha"]=narayana.narayana_dhasa_for_divisional_chart(drik.Date(year,month,day),(hour,minute,0),place,divisional_chart_factor=9)
            py["chara_dasha"]=chara.get_dhasa_antardhasa(drik.Date(year,month,day),(hour,minute,0),place)
        except: pass
        try:
            from jhora.horoscope.dhasa.raasi import kalachakra
            _moon_long=pp[2][1][0]*30+pp[2][1][1]
            py["kalachakra_dhasa"]=kalachakra.kalachakra_dhasa(_moon_long,jd_local,place=place)
        except: pass
        try:
            from jhora.horoscope.dhasa import sudharsana_chakra as _sc
            py["sudharsana_chakra"]=_sc.sudharshana_chakra_chart(jd_local,place,drik.Date(year,month,day))
            py["sudharsana_dhasa"]=_sc.get_dhasa_bhukthi(jd_local,place)
        except: pass
        # ——— 4. House 宫位关键分析 ———
        try:
            py["house_analysis"]={
                "chara_karakas":house.chara_karakas(pp),
                "marakas":house.marakas(h_to_p),
                "functional_benefic":house.functional_benefic_lord_houses(asc_house),
                "functional_malefic":house.functional_malefic_lord_houses(asc_house),
                "argala":house.get_argala(h_to_p),
                "brahma":house.brahma(pp),
                "rudra":house.rudra(pp),
                "yoga_kaaraka":{str(p):house.is_yoga_kaaraka(asc_house,p,h) for p,(h,_) in pp if p!=const._ascendant_symbol},
            }
        except: pass
        # ——— 5. 行星强度排序 + 吉凶星 + 互视 ———
        try:
            py["planet_strength_ranking"]=house.order_of_planets_by_strength(pp)
            py["rasi_strength_ranking"]=house.order_of_raasis_by_strength(pp)
            _bene_male=charts.benefics_and_malefics(jd_local,place)
            py["benefics"]=_bene_male[0]; py["malefics"]=_bene_male[1]
            py["graha_drishti"]=house.graha_drishti_from_chart(h_to_p)
        except: pass
        # ——— 6. 行星状态 ———
        try:
            py["planet_status"]={
                "combustion":charts.planets_in_combustion(pp),
                "retrograde":charts.planets_in_retrograde(pp),
                "marana_karaka_sthana":charts.get_planets_in_marana_karaka_sthana(pp),
                "kp_lords":charts.get_KP_lords_from_planet_positions(pp),
            }
        except: pass
        # ——— 7. 行星擢升/落陷 ———
        try:
            _planet_names={0:"Sun",1:"Moon",2:"Mars",3:"Mercury",4:"Jupiter",5:"Venus",6:"Saturn",7:"Rahu",8:"Ketu"}
            _exalted=[]; _debilitated=[]
            for p_id,(_rasi,_deg) in pp:
                if p_id in _planet_names:
                    _pn=_planet_names[p_id]
                    _strength=const.house_strengths_of_planets[p_id][_rasi]
                    if _strength >= const._EXALTED_UCCHAM:
                        _exalted.append({"planet":_pn,"id":p_id,"rasi":_rasi,"deg":_deg})
                    if _strength == const._DEBILITATED_NEECHAM:
                        _debilitated.append({"planet":_pn,"id":p_id,"rasi":_rasi,"deg":_deg})
            py["planet_dignity"]={"exalted":_exalted,"debilitated":_debilitated}
        except: pass
        # ——— 8. 行星在宫位分布 ———
        try: py["houses"]={"planets_in_quadrants":house.get_planets_in_quadrants(p_to_h),"planets_in_trines":house.get_planets_in_trines(p_to_h),"planets_in_dushthanas":house.get_planets_in_dushthanas(p_to_h)}
        except: pass
        # ——— 9. Shadbala 力量 ———
        try: py["shadbala"]=strength.shad_bala(jd_local,place)
        except: pass
        try: py["bhava_bala"]=strength.bhava_bala(jd_local,place)
        except: pass
        try: py["bhava_drishti_bala"]=strength.bhava_drishti_bala(jd_local,place)
        except: pass
        try: py["pancha_vargeeya_bala"]=strength.pancha_vargeeya_bala(jd_local,place)
        except: pass
        # ——— 10. 特殊格局 ———
        try:
            ry_data,ry_found,ry_total=raja_yoga.get_raja_yoga_details(jd_local,place)
            py["raja_yoga"]={"yogas":ry_data,"found":ry_found,"total_checked":ry_total}
        except: pass
        try:
            yd_data,yd_found,yd_total=yoga.get_yoga_details(jd_local,place)
            py["yoga_details"]={"yogas":yd_data,"found":yd_found,"total_checked":yd_total}
        except: pass
        # ——— 11. 特殊瑜伽检测 ———
        try:
            py["yogas_chandra_moon"]={"sunapha":yoga.sunaphaa_yoga_from_planet_positions(pp),"anapha":yoga.anaphaa_yoga_from_planet_positions(pp),"duradhara":yoga.duradhara_yoga_from_planet_positions(pp),"gaja_kesari":yoga.gaja_kesari_yoga_from_planet_positions(pp)}
            py["yogas_surya_sun"]={"vesi":yoga.vesi_yoga_from_planet_positions(pp),"vosi":yoga.vosi_yoga_from_planet_positions(pp),"ubhayachara":yoga.ubhayachara_yoga_from_planet_positions(pp)}
        except: pass
        # ——— 12. Ashtakavarga ———
        try:
            _bav_raw=ashtakavarga.get_ashtaka_varga(h_to_p)
            py["ashtakavarga"]={"bav":_bav_raw[0],"sav":_bav_raw[1],"pav":_bav_raw[2]}
            py["ashtakavarga_sodhaya"]=ashtakavarga.sodhaya_pindas(_bav_raw[0],h_to_p)
        except: pass
        # ——— 13. Dosha 凶格 ———
        try:
            moon_nak=asc_raw[2] if asc_raw else 0
            py["dosha"]={
                "manglik":dosha.manglik(pp),
                "kala_sarpa":dosha.kala_sarpa(h_to_p),
                "guru_chandala":dosha.guru_chandala_dosha(pp),
                "pitru_dosha":dosha.pitru_dosha(pp),
                "kalathra":dosha.kalathra(pp),
                "ganda_moola":dosha.ganda_moola(moon_nak),
                "shrapit":dosha.shrapit(pp),
                "ghata":dosha.ghata(pp),
            }
        except: pass
        # ——— 14. Arudha 映照 ———
        try: py["arudha"]=arudhas.bhava_arudhas_from_planet_positions(pp)
        except: pass
        # ——— 15. 分盘 Varga ———
        try:
            for dnum, dkey in [(2,"d2"),(3,"d3"),(4,"d4"),(7,"d7"),(9,"d9"),(10,"d10"),
                                (12,"d12"),(16,"d16"),(20,"d20"),(24,"d24"),(27,"d27"),
                                (30,"d30"),(40,"d40"),(45,"d45"),(60,"d60")]:
                try: py[f"varga_{dkey}"]=drik.dhasavarga(jd_local,place,dnum)
                except: pass
        except: pass
        # ——— 16. Varga 辅助: 64th Navamsa + 22nd Drekkana ———
        try:
            _d9=drik.dhasavarga(jd_local,place,9); _d3=drik.dhasavarga(jd_local,place,3)
            py["varga_d9_64th_navamsa"]=charts.get_64th_navamsa(_d9)
            py["varga_d3_22nd_drekkana"]=charts.get_22nd_drekkana(_d3)
        except: pass
        # ——— 17. 逐行星Nakshatra + 速度 + 交战 + 宫头 ———
        try:
            _pnak={}
            for _pid,(_pr,_pl) in pp[1:]:
                if _pid!=const._ascendant_symbol:
                    _pnak[str(_pid)]=drik.nakshatra_pada(_pr*30+_pl)
            py["planet_nakshatra"]=_pnak
            py["planet_speed"]=drik.planets_speed_info(jd_local,place)
            py["graha_yuddha"]=drik.planets_in_graha_yudh(jd_local,place)
            py["bhaava_madhya"]=drik.bhaava_madhya(jd_local,place)
        except Exception:
            pass
        # ——— 18. Special Lagnas + Upagrahas (含非太阳余炁) ———
        try:
            py["sree_lagna"]=drik.sree_lagna(jd_local,place)
            py["pranapada_lagna"]=drik.pranapada_lagna(jd_local,place)
            py["bhrigu_bindhu"]=drik.bhrigu_bindhu_lagna(jd_local,place)
            py["bhava_lagna"]=drik.bhava_lagna(jd_local,place)
            py["hora_lagna"]=drik.hora_lagna(jd_local,place)
            py["ghati_lagna"]=drik.ghati_lagna(jd_local,place)
            _sun_long=pp[1][1][0]*30+pp[1][1][1]
            py["upagrahas"]={_n:drik.solar_upagraha_longitudes(_sun_long,_n) for _n in ('dhuma','vyatipaata','parivesha','indrachaapa','upaketu')}
            _dob=drik.Date(year,month,day); _tob=(hour,minute,0)
            py["non_solar_upagrahas"]={
                "kaala":drik.kaala_longitude(_dob,_tob,place),
                "mrityu":drik.mrityu_longitude(_dob,_tob,place),
                "artha_praharaka":drik.artha_praharaka_longitude(_dob,_tob,place),
                "yama_ghantaka":drik.yama_ghantaka_longitude(_dob,_tob,place),
                "gulika":drik.gulika_longitude(_dob,_tob,place),
                "maandi":drik.maandi_longitude(_dob,_tob,place),
            }
        except Exception:
            pass
        # ——— 19. 农历/季节/年名 + Jaimini Karakas + 附加 ———
        try:
            _lm=drik.lunar_month(jd_local,place)
            py["lunar_info"]={"month_index":_lm[0],"is_adhika":_lm[1],"is_nija":_lm[2],"ritu_index":drik.ritu(_lm[0]),"samvatsara":drik.samvatsara(drik.Date(year,month,day),place)}
            py["naisargika_karakas"]=house.naisargika_karakas()
            py["sthira_karakas"]=house.sthira_karakas(pp)
            py["vivaha_chakra_palan"]=drik.vivaha_chakra_palan(jd_local,place)
            py["chandrashtama"]=drik.chandrashtama(jd_local,place)
        except Exception:
            pass
        # ——— 20. Tajaka 年运 + Saham + Eclipse ———
        try: py["tajaka"]={"varsha_pravesh":_tj.varsha_pravesh(jd_local,place),"annual_chart":_tj.annual_chart(jd_local,place),"lord_of_year":_tj.lord_of_the_year(jd_local,place,0)}
        except: pass
        try: py["tajaka_yoga"]={"ishkavala":_tjy.ishkavala_yoga(p_to_h),"induvara":_tjy.induvara_yoga(p_to_h),"nakta":_tjy.get_nakta_yoga_planet_triples(pp),"ithasala":_tjy.get_ithasala_yoga_planet_pairs(pp),"eesarpha":_tjy.get_eesarpha_yoga_planet_pairs(pp),"yamaya":_tjy.get_yamaya_yoga_planet_triples(pp),"manahoo":_tjy.get_manahoo_yoga_planet_pairs(pp),"kamboola":_tjy.get_kamboola_yoga_planet_pairs(pp)}
        except: pass
        try: py["saham"]={"punya":_sh.punya_saham(pp),"vidya":_sh.vidya_saham(pp),"yasas":_sh.yasas_saham(pp),"mitra":_sh.mitra_saham(pp),"mahatmaya":_sh.mahatmaya_saham(pp),"asha":_sh.asha_saham(pp),"samartha":_sh.samartha_saham(pp),"bhratri":_sh.bhratri_saham(pp),"gaurava":_sh.gaurava_saham(pp),"pithri":_sh.pithri_saham(pp),"rajya":_sh.rajya_saham(pp),"maathri":_sh.maathri_saham(pp),"puthra":_sh.puthra_saham(pp),"jeeva":_sh.jeeva_saham(pp),"karma":_sh.karma_saham(pp),"roga":_sh.roga_saham(pp),"kali":_sh.kali_saham(pp),"sastra":_sh.sastra_saham(pp),"bandhu":_sh.bandhu_saham(pp)}
        except: pass
        try: py["eclipses"]={"next_solar":next_solar_eclipse(jd_local,place),"next_lunar":next_lunar_eclipse(jd_local,place)}
        except: pass
        # ——— 21. 星辰吉凶/星宿力量/时择 ———
        try: py["thaaraabalam"]=drik.thaaraabalam(jd_local,place)
        except: pass
        try: py["amrita_gadiya"]=drik.amrita_gadiya(jd_local,place)
        except: pass
        try: py["varjyam"]=drik.varjyam(jd_local,place)
        except: pass
        try: py["sankranti"]={"next":drik.next_sankranti_date(drik.Date(year,month,day),place),"previous":drik.previous_sankranti_date(drik.Date(year,month,day),place)}
        except: pass
        try: py["dhasa_year_duration"]=drik.dhasa_year_duration(jd=jd_local,place=place)
        except: pass
        # ——— 22. Sphuta 数理 ———
        try:
            from jhora.horoscope.chart import sphuta as _sph
            _dob=drik.Date(year,month,day); _tob=(hour,minute,0)
            py["sphuta"]={
                "tri":_sph.tri_sphuta(_dob,_tob,place),
                "chatur":_sph.chatur_sphuta(_dob,_tob,place),
                "prana":_sph.prana_sphuta(_dob,_tob,place),
                "deha":_sph.deha_sphuta(_dob,_tob,place),
                "mrityu":_sph.mrityu_sphuta(_dob,_tob,place),
                "beeja":_sph.beeja_sphuta(_dob,_tob,place),
                "yogi":_sph.yogi_sphuta(_dob,_tob,place),
                "avayogi":_sph.avayogi_sphuta(_dob,_tob,place),
            }
        except Exception:
            pass
        # ——— engine 已在上文各阶段累计 ———
    except Exception as e:
        result["pyjhora_error"]=str(e)
        result["engine"]=""
    # ===== NodeJhora (DE440) =====
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
    # ===== NatalEngine =====
    try:
        _js_load("natalengine-engine")
        v=json.loads(_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateVedic('{date_str}',{hour_dec},{tz},{lat or 0},{lon or 0}))"))
        if isinstance(v, dict) and 'error' not in v: result["natal"]=v; result["engine"]=result.get("engine","")+"+NatalEngine"
    except: pass
    # ===== Caelus =====
    try:
        _js_load("caelus-engine")
        if lat and lon:
            today=datetime.datetime.now()
            today_jd=compute_jd(today.year,today.month,today.day,today.hour,today.minute,0)
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
                # 行运 Gochara
                "var trBodies=['sun','moon','mars','mercury','jupiter','venus','saturn','north_node','south_node'];"
                "r.transit={}; trBodies.forEach(function(b){"
                "var lon=e.longitude(b,today,{zodiac:'sidereal:lahiri'});"
                "var sg=Math.floor(lon/30);"
                "r.transit[b]={longitude:lon,sign:sg,degree:lon%30};"
                "});"
                # Sade Sati
                "var _ms=Math.floor(moonLon/30);var _ss=Math.floor(r.transit.saturn.longitude/30);"
                "if(_ss===_ms)r.transit.sadeSati='peak';"
                "else if(_ss===((_ms+11)%%12))r.transit.sadeSati='rising';"
                "else if(_ss===((_ms+1)%%12))r.transit.sadeSati='setting';"
                # Transit nakshatra
                "try{r.transitNakshatra={};"
                "var _trNakBodies=['sun','moon','mars','mercury','jupiter','venus','saturn','north_node','south_node'];"
                "_trNakBodies.forEach(function(b){r.transitNakshatra[b]=Caelus.nakshatraAt(e,today,b,'sidereal:lahiri');});"
                "}catch(ex){}"
                # Transit aspects
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
                result["engine"]=result.get("engine","")+"+Caelus"
    except: pass
    result["_hint"]=("PyJHora全量:Panchanga(含月出/落+日/夜长+7日星宿)/Muhurtha/VedicTime/Dasha(Vimshottari/Ashtottari/Yogini/Narayana+分盘/Chara/Kalachakra/Sudharsana)/House(CharaKarakas/Marakas/函益/Argala/Brahma/Rudra/YogaKaaraka)/行星强度排名/吉凶星/GrahaDrishti/行星状态(combustion/retrograde/MKS/KP)/行星擢升落陷(planet_dignity)/宫位分布/Shadbala+Bhavabala+BhavaDrishti+PanchaVargeeya/特殊格局(RajaYoga+YogaDetails)/瑜伽(Sunapha/Anapha/Duradhara/GajaKesari/Vesi/Vosi/Ubhayachara)/Ashtakavarga(含SodhayaPindas)/Dosha8(含Ghata)/Arudha/全部分盘(D2-D60)+64thNavamsa+22ndDrekkana/逐星Nakshatra+速度+GrahaYuddha+BhaavaMadhya/SpecialLagnas(Sree/Pranapada/BhriguBindhu/Bhava/Hora/Ghati)/Upagrahas(含Kaala/Mrityu/Gulika等非太阳余炁)/农历/季节/Naisargika+SthiraKarakas/VivahaChakra/Chandrashtama/Tajaka年运+TajakaYogas/全19Saham/Eclipses/Thaaraabalam/AmritaGadiya/Varjyam/Sankranti/DhasaYearDuration/Sphuta8(Tri/Chatur/Prana/Deha/Mrityu/Beeja/Yogi/Avayogi)。"
        "NodeJhora(DE440):行星/宫位/Jaimini/Ashtakavarga/Yogini/Yoga/Panchanga/Vimshottari+NarayanaDasha/VargaD9/InduLagna/DhumadiUpagrahas。"
        "Caelus:Vimshottari+Varga(D3/D9/D10/D12/D30)/NakshatraBodies/Yogas/Kemadruma/RajaYogas/DhanaYogas/Ashtottari/Yogini/行运(全9星西达尔经度/星座/度数)+SadeSati/留(全7星)/returns(火木土)/行运Nakshatra/行运对本命相位(含本命宫位)。"
        "自探索:dir(jhora)/Object.keys(NodeJhora)/Object.keys(Caelus)")
    return result
