"""Route:  vedic"""
import json, sys, os, datetime
from ._shared import resolve_tz_checked

# ===== 吠陀 =====
def _vedic(year,month,day,hour,tz,lat=None,lon=None,minute=0):
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    try:
        tz_vd, _ = resolve_tz_checked(tz, at=(year, month, day, hour, minute))
    except ValueError as e:
        return {"system": "vedic", "error": f"时区参数错误: {e}"}
    tz_vd_sign = "+" if tz_vd >= 0 else "-"
    tz_vd_abs = abs(tz_vd)

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
        # 当前运行的大运阶梯：Maha→Antara(Bhukthi)→Pratyantara→Sukshma→Prana
        # （对齐 jhora "Show Running Dhasa" 对话框的 dhasa_level_index=PRANA 用法）
        try:
            import datetime as _dtm
            _now_utc = _dtm.datetime.now(_dtm.timezone.utc)
            _now_local = _now_utc + _dtm.timedelta(hours=tz_vd)
            _cur_jd = utils.julian_day_number(
                drik.Date(_now_local.year, _now_local.month, _now_local.day),
                (_now_local.hour, _now_local.minute, 0))
            py["vimshottari_running"] = vimsottari.get_running_dhasa_for_given_date(
                _cur_jd, jd_local, place, dhasa_level_index=const.MAHA_DHASA_DEPTH.PRANA)
        except Exception:
            pass
        try:
            from jhora.horoscope.dhasa.graha import ashtottari as a_py, yogini as y_py
            py["ashtottari_dasha"]=a_py.get_ashtottari_dhasa_bhukthi(jd_local,place)
            py["yogini_dasha"]=y_py.get_dhasa_bhukthi(drik.Date(year,month,day),(hour,minute,0),place)
        except: pass
        try:
            from jhora.horoscope.dhasa.raasi import narayana, chara
            py["narayana_dasha"]=narayana.narayana_dhasa_for_rasi_chart(drik.Date(year,month,day),(hour,minute,0),place)
            py["chara_dasha"]=chara.get_dhasa_antardhasa(drik.Date(year,month,day),(hour,minute,0),place)
        except: pass
        try:
            from jhora.horoscope.dhasa.raasi import kalachakra
            _moon_long=pp[2][1][0]*30+pp[2][1][1]
            py["kalachakra_dhasa"]=kalachakra.kalachakra_dhasa(_moon_long,jd_local,place=place)
        except: pass
        try:
            from jhora.horoscope.dhasa import sudharsana_chakra as _sc
            # jhora UI 标准调用 years_from_dob=0（以出生年排轮盘种子，行年逐年推进）
            py["sudharsana_chakra"]=_sc.sudharshana_chakra_chart(jd_local,place,drik.Date(year,month,day),years_from_dob=0)
            # jhora info._get_sudharsana_chakra_dhasa 标准调用: dhasa_cycles=1(12年轮盘)
            py["sudharsana_dhasa"]=_sc.get_dhasa_bhukthi(jd_local,place,dhasa_cycles=1,antardhasa_from_lord_of_dhasa_sign=True)
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
            }
        except: pass
        # ——— 6b. Pushkara Navamsa / Pushkara Bhaga（强吉分度） ———
        try:
            _pna, _pbg = charts.planets_in_pushkara_navamsa_bhaga(pp)
            py["pushkara"] = {"pushkara_navamsa_planets": _pna, "pushkara_bhaga_planets": _pbg}
        except Exception:
            pass
        # ——— 6c. Vimsopaka Bala（四档分盘力量计分） ———
        try:
            py["vimsopaka_bala"] = {
                "shadvarga": charts.vimsopaka_shadvarga_of_planets(jd_local, place),
                "sapthavarga": charts.vimsopaka_sapthavarga_of_planets(jd_local, place),
                "dhasavarga": charts.vimsopaka_dhasavarga_of_planets(jd_local, place),
                "shodhasavarga": charts.vimsopaka_shodhasavarga_of_planets(jd_local, place),
            }
        except Exception:
            pass
        # ——— 6d. 行星自然友谊矩阵（const.planet_relations 静态表） ———
        try:
            _rel_names = {0:"Sun",1:"Moon",2:"Mars",3:"Mercury",4:"Jupiter",
                          5:"Venus",6:"Saturn",7:"Rahu",8:"Ketu"}
            py["planet_relations"] = {
                _rel_names[p]: {
                    "friends": [ _rel_names[f] for f in const.friendly_planets[p] ],
                    "neutrals": [ _rel_names[n] for n in const.neutral_planets[p] ],
                    "enemies": [ _rel_names[e] for e in const.enemy_planets[p] ],
                } for p in range(9)
            }
        except Exception:
            pass
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
            # sodhaya_pindas 内部浅拷贝会就地修改 bav 行，必须给深拷贝行，保留原始 BAV
            _bav_rows=[row[:] for row in _bav_raw[0]]
            py["ashtakavarga"]={"bav":_bav_rows,"sav":_bav_raw[1],"pav":_bav_raw[2]}
            py["ashtakavarga_sodhaya"]=ashtakavarga.sodhaya_pindas([row[:] for row in _bav_raw[0]],h_to_p)
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
            # 传统 Gochara Phala 每行星单星座停留时长（Brihat Jataka/BPHS 惯例，约数）
            _gochara_duration = {
                0:"~1 month", 1:"~2.25 days", 2:"~45 days", 3:"~1 month",
                4:"~1 year", 5:"~1 month", 6:"~2.5 years",
                7:"~1.5 years", 8:"~1.5 years",
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
                    "sign_duration": _gochara_duration.get(_pid, "n/a"),
                }
                # Vedha：吉宫行运被阻碍宫内的其他行运行星所挡
                # （Parashari 惯例豁免：日月互阻、木水互阻、土罗互阻）
                if _good:
                    _vh = _bphs_vedha[_pid].get(_h_moon)
                    if _vh is not None:
                        _target = (_moon_rasi + _vh - 1) % 12
                        _vedha = []
                        for _oid, (_orasi, _odeg) in _tr_pl:
                            if _oid == _pid or _orasi != _target:
                                continue
                            if (_pid == 0 and _oid == 1) or (_pid == 1 and _oid == 0) or \
                               (_pid == 4 and _oid == 3) or (_pid == 3 and _oid == 4) or \
                               (_pid == 6 and _oid == 7) or (_pid == 7 and _oid == 6):
                                continue
                            _vedha.append(_tr_names[_oid])
                        if _vedha:
                            _entry["vedha_planets"] = _vedha
                _transit[_tr_names[_pid]] = _entry
                _tr_rasi_of[_pid] = _rasi
            py["transit"] = _transit
            py["transit_meta"] = {
                "basis": "BPHS Ch.29 Gochara",
                "reference": "natal Moon sign (Chandra Lagna) primary; natal Lagna secondary",
                "notes": "Rahu/Ketu follow the traditional extension (same as Mars: benefic 3/6/11, vedha 12/9/5); BPHS Ch.29 itself covers the seven classical grahas. Vedha exemptions: Sun-Moon, Jupiter-Mercury, Saturn-Rahu.",
                "ayanamsa": "LAHIRI",
            }
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
        # ——— 23. 输出可读性标注（对照 jhora 源码语义：数字ID→名称、裸tuple→命名对象、日期→本地时间） ———
        try:
            _pl_names = {0:"Sun",1:"Moon",2:"Mars",3:"Mercury",4:"Jupiter",5:"Venus",
                         6:"Saturn",7:"Rahu",8:"Ketu"}
            _rasi_names = const.rasi_names_en
            _nak_names = ["Ashwini","Bharani","Krittika","Rohini","Mrigashira","Ardra",
                "Punarvasu","Pushya","Ashlesha","Magha","Purva Phalguni","Uttara Phalguni",
                "Hasta","Chitra","Swati","Vishakha","Anuradha","Jyeshtha","Mula",
                "Purva Ashadha","Uttara Ashadha","Shravana","Dhanishta","Shatabhisha",
                "Purva Bhadrapada","Uttara Bhadrapada","Revati"]
            _tithi_names = ["Pratipada","Dwitiya","Tritiya","Chaturthi","Panchami","Shashthi",
                "Saptami","Ashtami","Navami","Dashami","Ekadashi","Dwadashi","Trayodashi",
                "Chaturdashi","Purnima","Pratipada","Dwitiya","Tritiya","Chaturthi","Panchami",
                "Shashthi","Saptami","Ashtami","Navami","Dashami","Ekadashi","Dwadashi",
                "Trayodashi","Chaturdashi","Amavasya"]
            _yoga_names = ["Vishkambha","Priti","Ayushman","Saubhagya","Shobhana","Atiganda",
                "Sukarman","Dhriti","Shula","Ganda","Vriddhi","Dhruva","Vyaghata","Harshana",
                "Vajra","Siddhi","Vyatipata","Variyana","Parigha","Shiva","Siddha","Sadhya",
                "Shubha","Shukla","Brahma","Indra","Vaidhriti"]
            _weekday_names = ["Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"]
            _lunar_months = ["Chaitra","Vaishakha","Jyeshtha","Ashadha","Shravana","Bhadrapada",
                "Ashwina","Kartika","Margashirsha","Pausha","Magha","Phalguna"]
            _ritus = ["Vasanta","Grishma","Varsha","Sharad","Hemanta","Shishira"]

            def _pname(x):
                try: return _pl_names.get(int(x), str(x))
                except Exception: return x

            def _rname(i, one_based=False):
                try: return _rasi_names[int(i) - (1 if one_based else 0)]
                except Exception: return i

            def _nakname(i):
                try: return _nak_names[int(i)-1]
                except Exception: return i

            def _jd_local_str(jd):
                try:
                    y,m,d,fh = utils.jd_to_gregorian(float(jd))
                    hh = int(fh); mm = int((fh-hh)*60)
                    return f"{y:04d}-{m:02d}-{d:02d} {hh:02d}:{mm:02d}"
                except Exception:
                    return jd

            def _date_arr_str(v):
                try:
                    y,m,d,fh = v[0],v[1],v[2],float(v[3])
                    hh = int(fh); mm = int((fh-hh)*60)
                    return f"{y:04d}-{m:02d}-{d:02d} {hh:02d}:{mm:02d}"
                except Exception:
                    return v

            def _pos(rasi_deg, one_based=False):
                try:
                    r,d = rasi_deg
                    r = int(r)
                    return {"rasi": r, "rasi_name": _rname(r, one_based),
                            "degree_in_rasi": round(float(d),4),
                            "longitude": round((r-(1 if one_based else 0))*30+float(d),4)}
                except Exception:
                    return rasi_deg

            def _planet_positions_list(pp):
                out = []
                for p,(r,d) in pp:
                    out.append({"planet": ("Lagna" if p==const._ascendant_symbol else _pname(p)),
                                **_pos([r,d])})
                return out

            def _keyed_by_planet(d):
                return {("Lagna" if k==const._ascendant_symbol else _pname(k)): v
                        for k,v in (d or {}).items()}

            def _dasha_periods(rows, rasi_based=False, yogini=False):
                out = []
                for row in (rows or []):
                    try:
                        lords, date_arr, years = row[0], row[1], row[2]
                        if not isinstance(lords, (list, tuple)): lords = [lords]
                        if isinstance(lords[0], (list, tuple)):
                            # 嵌套 lords（如 Sudharsana 的 [rasi,sub,subsub] 对）
                            lnames = [" / ".join(
                                (_rname(x) if rasi_based else _pname(x)) for x in group)
                                for group in lords]
                        elif rasi_based:
                            lnames = [_rname(x) for x in lords]
                        else:
                            lnames = [_pname(x) for x in lords]
                        out.append({"lords": lnames, "start": _date_arr_str(date_arr),
                                    "years": round(float(years),4)})
                    except Exception:
                        out.append(row)
                return out

            def _cell_planets(s):
                if not s: return ""
                parts = str(s).split("/")
                return "/".join("Lagna" if p==const._ascendant_symbol else _pname(p) for p in parts)

            # planets / vargas / lagna / transit 名称化
            py["planets"] = {_pname(p): _pos([r,d]) for p,(r,d) in pp[1:]}
            py["lagna"]["rasi_name"] = _rname(py["lagna"]["rasi"])
            for _vk in list(py.keys()):
                if _vk.startswith("varga_d") and isinstance(py[_vk], list) and py[_vk] and \
                   isinstance(py[_vk][0], list) and len(py[_vk][0])==2 and \
                   not isinstance(py[_vk][0][0], (list, tuple)):
                    py[_vk] = {_pname(p): _pos([r,d]) for p,(r,d) in py[_vk]}
            for _vk in ("varga_d9_64th_navamsa","varga_d3_22nd_drekkana"):
                if isinstance(py.get(_vk), dict):
                    # jhora 源码: get_64th_navamsa/get_22nd_drekkana 返回 (星座索引, 宫主星ID)
                    py[_vk] = {_pname(k): {"rasi": v[0], "rasi_name": _rname(v[0]),
                                           "lord": _pname(v[1])} for k,v in py[_vk].items()}
            for _k in ("planet_nakshatra",):
                if isinstance(py.get(_k), dict):
                    py[_k] = {_pname(k): {"nakshatra": v[0], "nakshatra_name": _nakname(v[0]),
                                          "pada": v[1], "remaining_deg": round(float(v[2]),4)}
                              for k,v in py[_k].items()}
            if isinstance(py.get("planet_speed"), dict):
                _speed_labels = ["longitude","latitude","distance_from_earth_au",
                                 "longitude_speed_deg_day","latitude_speed_deg_day","distance_speed_au_day"]
                py["planet_speed"] = {_pname(k): dict(zip(_speed_labels,
                    [round(float(x),6) if isinstance(x,(int,float)) else x for x in v]))
                    for k,v in py["planet_speed"].items()}
            # 行星状态 / 吉凶星 / 排名 / 宫位分布
            try:
                ps = py.get("planet_status", {})
                for f in ("combustion","retrograde","marana_karaka_sthana"):
                    if f in ("combustion","retrograde") and isinstance(ps.get(f), list):
                        ps[f] = [_pname(x) for x in ps[f]]
                # jhora 源码: MKS 返回 [(行星ID, 相对宫位1-12), ...]
                if isinstance(ps.get("marana_karaka_sthana"), list):
                    ps["marana_karaka_sthana"] = [
                        {"planet": _pname(x[0]), "house": x[1]}
                        for x in ps["marana_karaka_sthana"]
                        if isinstance(x, (list, tuple)) and len(x)>=2]
            except Exception: pass
            # Pushkara Navamsa / Pushkara Bhaga（jhora 源码返回行星ID列表）
            try:
                if isinstance(py.get("pushkara"), dict):
                    py["pushkara"] = {
                        "pushkara_navamsa_planets": [_pname(x) for x in py["pushkara"].get("pushkara_navamsa_planets") or []],
                        "pushkara_bhaga_planets": [_pname(x) for x in py["pushkara"].get("pushkara_bhaga_planets") or []],
                    }
            except Exception: pass
            # Vimsopaka Bala：jhora 源码 pdc[p]=[varga_count, dignity_sequence, weighted_score]
            try:
                _vims_amsa_names = {
                    "shadvarga": ["No Amsa","No Amsa","Kimsukaamsa","Vyanjanaamsa","Chaamaraamsa","Chatraamsa","Kundalaamsa"],
                    "sapthavarga": ["No Amsa","No Amsa","Kimsukaamsa","Vyanjanaamsa","Chaamaraamsa","Chatraamsa","Kundalaamsa","Mukutaamsa"],
                    "dhasavarga": ["No Amsa","No Amsa","Paarijaataamsa","Uttamaamsa","Gopuraamsa","Simhaasanaamsa","Paaraavataamsa","Devalokaamsa","Brahmalokamsa","Airaavataamsa","Sreedhaamaamsa"],
                    "shodhasavarga": ["No Amsa","No Amsa","Bhedakaamsa","Kusumaamsa","Nagapurushaamsa","Kandukaamsa","Keralaamsa","Kalpavrikshaamsa","Chandanavanaamsa","Poornachandraamsa","Uchchaisravaamsa","Dhanvantaryamsa","Sooryakaantaamsa","Vidrumaamsa","Indraasanaamsa","Golokaamsa","Sree Vallabhaamsa"],
                }
                if isinstance(py.get("vimsopaka_bala"), dict):
                    for _vk, _vnames in _vims_amsa_names.items():
                        _vsrc = py["vimsopaka_bala"].get(_vk)
                        if isinstance(_vsrc, dict):
                            py["vimsopaka_bala"][_vk] = {
                                _pname(k): {
                                    "varga_count": int(v[0]),
                                    "varga_name": _vnames[int(v[0])] if 0 <= int(v[0]) < len(_vnames) else str(v[0]),
                                    "dignity_sequence": v[1],
                                    "vimsopaka_score": round(float(v[2]), 4),
                                } for k, v in _vsrc.items()
                            }
            except Exception: pass
            # 行星自然友谊矩阵（已在计算层名称化，这里兜底）
            try:
                if isinstance(py.get("planet_relations"), dict):
                    py["planet_relations"] = {
                        _pname(p): {k: [_pname(x) for x in v] for k, v in rel.items()}
                        for p, rel in py["planet_relations"].items()}
            except Exception: pass
            for f in ("benefics","malefics"):
                if isinstance(py.get(f), list): py[f] = [_pname(x) for x in py[f]]
            if isinstance(py.get("planet_strength_ranking"), list):
                py["planet_strength_ranking"] = [_pname(x) for x in py["planet_strength_ranking"]]
            if isinstance(py.get("rasi_strength_ranking"), list):
                py["rasi_strength_ranking"] = [_rname(x) for x in py["rasi_strength_ranking"]]
            try:
                hs = py.get("houses", {})
                for f in ("planets_in_quadrants","planets_in_trines","planets_in_dushthanas"):
                    if isinstance(hs.get(f), list):
                        hs[f] = [("Lagna" if x==const._ascendant_symbol else _pname(x)) for x in hs[f]]
            except Exception: pass
            # House 分析
            try:
                ha = py.get("house_analysis", {})
                _ck_roles = ["atma","amatya","bhratri","maitri","pitri","putra","jnaati","dara"]
                if isinstance(ha.get("chara_karakas"), list):
                    ha["chara_karakas"] = {_ck_roles[i] if i<len(_ck_roles) else str(i): _pname(x)
                                           for i,x in enumerate(ha["chara_karakas"])}
                if isinstance(ha.get("yoga_kaaraka"), dict):
                    ha["yoga_kaaraka"] = {_pname(k): v for k,v in ha["yoga_kaaraka"].items()}
                if isinstance(ha.get("argala"), (tuple, list)) and len(ha["argala"])==2:
                    ha["argala"], ha["virodhargala"] = ha["argala"]
                    for _ak in ("argala","virodhargala"):
                        ha[_ak] = [[_cell_planets(c) for c in row] for row in ha[_ak]]
            except Exception: pass
            # Graha Drishti（arp=对星座 / ahp=对宫位 / app=对行星）
            try:
                if isinstance(py.get("graha_drishti"), (tuple, list)) and len(py["graha_drishti"])==3:
                    arp, ahp, app = py["graha_drishti"]
                    py["graha_drishti"] = {
                        "drishti_on_raasis": {_pname(k): [_rname(x) for x in v] for k,v in arp.items()},
                        "drishti_on_houses": {_pname(k): [int(x)+1 for x in v] for k,v in ahp.items()},
                        "drishti_on_planets": {_pname(k): [_pname(x) for x in v] for k,v in app.items()},
                    }
            except Exception: pass
            # Panchanga / Muhurtha / 时间类
            try:
                pc = py.get("panchanga", {})
                if isinstance(pc.get("tithi"), list) and len(pc["tithi"])>=3:
                    t = pc["tithi"]
                    pc["tithi"] = {"tithi": t[0], "tithi_name": _tithi_names[t[0]-1] if 1<=t[0]<=30 else t[0],
                                   "start_local_hour": t[1], "end_local_hour": t[2]}
                    if len(t)>=6:
                        pc["tithi"].update({"next_tithi": t[3],
                            "next_start_local_hour": t[4], "next_end_local_hour": t[5]})
                if isinstance(pc.get("nakshatra"), list) and len(pc["nakshatra"])>=4:
                    n = pc["nakshatra"]
                    pc["nakshatra"] = {"nakshatra": n[0], "nakshatra_name": _nakname(n[0]),
                                       "pada": n[1], "start_local_hour": n[2], "end_local_hour": n[3]}
                    if len(n)>=7:
                        pc["nakshatra"].update({"next_nakshatra": n[4],
                            "next_nakshatra_name": _nakname(n[4]), "next_pada": n[5],
                            "next_end_local_hour": n[6]})
                if isinstance(pc.get("yogam"), list) and len(pc["yogam"])>=3:
                    y = pc["yogam"]
                    pc["yogam"] = {"yoga": y[0],
                                   "yoga_name": _yoga_names[y[0]-1] if 1<=y[0]<=27 else y[0],
                                   "start_local_hour": y[1], "end_local_hour": y[2]}
                if isinstance(pc.get("karana"), (list, tuple)) and len(pc["karana"])>=3:
                    k = pc["karana"]
                    pc["karana"] = {"karana": k[0], "start_local_hour": k[1], "end_local_hour": k[2]}
                if isinstance(pc.get("vaara"), (int,float)):
                    pc["vaara"] = {"vaara_index": int(pc["vaara"]),
                                   "vaara": _weekday_names[int(pc["vaara"])%7]}
                # jhora 源码: sunrise/sunset/moonrise/moonset 返回 [本地小时(float), 时间字符串, 事件JD]
                # 注意: moonrise/moonset 的 JD 是 UTC 原始 JD, 不能直接转成本地日期;
                # 统一用"出生日期 + 本地小时"推导本地日期（可跨天/负值）
                for f in ("sunrise","sunset","moonrise","moonset"):
                    if isinstance(pc.get(f), (list, tuple)) and len(pc[f])>=3:
                        pc[f] = {"local_hour": pc[f][0], "time": pc[f][1],
                                 "date": (_dtm.datetime(year, month, day)
                                          + _dtm.timedelta(hours=float(pc[f][0]))).strftime("%Y-%m-%d")}
                for f in ("day_length","night_length"):
                    if isinstance(pc.get(f), (int,float)):
                        pc[f] = {"hours": round(float(pc[f]),3)}
            except Exception: pass
            try:
                mh = py.get("muhurtha", {})
                for f in ("rahu_kaalam","yamagandam","gulikai","abhijit"):
                    if isinstance(mh.get(f), (list, tuple)) and len(mh[f])==2:
                        mh[f] = {"start": mh[f][0], "end": mh[f][1]}
                if isinstance(mh.get("brahma_muhurtha"), (list, tuple)) and len(mh["brahma_muhurtha"])==2:
                    mh["brahma_muhurtha"] = {"start_local_hour": mh["brahma_muhurtha"][0],
                                             "end_local_hour": mh["brahma_muhurtha"][1]}
            except Exception: pass
            try:
                li = py.get("lunar_info", {})
                if isinstance(li.get("month_index"), (int,float)):
                    li["month_name"] = _lunar_months[(int(li["month_index"])-1)%12]
                if isinstance(li.get("ritu_index"), (int,float)):
                    li["ritu_name"] = _ritus[int(li["ritu_index"])%6]
            except Exception: pass
            try:
                if isinstance(py.get("vedic_time"), (list, tuple)) and len(py["vedic_time"])>=3:
                    # jhora 源码: vedic_time 返回 (ghati, phala, vighati)
                    py["vedic_time"] = {"ghati": int(py["vedic_time"][0]),
                                        "phala": int(py["vedic_time"][1]),
                                        "vighati": int(py["vedic_time"][2])}
            except Exception: pass
            try:
                if isinstance(py.get("amrita_gadiya"), (list, tuple)) and len(py["amrita_gadiya"])>=2:
                    py["amrita_gadiya"] = {"start_local_hour": py["amrita_gadiya"][0],
                                           "end_local_hour": py["amrita_gadiya"][1]}
                if isinstance(py.get("varjyam"), (list, tuple)) and len(py["varjyam"])>=2:
                    vj = py["varjyam"]
                    py["varjyam"] = {"start_local_hour": vj[0], "end_local_hour": vj[1]}
                    if len(vj)>=4:
                        py["varjyam"]["second_window"] = {"start_local_hour": vj[2], "end_local_hour": vj[3]}
            except Exception: pass
            try:
                if isinstance(py.get("thaaraabalam"), list):
                    py["thaaraabalam"] = {"good_nakshatra_indexes": py["thaaraabalam"],
                                          "good_nakshatra_names": [_nakname(x) for x in py["thaaraabalam"]]}
            except Exception: pass
            try:
                if isinstance(py.get("chandrashtama"), (list, tuple)) and len(py["chandrashtama"])==2:
                    cr, jd2 = py["chandrashtama"]
                    py["chandrashtama"] = {"rasi": cr, "rasi_name": _rname(cr, one_based=True),
                                           "next_moon_transit_date": _jd_local_str(jd2)}
            except Exception: pass
            try:
                sk = py.get("sankranti", {})
                for f in ("next","previous"):
                    if isinstance(sk.get(f), (list, tuple)) and len(sk[f])>=4:
                        d,tm,mo,td = sk[f]
                        sk[f] = {"date": f"{d[0]:04d}-{d[1]:02d}-{d[2]:02d}",
                                 "time_local_hour": tm, "tamil_month": mo, "tamil_date": td}
            except Exception: pass
            try:
                ec = py.get("eclipses", {})
                for f in ("next_solar","next_lunar"):
                    if isinstance(ec.get(f), (list, tuple)) and len(ec[f])==2:
                        etype, dates = ec[f]
                        ec[f] = {"type": etype,
                                 "begin": _date_arr_str(dates[0]) if dates else None,
                                 "maximum": _date_arr_str(dates[1]) if len(dates)>1 else None,
                                 "end": _date_arr_str(dates[2]) if len(dates)>2 else None}
            except Exception: pass
            # 力量类
            try:
                if isinstance(py.get("shadbala"), (list, tuple)) and len(py["shadbala"])>=9:
                    comps = ["sthana_bala","kaala_bala","dig_bala","cheshta_bala",
                             "naisargika_bala","drik_bala","total_shadbala",
                             "shadbala_in_rupas","shadbala_strength"]
                    py["shadbala"] = {comps[i]: {_pname(j): v for j,v in enumerate(py["shadbala"][i])}
                                      for i in range(len(comps))}
            except Exception: pass
            try:
                if isinstance(py.get("bhava_bala"), (list, tuple)) and len(py["bhava_bala"])>=3:
                    py["bhava_bala"] = {
                        "bhava_bala": {f"house_{i+1}": v for i,v in enumerate(py["bhava_bala"][0])},
                        "bhava_bala_rupas": {f"house_{i+1}": v for i,v in enumerate(py["bhava_bala"][1])},
                        "bhava_bala_strength": {f"house_{i+1}": v for i,v in enumerate(py["bhava_bala"][2])}}
            except Exception: pass
            try:
                if isinstance(py.get("bhava_drishti_bala"), list):
                    py["bhava_drishti_bala"] = {f"house_{i+1}": v
                                                for i,v in enumerate(py["bhava_drishti_bala"])}
            except Exception: pass
            try:
                if isinstance(py.get("bhaava_madhya"), list):
                    py["bhaava_madhya"] = {f"house_{i+1}_madhya": v
                                           for i,v in enumerate(py["bhaava_madhya"])}
            except Exception: pass
            try:
                if isinstance(py.get("pancha_vargeeya_bala"), dict):
                    py["pancha_vargeeya_bala"] = {_pname(k): v
                                                  for k,v in py["pancha_vargeeya_bala"].items()}
            except Exception: pass
            # Ashtakavarga
            try:
                av = py.get("ashtakavarga", {})
                if isinstance(av.get("bav"), list):
                    av["bav"] = {(_pname(i) if i<7 else "Lagna"): v
                                 for i,v in enumerate(av["bav"])}
                if isinstance(av.get("sav"), list):
                    av["sav"] = {_rname(i): v for i,v in enumerate(av["sav"])}
                if isinstance(av.get("pav"), list):
                    av["pav"] = {(_pname(i) if i<7 else "Lagna"): {
                        (_pname(j) if j<7 else "Lagna" if j==7 else "total"): row
                        for j,row in enumerate(rows)} for i,rows in enumerate(av["pav"])}
            except Exception: pass
            try:
                if isinstance(py.get("ashtakavarga_sodhaya"), (list, tuple)) and len(py["ashtakavarga_sodhaya"])==3:
                    rp, gp, sp = py["ashtakavarga_sodhaya"]
                    py["ashtakavarga_sodhaya"] = {
                        "raasi_pindas": {_pname(i): v for i,v in enumerate(rp)},
                        "graha_pindas": {_pname(i): v for i,v in enumerate(gp)},
                        "sodhya_pindas": {_pname(i): v for i,v in enumerate(sp)}}
            except Exception: pass
            # Dosha
            try:
                ds = py.get("dosha", {})
                if isinstance(ds.get("manglik"), (list, tuple)) and len(ds["manglik"])>=3:
                    ds["manglik"] = {"is_manglik": ds["manglik"][0],
                                     "exceptions_applied": ds["manglik"][1],
                                     "exception_indices": ds["manglik"][2]}
                if isinstance(ds.get("guru_chandala"), (list, tuple)) and len(ds["guru_chandala"])==2:
                    ds["guru_chandala"] = {"is_guru_chandala": ds["guru_chandala"][0],
                                           "jupiter_is_stronger": ds["guru_chandala"][1]}
                if isinstance(ds.get("pitru_dosha"), (list, tuple)) and len(ds["pitru_dosha"])==2:
                    ds["pitru_dosha"] = {"has_pitru_dosha": ds["pitru_dosha"][0],
                                         "active_rule_indices": ds["pitru_dosha"][1]}
            except Exception: pass
            # Karakas
            try:
                if isinstance(py.get("naisargika_karakas"), list):
                    py["naisargika_karakas"] = {f"house_{i+1}": _pname(x)
                                                for i,x in enumerate(py["naisargika_karakas"])}
                if isinstance(py.get("sthira_karakas"), list):
                    py["sthira_karakas"] = [_pname(x) for x in py["sthira_karakas"]]
            except Exception: pass
            # 特殊点 / Upagraha / Sphuta / Saham
            for _fk in ("sree_lagna","pranapada_lagna","bhrigu_bindhu","bhava_lagna",
                        "hora_lagna","ghati_lagna","upagrahas","non_solar_upagrahas","sphuta"):
                try:
                    if isinstance(py.get(_fk), dict):
                        py[_fk] = {k: _pos(v, one_based=False) for k,v in py[_fk].items()}
                    elif isinstance(py.get(_fk), (list, tuple)) and len(py[_fk])==2:
                        py[_fk] = _pos(py[_fk], one_based=False)
                except Exception: pass
            try:
                if isinstance(py.get("saham"), dict):
                    py["saham"] = {k: {"longitude": round(float(v),4),
                                       "rasi": int(float(v)//30),
                                       "rasi_name": _rname(int(float(v)//30))}
                                   for k,v in py["saham"].items()}
            except Exception: pass
            # Dasha 系列
            try:
                if isinstance(py.get("vimshottari"), (list, tuple)) and len(py["vimshottari"])==2:
                    bal, rows = py["vimshottari"]
                    py["vimshottari"] = {
                        "balance": {"years": bal[0], "months": bal[1], "days": bal[2]},
                        "periods": _dasha_periods(rows)}
            except Exception: pass
            # vimshottari_running：jhora get_running_dhasa_for_given_date 返回
            # [[lords_tuple, start_tuple, end_tuple], ...]（1级Maha..5级Prana）
            try:
                if isinstance(py.get("vimshottari_running"), (list, tuple)):
                    _lvl_names = {1:"Maha Dasha",2:"Antara Dasha (Bhukthi)",3:"Pratyantara Dasha",
                                  4:"Sukshma Dasha",5:"Prana Dasha",6:"Deha-antara Dasha"}
                    _ydur = py.get("dhasa_year_duration") or drik.dhasa_year_duration(jd=jd_local, place=place)
                    _out = []
                    for _row in py["vimshottari_running"]:
                        if not (isinstance(_row, (list, tuple)) and len(_row) >= 3):
                            continue
                        _lords, _st, _en = _row[0], _row[1], _row[2]
                        if not isinstance(_lords, (list, tuple)): _lords = [_lords]
                        _lvl = len(_lords)
                        def _run_date(t):
                            try:
                                y, m, d, fh = t
                                hh = int(float(fh)); mm = int((float(fh)-hh)*60)
                                return f"{int(y):04d}-{int(m):02d}-{int(d):02d} {hh:02d}:{mm:02d}"
                            except Exception:
                                return t
                        try:
                            _st_jd = utils.julian_day_number(drik.Date(_st[0], _st[1], _st[2]), (float(_st[3]), 0, 0))
                            _en_jd = utils.julian_day_number(drik.Date(_en[0], _en[1], _en[2]), (float(_en[3]), 0, 0))
                            _yrs = round((_en_jd - _st_jd) / float(_ydur), 4)
                        except Exception:
                            _yrs = None
                        _out.append({
                            "level": _lvl,
                            "level_name": _lvl_names.get(_lvl, str(_lvl)),
                            "lords": [_pname(x) for x in _lords],
                            "start": _run_date(_st),
                            "end": _run_date(_en),
                            "years": _yrs,
                        })
                    py["vimshottari_running"] = _out
            except Exception: pass
            for _dk, _rasi_based, _yogini in [
                    ("ashtottari_dasha",False,False), ("yogini_dasha",False,False),
                    ("narayana_dasha",True,False),
                    ("chara_dasha",True,False), ("kalachakra_dhasa",True,False),
                    ("sudharsana_dhasa",True,False)]:
                try:
                    if isinstance(py.get(_dk), list):
                        py[_dk] = _dasha_periods(py[_dk], rasi_based=_rasi_based, yogini=_yogini)
                except Exception: pass
            try:
                if isinstance(py.get("sudharsana_chakra"), (list, tuple)):
                    py["sudharsana_chakra"] = [
                        [[int(c[0]), _cell_planets(c[1])] if isinstance(c, (list, tuple)) and len(c)>=2 else c
                         for c in row]
                        for row in py["sudharsana_chakra"]]
            except Exception: pass
            # Tajaka
            try:
                tj = py.get("tajaka", {})
                if isinstance(tj.get("varsha_pravesh"), (list, tuple)):
                    _vp = tj["varsha_pravesh"]
                    if len(_vp)>=2:
                        _pd = _vp[1][0]
                        tj["varsha_pravesh"] = {
                            "chart": _planet_positions_list(_vp[0]),
                            "pravesh_date": f"{_pd[0]:04d}-{_pd[1]:02d}-{_pd[2]:02d}",
                            "pravesh_time": _vp[1][1],
                        }
                    else:
                        tj["varsha_pravesh"] = {"chart": _planet_positions_list(_vp[0])}
            except Exception: pass
            try:
                ty = py.get("tajaka_yoga", {})
                if isinstance(ty.get("nakta"), (list, tuple)) and len(ty["nakta"])==2:
                    ty["nakta"] = {"lord": _pname(ty["nakta"][0]),
                                   "planets": [_pname(x) for x in ty["nakta"][1]]}
                if isinstance(ty.get("ithasala"), list):
                    ty["ithasala"] = [{"planet_a": _pname(x[0]), "planet_b": _pname(x[1]),
                                       "type": x[2] if len(x)>2 else None} for x in ty["ithasala"]]
                for _tf in ("eesarpha","manahoo","kamboola"):
                    if isinstance(ty.get(_tf), list):
                        ty[_tf] = [[_pname(x) for x in row] for row in ty[_tf]]
                if isinstance(ty.get("yamaya"), list):
                    ty["yamaya"] = [[_pname(x) for x in row] for row in ty["yamaya"]]
            except Exception: pass
            # 行星尊贵补 rasi_name
            try:
                pdg = py.get("planet_dignity", {})
                for _lst_name in ("exalted","debilitated"):
                    for _e in pdg.get(_lst_name, []) or []:
                        _e["rasi_name"] = _rname(_e["rasi"])
            except Exception: pass
            # 行运补 nakshatra_name
            try:
                for _t in (py.get("transit") or {}).values():
                    if isinstance(_t, dict) and "nakshatra" in _t:
                        _t["nakshatra_name"] = _nakname(_t["nakshatra"])
            except Exception: pass
            py["meta"] = {
                "planet_id_to_name": _pl_names,
                "note": "行星一律用名称; rasi/nakshatra 均附英文名; 所有日期为出生地本地时间"
                        "(格式 YYYY-MM-DD HH:MM, 不再逐行标注 local); panchanga 时间以本地小时表示, 大于24表示次日;"
                        "dasha periods 的 lords 为名称列表(rasi dasha 为星座名)。"}
        except Exception:
            pass
        # ——— engine 已在上文各阶段累计 ———
    except Exception as e:
        result["pyjhora_error"]=str(e)
        result["engine"]=""
    result["_hint"]=("PyJHora全量:Panchanga(含月出/落+日/夜长+7日星宿)/Muhurtha/VedicTime/Dasha(Vimshottari+当前运行阶梯Maha→Antara→Pratyantara→Sukshma→Prana/Ashtottari/Yogini/Narayana/Chara/Kalachakra/Sudharsana)/House(CharaKarakas/Marakas/函益/Argala/Brahma/Rudra/YogaKaaraka)/行星强度排名/吉凶星/GrahaDrishti/行星状态(combustion/retrograde/MKS)/PushkaraNavamsa+PushkaraBhaga/VimsopakaBala四档(Shadvarga/Sapthavarga/Dhasavarga/Shodhasavarga)/行星自然友谊矩阵/行星擢升落陷(planet_dignity)/宫位分布/Shadbala+Bhavabala+BhavaDrishti+PanchaVargeeya/特殊格局(RajaYoga+YogaDetails)/瑜伽(Sunapha/Anapha/Duradhara/GajaKesari/Vesi/Vosi/Ubhayachara)/Ashtakavarga(含SodhayaPindas)/Dosha8(含Ghata)/Arudha/全部分盘(D2-D60)+64thNavamsa+22ndDrekkana/逐星Nakshatra+速度+GrahaYuddha+BhaavaMadhya/SpecialLagnas(Sree/Pranapada/BhriguBindhu/Bhava/Hora/Ghati)/Upagrahas(含Kaala/Mrityu/Gulika等非太阳余炁)/农历/季节/Naisargika+SthiraKarakas/VivahaChakra/Chandrashtama/Tajaka年运+TajakaYogas/全19Saham/Eclipses/Thaaraabalam/AmritaGadiya/Varjyam/Sankranti/DhasaYearDuration/Sphuta8(Tri/Chatur/Prana/Deha/Mrityu/Beeja/Yogi/Avayogi)。"
        "Gochara行运(九曜当前西达尔经度/星座/度数/星宿+分度/月亮与上升双基准宫位/BPHS第29章吉凶+Vedha阻碍/行运对本命Drishti/"
        "SadeSati+AshtamaShani+ArdhaAshtama+KantakaShani)/Lahiri岁差。"
        "输出已可读化:行星/星座/星宿一律带名称,日期为本地时间(YYYY-MM-DD HH:MM,不再逐行标注local),"
        "panchanga为本地小时(负=前一天/>24=次日),Dasha/Shadbala/Ashtakavarga等均为命名结构。"
        "自探索:dir(jhora)")
    return result
