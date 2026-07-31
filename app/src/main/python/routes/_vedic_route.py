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
        drik.set_ayanamsa_mode('LAHIRI')  # 标准 Lahiri(Chitrapaksha)岁差，与主流Panchanga及CAE sidereal:lahiri一致
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
        py["ayanamsa"]=drik.get_ayanamsa_value(jd_local); py["ayanamsa_mode"]="LAHIRI"
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
        # ——— 22. Gochara 行运（当前时刻，标准 BPHS Ch.29 + 传统土星行运） ———
        try:
            import datetime as _dtm
            _now_utc = _dtm.datetime.now(_dtm.timezone.utc)
            # 用出生地时区构造"当前本地时间"（与 jd_local 的本地语义一致）
            _now_local = _now_utc + _dtm.timedelta(hours=tz_vd)
            now_jd = utils.julian_day_number(
                drik.Date(_now_local.year, _now_local.month, _now_local.day),
                (_now_local.hour, _now_local.minute, 0))
            # 行运位置：与全路由排盘同一 API（dhasavarga 返回 [pid,[rasi,deg]]，仅九曜；
            # planetary_positions 在 pyjhora 4.8.7 中因 planet_list.index 缺陷不可用）
            _tr_pl = drik.dhasavarga(now_jd, place, 1)
            _tr_names = {0:'Sun',1:'Moon',2:'Mars',3:'Mercury',4:'Jupiter',5:'Venus',
                         6:'Saturn',7:'Rahu',8:'Ketu'}
            _rasi_names = const.rasi_names_en
            _moon_rasi = pp[2][1][0]  # 本命月亮星座（Gochara 主基准 = Chandra Lagna）
            # BPHS Ch.29 各行星吉宫（从本命月亮起算）；Rahu/Ketu 传统同火星论(3/6/11)
            _bphs_good = {
                0:[3,6,10,11], 1:[1,3,6,7,10,11], 2:[3,6,11], 3:[2,4,6,8,10,11],
                4:[2,5,7,9,11], 5:[1,2,3,4,5,8,9,11,12], 6:[3,6,11], 7:[3,6,11], 8:[3,6,11],
            }
            # BPHS Ch.29 Vedha 阻碍对：吉宫 -> 阻碍宫（另一行星在该宫时吉运被挡）
            _bphs_vedha = {
                0:{3:9,6:12,10:4,11:5},
                1:{1:5,3:9,6:12,7:2,10:4,11:8},
                2:{3:12,6:9,11:5},
                3:{2:5,4:3,6:9,8:1,10:8,11:12},
                4:{2:12,5:4,7:3,9:10,11:8},
                5:{1:8,2:7,3:1,4:10,5:9,8:5,9:11,11:6,12:3},
                6:{3:12,6:9,11:5},
                7:{3:12,6:9,11:5},
                8:{3:12,6:9,11:5},
            }
            _transit = {}
            _tr_rasi_of = {}
            for _pid, (_rasi, _coords) in _tr_pl:
                _lon = _rasi * 30 + _coords
                _nak = drik.nakshatra_pada(_lon)
                _h_moon = (_rasi - _moon_rasi) % 12 + 1
                _h_lagna = (_rasi - asc_house) % 12 + 1
                _good = _h_moon in _bphs_good[_pid]
                _entry = {
                    "longitude": round(_lon, 4),
                    "rasi": _rasi,
                    "rasi_name": _rasi_names[_rasi],
                    "degree": round(_coords, 4),
                    "nakshatra": _nak[0],
                    "pada": _nak[1],
                    "house_from_moon": _h_moon,
                    "house_from_lagna": _h_lagna,
                    "gochara_effect": "benefic" if _good else "malefic",
                }
                # Vedha：吉宫行运被阻碍宫内的其他行运行星所挡
                # （Parashari 惯例豁免 日月互阻 / 木水互阻）
                if _good:
                    _vh = _bphs_vedha[_pid].get(_h_moon)
                    if _vh is not None:
                        _target = (_moon_rasi + _vh - 1) % 12
                        _vedha = []
                        for _oid, (_orasi, _odeg) in _tr_pl:
                            if _oid == _pid or _orasi != _target:
                                continue
                            if (_pid == 0 and _oid == 1) or (_pid == 1 and _oid == 0) or \
                               (_pid == 4 and _oid == 3) or (_pid == 3 and _oid == 4):
                                continue
                            _vedha.append(_tr_names[_oid])
                        if _vedha:
                            _entry["vedha_planets"] = _vedha
                _transit[_tr_names[_pid]] = _entry
                _tr_rasi_of[_pid] = _rasi
            py["transit"] = _transit
            # 行运对本命 drishti 相位（对照 const.graha_drishti 传统规则）
            try:
                _tr_drishti = {}
                for _pid, _rasi in _tr_rasi_of.items():
                    _asp = []
                    for _off in const.graha_drishti.get(_pid, [7]):
                        _ar = (_rasi + _off) % 12
                        for _nid, (_nrasi, _ndeg) in pp[1:]:
                            if _nid != const._ascendant_symbol and _nrasi == _ar:
                                _asp.append(_tr_names.get(_nid, str(_nid)))
                    if _asp:
                        _tr_drishti[_tr_names.get(_pid, str(_pid))] = _asp
                if _tr_drishti:
                    py["transit_drishti"] = _tr_drishti
            except Exception:
                pass
            # 土星专项行运（均以本命月亮为基准）
            try:
                _sat_rasi = _tr_rasi_of.get(6)
                if _sat_rasi is not None:
                    _diff = (_sat_rasi - _moon_rasi) % 12  # 0=第1宫, 11=第12宫
                    py["sade_sati"] = (
                        "peak" if _diff == 0
                        else "rising" if _diff == 11
                        else "setting" if _diff == 1 else "none")
                    py["saturn_transits"] = {
                        "saturn_house_from_moon": _diff + 1,
                        "sade_sati": py["sade_sati"],
                        "ashtama_shani": _diff == 7,          # 第8宫
                        "ardha_ashtama_shani": _diff == 3,    # 第4宫（Dhaiya）
                        "kantaka_shani": _diff in (3, 6, 9),  # 第4/7/10宫
                    }
            except Exception:
                pass
        except Exception:
            pass
        # ——— engine 已在上文各阶段累计 ———
    except Exception as e:
        result["pyjhora_error"]=str(e)
        result["engine"]=""
    result["_hint"]=("PyJHora全量:Panchanga(含月出/落+日/夜长+7日星宿)/Muhurtha/VedicTime/Dasha(Vimshottari/Ashtottari/Yogini/Narayana+分盘/Chara/Kalachakra/Sudharsana)/House(CharaKarakas/Marakas/函益/Argala/Brahma/Rudra/YogaKaaraka)/行星强度排名/吉凶星/GrahaDrishti/行星状态(combustion/retrograde/MKS/KP)/行星擢升落陷(planet_dignity)/宫位分布/Shadbala+Bhavabala+BhavaDrishti+PanchaVargeeya/特殊格局(RajaYoga+YogaDetails)/瑜伽(Sunapha/Anapha/Duradhara/GajaKesari/Vesi/Vosi/Ubhayachara)/Ashtakavarga(含SodhayaPindas)/Dosha8(含Ghata)/Arudha/全部分盘(D2-D60)+64thNavamsa+22ndDrekkana/逐星Nakshatra+速度+GrahaYuddha+BhaavaMadhya/SpecialLagnas(Sree/Pranapada/BhriguBindhu/Bhava/Hora/Ghati)/Upagrahas(含Kaala/Mrityu/Gulika等非太阳余炁)/农历/季节/Naisargika+SthiraKarakas/VivahaChakra/Chandrashtama/Tajaka年运+TajakaYogas/全19Saham/Eclipses/Thaaraabalam/AmritaGadiya/Varjyam/Sankranti/DhasaYearDuration/Sphuta8(Tri/Chatur/Prana/Deha/Mrityu/Beeja/Yogi/Avayogi)。"
        "Gochara行运(九曜当前西达尔经度/星座/度数/星宿+分度/月亮与上升双基准宫位/BPHS第29章吉凶+Vedha阻碍/行运对本命Drishti/"
        "SadeSati+AshtamaShani+ArdhaAshtama+KantakaShani)/Lahiri岁差。"
        "自探索:dir(jhora)")
    return result
