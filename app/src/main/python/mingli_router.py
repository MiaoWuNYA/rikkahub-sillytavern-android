"""
mingli_router — 命理统一入口
AI 不写排盘代码,不读引擎文档。
"""
import json, sys, os
_bridge = None

def _js(lib, code):
    if _bridge:
        try: return _bridge.evalJavascript(lib, code)
        except Exception as e: return json.dumps({"error": f"JS: {e}"}, ensure_ascii=False)
    return json.dumps({"error": "bridge not available"})

def _js_load(lib):
    if _bridge:
        try: return _bridge.evalJavascript(lib, "")
        except Exception as e: return json.dumps({"error": f"load: {e}"}, ensure_ascii=False)
    return json.dumps({"error": "bridge not available"})

# ===== 塔罗 =====
def _tarot(spread="celtic-cross", seed=None, question_type=None, kaabalah=False):
    from arcanite.core import TarotDeck
    from arcanite.core.spread import load_spread
    from tarot_elemental_engine import ElementalDignityEngine as EE
    deck = TarotDeck.load(system="tarot")
    sp = load_spread(spread)
    drawn = deck.draw(len(sp.positions), seed=seed)
    cards = []
    SUIT_URL={"wands":"W","cups":"C","swords":"S","pentacles":"P"}
    RANK_URL={1:"01",2:"02",3:"03",4:"04",5:"05",6:"06",7:"07",8:"08",9:"09",10:"10",
               11:"J1",12:"J2",13:"QU",14:"KI"}
    for i, dc in enumerate(drawn):
        is_rev = dc.orientation.value == "reversed"
        if dc.suit=="major_arcana" or dc.suit=="major":
            img=f"https://steve-p.org/cards/pix/RWSa-T-{dc.card_number:02d}.png"
        else:
            s=SUIT_URL.get(dc.suit,"T")
            n=RANK_URL.get(dc.card_number,f"{dc.card_number:02d}")
            img=f"https://steve-p.org/cards/pix/RWSa-{s}-{n}.png"
        cards.append({
            "position": sp.positions[i].rag_mapping,
            "card_number": dc.card_number, "card_name": dc.card_name,
            "suit": dc.suit, "orientation": dc.orientation.value,
            "core_meaning": dc.get_core_meaning(reversed=is_rev),
            "interpretation": dc.get_interpretation(sp.positions[i].rag_mapping, reversed=is_rev),
            "question_context": dc.get_question_context(question_type, reversed=is_rev) if question_type else None,
            "elemental": dc.get_elemental_correspondences(),
            "symbols": dict(dc.get_symbols()),
            "affirmations": dc.get_affirmations(),
            "journaling_prompts": dc.get_journaling_prompts(),
            "relationships": dc.get_relationships(),
            "archetype": getattr(dc, 'archetype', None),
            "reading_aspects": getattr(dc, 'reading_aspects', []),
            "contextual_meanings": getattr(dc, 'contextual_meanings', {}),
            "description": getattr(dc, 'description', {}),
            "waite_meaning": {"upright":dc.get_waite_meaning("upright"), "reversed":dc.get_waite_meaning("reversed")},
            "tk_meaning": {"upright_en":dc.get_tk_meaning("upright","en"), "upright_zh":dc.get_tk_meaning("upright","zh"),
                           "reversed_en":dc.get_tk_meaning("reversed","en"), "reversed_zh":dc.get_tk_meaning("reversed","zh")},
            "meditation_focus": (dc.raw_data or {}).get("meditation_focus") if hasattr(dc,'raw_data') else None,
            "image_url": img,
        })
    result = {
        "system": "tarot", "engine": "arcanite-unified", "seed": seed,
        "spread": {"id": spread, "positions": [p.rag_mapping for p in sp.positions]},
        "cards": cards, "ee_analysis": EE.full_analysis(drawn),
        "_hint": "arcanite内置18字段已全量。Kaabalah(JS): 22塔罗导出+5牌桌+7牌阵+卡巴拉对应+777表。自探索: Object.keys(Kaabalah)"
    }
    if kaabalah:
        _js_load("kaabalah-engine")
        result["kaabalah"] = [
            _js("kaabalah-engine", f"JSON.stringify(Kaabalah.getTarotCorrespondenceProfile({{tarotCardNumber:Kaabalah.getTarotCardNumber({{tarotCardName:'{c.card_name}'}}).cardNumber}}))")
            for c in drawn
        ]
    return result

# ===== 雷诺曼 =====
def _lenormand(spread="line-5", seed=None):
    from arcanite.core import LenormandDeck
    from arcanite.core.spread import load_spread
    d = LenormandDeck.load()
    sp = load_spread(spread, system="lenormand")
    items = d.draw_with_data(len(sp.positions), seed=seed)
    cards = []
    for i, item in enumerate(items):
        cards.append({
            "position": sp.positions[i].name, "card_id": item.card_id,
            "card_name": item.card_name, "core": item.get_core(),
            "timing": item.get_timing(), "modifier": item.get_modifier_behavior(),
            "as_person": item.get_as_person(),
            "playing_card": item.get_playing_card(),
            "topic_contexts": item.get_topic_contexts(),
            "line_reading": item.get_line_reading(),
            "combination_grammar": item.get_combination_grammar(),
            "combinations": item.get_combinations(),
            "grand_tableau": item.get_grand_tableau(),
        })
    from lenormand_engine import LenormandFateEngine as FE
    return {
        "system": "lenormand", "engine": "arcanite-unified", "seed": seed,
        "spread_positions": [p.name for p in sp.positions],
        "cards": cards,
        "statistics": d.analyze_draw(items),
        "karmic_mirrors": {i: FE.parse_karmic_mirrors(sp.positions, items) for i in [0]},
        "fe_portrait": FE.parse_portrait_3x3_cage(items, spread),
        "_hint": "arcanite 36张语义getter已全量。FE引擎另有: GT_portrait/骑士步/镜像/反射。自探索: dir(LenormandFateEngine)"
    }

# ===== 八字 =====
def _bazi(year, month, day, hour, gender=1):
    from lunar_python import Solar
    s = Solar.fromYmdHms(year, month, day, hour, 0, 0)
    l = s.getLunar()
    ec = l.getEightChar()
    yun = ec.getYun(gender)
    dayun_list = []
    for dy in yun.getDaYun():
        gz = dy.getGanZhi()
        if gz: dayun_list.append({"ganzhi":gz, "start_age":dy.getStartAge(), "end_age":dy.getEndAge(),
                                   "liunian":[ln.getGanZhi() for ln in dy.getLiuNian()]})
    result = {
        "system":"bazi","engine":"lunar_python",
        "four_pillars":{
            "year":{"gan":ec.getYearGan(),"zhi":ec.getYearZhi(),"ganzhi":ec.getYear(),"wuxing":ec.getYearWuXing(),"nayin":ec.getYearNaYin(),"xunkong":ec.getYearXunKong(),"hide_gan":ec.getYearHideGan(),"shishen":ec.getYearShiShenGan(),"dishi":ec.getYearDiShi()},
            "month":{"gan":ec.getMonthGan(),"zhi":ec.getMonthZhi(),"ganzhi":ec.getMonth(),"wuxing":ec.getMonthWuXing(),"nayin":ec.getMonthNaYin(),"xunkong":ec.getMonthXunKong(),"hide_gan":ec.getMonthHideGan(),"shishen":ec.getMonthShiShenGan()},
            "day":{"gan":ec.getDayGan(),"zhi":ec.getDayZhi(),"ganzhi":ec.getDay(),"wuxing":ec.getDayWuXing(),"nayin":ec.getDayNaYin(),"xunkong":ec.getDayXunKong(),"hide_gan":ec.getDayHideGan(),"shishen":ec.getDayShiShenGan(),"dishi":ec.getDayDiShi()},
            "time":{"gan":ec.getTimeGan(),"zhi":ec.getTimeZhi(),"ganzhi":ec.getTime(),"wuxing":ec.getTimeWuXing(),"nayin":ec.getTimeNaYin(),"xunkong":ec.getTimeXunKong(),"hide_gan":ec.getTimeHideGan(),"shishen":ec.getTimeShiShenGan(),"dishi":ec.getTimeDiShi()},
        },
        "dayun":dayun_list,"start_age":yun.getStartYear(),"gender":gender,
        "solar":s.toFullString(),"lunar":l.toFullString(),
        "jieqi":{k:str(v) for k,v in (l.getJieQiTable() or {}).items()},
    }
    try:
        sys.path.insert(0, os.path.dirname(__file__))
        from bazi_china import datas, shengxiao, sizi, luohou, ganzhi
        yg,yz=ec.getYearGan(),ec.getYearZhi(); dg,dz=ec.getDayGan(),ec.getDayZhi()
        tg,tz=ec.getTimeGan(),ec.getTimeZhi(); mg=ec.getMonthGan(); mz=ec.getMonthZhi()
        result["extra"] = {
            "nayin":{"year":datas.nayins.get((yg,yz),""),"day":datas.nayins.get((dg,dz),""),"time":datas.nayins.get((tg,tz),"")},
            "rizhu":datas.rizhus.get(dg+dz,""),
            "minggong":datas.minggongs.get(ec.getMingGong()[-1:],""),
            "shengong":datas.minggongs.get(ec.getShenGong()[-1:],""),
            "day_shen":{k:v.get(dz,"") for k,v in datas.day_shens.items()},
            "g_shen":{k:v.get(dg,"") for k,v in datas.g_shens.items()},
            "sizi":{k: v for k,v in list(sizi.summarys.items())[:5]},
            "ganzhi_gan":ganzhi.Gan[:10], "ganzhi_zhi":ganzhi.Zhi[:12],
        }
        result["engine"] += " + bazi_china"
        result["_hint"] = "lunar_python全字段已返回。bazi_china另有: ganzi干支/luohou飞星/shengxiao生肖/sizi古诀/yue月令/cnlunar黄历。自探索: dir(datas)"
    except Exception:
        result["_hint"] = "lunar_python已返回排盘+大运。bazi_china不可用(仅APK内)"
    return result

# ===== 紫微 =====
def _ziwei(year,month,day,hour,gender="male",engine="iztro"):
    date_str=f"{year}-{month:02d}-{day}"
    result={"system":"ziwei","engine":engine}
    if engine in ("iztro","all"):
        _js_load("iztro-engine")
        result["iztro"]=_js("iztro-engine",f"JSON.stringify(Iztro.astro.bySolar('{date_str}',{hour},'{gender}'))")
    if engine in ("nihai","all"):
        _js_load("ziwei-nihai")
        result["nihai"]=_js("ziwei-nihai",f"JSON.stringify(ZiweiNihai.generateChart({{year:{year},month:{month},day:{day},hour:{hour},gender:'{gender}'}}))")
    if engine in ("python","all"):
        try:
            sys.path.insert(0,os.path.dirname(__file__))
            from ziwei_paipan import by_solar
            result["ziwei_paipan"]=str(by_solar(date_str,hour,gender))
        except Exception as e: result["ziwei_paipan_error"]=str(e)
    result["_hint"]="Iztro全量已返回。另:surroundedPalaces三方四正/horoscope大限/soul+body。ZiweiNihai含倪海夏天纪+古籍。自探索:Object.keys(Iztro.astro)/dir(ziwei_paipan)"
    return result

# ===== 现代西洋占星 =====
def _western_astro(year,month,day,hour,tz,lat,lon,depth="standard"):
    date_str=f"{year}-{month:02d}-{day}"
    _js_load("natalengine-engine")
    natal=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateAstrology('{date_str}',{hour},{tz},{lat},{lon}))")
    result={"system":"western_astrology","engine":"natalengine-js","natal":natal}
    # Caelus: 本命盘(宫位+逆行+尊贵) standard即提供
    _js_load("caelus-engine")
    c=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%sT%02d:00:00+08:00');var chart=e.chartAt(jd,%f,%f,{});JSON.stringify({signature:Caelus.chartSignature(chart),patterns:Caelus.detectPatterns(chart),bodies:chart.bodies,cusps:chart.cusps,angles:chart.angles,lots:Caelus.lots(e,jd,%f,%f),isDay:Caelus.isDayChart(e,jd,%f,%f),voidOfCourse:Caelus.voidOfCourse(e,jd)})"%(date_str,hour,lat,lon,lat,lon,lat,lon))
    result["caelus"]=c
    result["engine"]+="+Caelus"
    result["_hint"]="NatalEngine已返回日月升+7星+元素+相位+合盘+ACG。Caelus已返回12宫位+逆行+尊贵+格局+7点+空亡。"
    "自探索:Object.keys(Caelus)含推运7种/合盘3种/行运12/恒星2/ACG/赤纬/越界/映点/调和盘"
    if depth=="deep":
        c2=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%sT%02d:00:00+08:00');var chart=e.chartAt(jd,%f,%f,{});JSON.stringify({firdaria:Caelus.firdariaAt(e,jd,jd,%f,%f),profections:Caelus.profectionAt(e,jd,jd,%f,%f),primaryDirections:Caelus.primaryDirections(e,jd,%f,%f),solarArc:Caelus.solarArc(e,jd,jd),declinationAspects:Caelus.declinationAspects(e,Caelus.DEFAULT_BODIES,jd,1),outOfBounds:Caelus.outOfBounds(e,'moon',jd)})"%(date_str,hour,lat,lon,lat,lon,lat,lon,lat,lon))
        result["caelus_deep"]=c2
        result["engine"]+="+deep"
    return result

# ===== 传统西洋占星 =====
def _traditional_astro(year,month,day,hour,tz_offset,lat,lon):
    from flatlib.chart import Chart
    from flatlib.datetime import Datetime
    from flatlib.geopos import GeoPos
    from flatlib import const
    from flatlib.dignities.essential import ruler, exalt, score as ess_score, isPeregrine, almutem, getInfo
    from flatlib.dignities.accidental import AccidentalDignity
    from flatlib.tools.arabicparts import getPart, partLon
    from flatlib.protocols.temperament import Temperament
    from flatlib.protocols.almutem import compute as almutem_compute
    from flatlib.predictives.profections import compute as prof_compute
    from flatlib.aspects import hasAspect
    dt=Datetime(f"{year}/{month:02d}/{day}",f"{hour}:00",tz_offset)
    pos=GeoPos(lat,lon)
    chart=Chart(dt,pos,IDs=const.LIST_OBJECTS)
    is_day=chart.isDiurnal()
    asc_lon=chart.getAngle(const.ASC).lon
    # 行星数据收集
    planets={}
    for name in const.LIST_OBJECTS:
        try:
            o=chart.getObject(name)
            planets[name.lower()]={"lon":o.lon,"sign":o.sign,"signlon":o.signlon,
                "retrograde":o.isRetrograde(),"speed":o.meanMotion()}
        except: pass
    # 计算每星宫位
    for pname,pdata in planets.items():
        for i in range(1,13):
            h=chart.getHouse(getattr(const,f"HOUSE{i}"))
            if h.inHouse(pdata["lon"]):
                pdata["house"]=i
                break
    objs={}
    for name in const.LIST_OBJECTS[:7]:
        try:
            o=chart.getObject(name)
            objs[name.lower()]={"sign":o.sign,"signlon":o.signlon,"lon":o.lon,
                "house":next(i for i in range(1,13) if chart.getHouse(getattr(const,f"HOUSE{i}")).inHouse(o.lon)),
                "retrograde":o.isRetrograde(),"ruler":ruler(o.sign),"exalt":exalt(o.sign),
                "score":ess_score(name,o.sign,o.signlon),"peregrine":isPeregrine(name,o.sign,o.signlon)}
        except: pass
    houses={}
    for i in range(1,13):
        try:
            h=chart.getHouse(getattr(const,f"HOUSE{i}"))
            houses[f"house{i}"]={"sign":h.sign,"lon":h.lon}
        except: pass
    # 本质尊贵
    dignities={}
    for name in const.LIST_OBJECTS[:7]:
        try:
            o=chart.getObject(name)
            dignities[name.lower()]=getInfo(o.sign,o.signlon)
        except: pass
    # 偶然尊贵
    accidental={}
    for name in const.LIST_OBJECTS[:7]:
        try:
            o=chart.getObject(name)
            ad=AccidentalDignity(o,chart)
            accidental[name.lower()]={"score":ad.score(),"combust":ad.isCombust(),"cazimi":ad.isCazimi(),
                "orientality":ad.orientality(),"augmenting_light":ad.isAugmentingLight(),
                "under_sun":ad.isUnderSun(),"voc":ad.isVOC(),"joy_house":ad.inHouseJoy()}
        except: pass
    # In Sect / Out of Sect
    diurnal_planets={"sun","jupiter","saturn"}
    nocturnal_planets={"moon","venus","mars"}
    sect={}
    for pname in planets:
        if pname=="mercury": sect[pname]="common"
        elif is_day and pname in diurnal_planets: sect[pname]="in_sect"
        elif not is_day and pname in nocturnal_planets: sect[pname]="in_sect"
        else: sect[pname]="out_of_sect"
    # 全部Lots (传统公式: ASC + A - B)
    def _lot(a_lon,b_lon): return (asc_lon + a_lon - b_lon) % 360
    try:
        s_lon=planets["sun"]["lon"]; m_lon=planets["moon"]["lon"]
        me_lon=planets["mercury"]["lon"]; v_lon=planets["venus"]["lon"]
        ma_lon=planets["mars"]["lon"]; j_lon=planets["jupiter"]["lon"]
        sa_lon=planets["saturn"]["lon"]
        lots={}
        if is_day:
            lots["fortune"]=_lot(m_lon,s_lon); lots["spirit"]=_lot(s_lon,m_lon)
            lots["eros"]=_lot(ma_lon,v_lon); lots["courage"]=_lot(ma_lon,v_lon)
            lots["basis"]=_lot(ma_lon,sa_lon); lots["nemesis"]=_lot(v_lon,sa_lon)
        else:
            lots["fortune"]=_lot(s_lon,m_lon); lots["spirit"]=_lot(m_lon,s_lon)
            lots["eros"]=_lot(v_lon,ma_lon); lots["courage"]=_lot(v_lon,ma_lon)
            lots["basis"]=_lot(sa_lon,ma_lon); lots["nemesis"]=_lot(sa_lon,v_lon)
        lots["necessity"]=_lot(sa_lon,me_lon); lots["victory"]=_lot(j_lon,ma_lon)
        lots["marriage_m"]=_lot(v_lon,sa_lon); lots["marriage_f"]=_lot(sa_lon,v_lon)
        lots["children_m"]=_lot(v_lon,j_lon); lots["children_f"]=_lot(j_lon,v_lon)
        lots["father"]=_lot(sa_lon,s_lon)
        lots["mother"]=_lot(m_lon,v_lon) if is_day else _lot(v_lon,m_lon)
        lots["friends"]=_lot(m_lon,me_lon)
    except: lots={}
    # 传统特殊结构检测
    configs=[]
    try:
        bnames=["sun","moon","mercury","venus","mars","jupiter","saturn"]
        brows={n:chart.getObject(getattr(const,n.upper())) for n in bnames}
        bkeys=list(bnames)
        # Collection of Light
        for c in bkeys:
            asp=[t for t in bkeys if t!=c and hasAspect(brows[c],brows[t],const.MAJOR_ASPECTS)]
            for i,a1 in enumerate(asp):
                for a2 in asp[i+1:]:
                    if not hasAspect(brows[a1],brows[a2],const.MAJOR_ASPECTS):
                        configs.append({"type":"collection_of_light","collector":c,"planets":[a1,a2]})
        # Besiegement
        for c in bkeys:
            besieging=[m for m in ["mars","saturn"] if m!=c and hasAspect(brows[m],brows[c],const.MAJOR_ASPECTS)]
            if len(besieging)>=2:
                configs.append({"type":"besiegement","planet":c,"besiegers":besieging})
    except: pass
    # 气质
    try:
        t=Temperament(chart)
        temperament={"score":t.getScore(),"factors":t.getFactors()}
    except: temperament={}
    # Almutem
    try: alm=almutem_compute(chart)
    except: alm={}
    # 小限
    try:
        prof=prof_compute(chart,dt)
        prof_asc=str(prof.getAngle(const.ASC))
    except: prof_asc=None
    return {"system":"traditional_astrology","engine":"flatlib",
        "objects":objs,"houses":houses,
        "asc":str(chart.getAngle(const.ASC)),"mc":str(chart.getAngle(const.MC)),
        "dignities":dignities,"accidental":accidental,
        "sect":{"is_day":is_day,"planets":sect},
        "temperament":temperament,"almutem":str(alm),
        "arabic_parts":lots,"profection_asc":prof_asc,
        "configurations":configs,
        "_hint":"flatlib已全量:本质尊贵/偶然尊贵/Sect/In-Out/Lots全/小限/Almutem/气质/传统结构检测。"
        "Zodiacal Releasing当前不支持。Firdaria/Caelus(deep)。自探索:dir(flatlib)"}

# ===== 吠陀 =====
def _vedic(year,month,day,hour,tz,lat=None,lon=None,depth="standard"):
    date_str=f"{year}-{month:02d}-{day}"
    result={"system":"vedic"}
    # ===== 默认主力: PyJHora (Python/Chaquopy, 按路由文档1:1补全) =====
    try:
        from jhora import const, utils
        from jhora.panchanga import drik
        from jhora.horoscope.chart import house, strength, raja_yoga, yoga, dosha, ashtakavarga, arudhas
        from jhora.horoscope.dhasa.graha import vimsottari
        place=drik.Place("loc",lat or 0,lon or 0,float(tz))
        jd_local=utils.julian_day_number(drik.Date(year,month,day),(hour,0,0))
        # 1. 排盘
        pp=drik.dhasavarga(jd_local,place,1)
        asc_raw=drik.ascendant(jd_local,place)
        asc_house,asc_long=drik.dasavarga_from_long(asc_raw[0]*30+asc_raw[1],1)
        pp+=[[const._ascendant_symbol,(asc_house,asc_long)]]
        p_to_h={p:h for p,(h,_) in pp}
        h_to_p=utils.get_house_planet_list_from_planet_positions(pp)
        result["pyjhora"]={"planets":str(pp[:9]),"lagna":{"rasi":asc_raw[0],"deg":asc_raw[1],"nak":asc_raw[2],"pada":asc_raw[3]}}
        # 2. Panchanga 五支
        result["panchanga"]={
            "tithi": drik.tithi(jd_local,place),
            "nakshatra": drik.nakshatra(jd_local,place),
            "yogam": drik.yogam(jd_local,place),
            "karana": drik.karana(jd_local,place),
            "vaara": drik.vaara(jd_local,place),
            "sunrise": drik.sunrise(jd_local,place),
            "sunset": drik.sunset(jd_local,place),
        }
        # 3. 宫位分析
        result["houses"]={
            "planets_in_quadrants": house.get_planets_in_quadrants(p_to_h),
            "planets_in_trines": house.get_planets_in_trines(p_to_h),
            "planets_in_dushthanas": house.get_planets_in_dushthanas(p_to_h),
        }
        # 4. Shadbala + Bhava Bala
        result["shadbala"]=str(strength.shad_bala(jd_local,place))
        result["bhava_bala"]=str(strength.bhava_bala(jd_local,place))
        # 5. Ashtakavarga
        result["ashtakavarga"]=str(ashtakavarga.get_ashtaka_varga(p_to_h))
        # 6. Raja Yoga + 全Yoga
        result["raja_yoga"]=str(raja_yoga.get_raja_yoga_details(jd_local,place))
        result["yoga_details"]=str(yoga.get_yoga_details(jd_local,place))
        # 7. Dosha
        result["dosha"]={"manglik":str(dosha.manglik(pp))}
        # 8. Arudha
        result["arudha"]=str(arudhas.bhava_arudhas_from_planet_positions(pp))
        # 9. Vimshottari Dasha
        result["vimshottari"]=str(vimsottari.get_vimsottari_dhasa_bhukthi(jd_local,place))
        result["engine"]="PyJHora"
    except Exception as e:
        result["pyjhora_error"]=str(e)
        result["engine"]=""
    # ===== 辅助: NodeJhora (DE440精密+Jaimini+Ashtakavarga+Yoga+Shadbala) =====
    if lat and lon:
        _js_load("node-jhora-engine")
        nj=_js("node-jhora-engine",
                "try{"
                "var dt=NodeJhora.DateTime.fromISO('%sT%02d:00:00+08:00');"
                "var nj=NodeJhora.EphemerisEngine.getInstance();"
                "var planets=nj.getPlanets(dt,{latitude:%f,longitude:%f},{ayanamsaOrder:1});"
                "var jd=nj.julday(dt);"
                "var houses=nj.getHouses(jd,%f,%f,'W',true);"
                "var moonLon=planets.find(function(p){return p.id===1}).longitude;"
                "var sunLon=planets.find(function(p){return p.id===0}).longitude;"
                "var idToName={0:'Sun',1:'Moon',2:'Mercury',3:'Venus',4:'Mars',5:'Jupiter',6:'Saturn',10:'Rahu',99:'Ketu'};"
                "var chart={planets:planets.map(function(p){return{name:idToName[p.id]||'Unknown',longitude:p.longitude}}),houses:{ascendant:houses.ascendant}};"
                "var charaKarakas=NodeJhora.JaiminiCore.calculateCharaKarakas(planets);"
                "var atmakaraka=charaKarakas[0];"
                "var ashtakavarga=NodeJhora.Ashtakavarga.calculateSAV(planets);"
                "var yogini=NodeJhora.YoginiDasha.calculate(moonLon,dt,50);"
                "var yogas=NodeJhora.YogaEngine.findYogas(chart,NodeJhora.YOGA_LIBRARY);"
                "JSON.stringify({planets:planets,houses:houses,moonLon:moonLon,sunLon:sunLon,charaKarakas:charaKarakas,atmakaraka:atmakaraka,ashtakavarga:ashtakavarga,yogini:yogini,yogas:yogas})"
                "}catch(e){JSON.stringify({error:e.message})}" % (
                    date_str, hour, lat, lon, lat, lon))
        result["nodejhora"]=nj
        result["engine"]+="+NodeJhora"
    result["_hint"]=("PyJHora已全量:Panchanga/Shadbala/Ashtakavarga/RajaYoga774/Dosha/Arudha/Vimshottari。"
        "NodeJhora:DE440/Jaimini(Atmakaraka)/Ashtakavarga/Yoga检测/YoginiDasha。"
        "自探索:dir(jhora)更多Dasha/Varga/Sphuta。Object.keys(NodeJhora)更多KP/Transit/特殊Lagna")
    # ===== 深度模式: NatalEngine(文本) + Caelus(分盘/Ashtottari) + PyJHora深度补充 =====
    if depth=="deep":
        _js_load("natalengine-engine")
        v=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateVedic('{date_str}',{hour},{tz},{lat or 0},{lon or 0}))")
        result["natal"]=v
        result["engine"]+="+NatalEngine"
        _js_load("caelus-engine")
        if lat and lon:
            c=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%sT%02d:00:00+08:00');var chart=e.chartAt(jd,%f,%f,{zodiac:'sidereal'});var moonLon=e.longitude('moon',jd,{zodiac:'sidereal:lahiri'});JSON.stringify({varga9:Caelus.vargaAt(e,jd,9),vimshottari:Caelus.vimshottariDashas(moonLon,jd),ashtottari:Caelus.ashtottariAt(e,jd,jd,%f,%f),yogini:Caelus.yoginiAt(e,jd,jd,%f,%f)})"%(date_str,hour,lat,lon,lat,lon,lat,lon))
            result["caelus_deep"]=c
            result["engine"]+="+Caelus"
        # PyJHora深度: D9/D10分盘 + Ashtottari/Yogini/Narayana/Chara Dasha
        try:
            from jhora.horoscope.dhasa.graha import ashtottari as ashtottari_py, yogini as yogini_py
            from jhora.horoscope.dhasa.raasi import narayana, chara
            result["varga_d9"]=str(drik.dhasavarga(jd_local,place,9))
            result["varga_d10"]=str(drik.dhasavarga(jd_local,place,10))
            result["varga_d60"]=str(drik.dhasavarga(jd_local,place,60))
            result["ashtottari_dasha"]=str(ashtottari_py.get_ashtottari_dhasa_bhukthi(jd_local,place))
            result["yogini_dasha"]=str(yogini_py.get_dhasa_bhukthi(drik.Date(year,month,day),(hour,0,0),place))
            result["narayana_dasha"]=str(narayana.narayana_dhasa_for_rasi_chart(drik.Date(year,month,day),(hour,0,0),place))
            result["chara_dasha"]=str(chara.get_dhasa_antardhasa(drik.Date(year,month,day),(hour,0,0),place))
            result["engine"]+="+PyJHora_deep"
        except Exception as deep_e:
            result["pyjhora_deep_error"]=str(deep_e)
    return result

# ===== 人类图 =====
def _human_design(year,month,day,hour,tz,gene_keys=False):
    date_str=f"{year}-{month:02d}-{day}"
    _js_load("natalengine-engine")
    hd=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateHumanDesign('{date_str}',{hour},{tz}))")
    result={"system":"human_design","engine":"natalengine-js","human_design":hd,"_hint":"已返回类型+权威+中心+通道+闸门+轮回交叉+Profile。基因钥匙:calculateGeneKeys(hdResult)。行运:calculateHDTransits/calculateTransitGates"}
    if gene_keys:
        result["gene_keys"]=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateGeneKeys({hd}))")
    return result

# ===== 灵数卡巴拉 =====
def _kabbalah(year,month,day,word=None,feature="numerology"):
    _js_load("kaabalah-engine")
    result={"system":"kabbalah","engine":"kaabalah-js","_hint":"Kaabalah已返回灵数6核心+个人年/月/周期+挑战+斐波那契+Gematria正反查+Ifa Odu+生命之树+塔罗卡巴拉。自探索:Object.keys(Kaabalah)"}
    base_date=f"new Date({year},{month-1},{day},12)"
    if feature in ("numerology","all"):
        result["life_path"]=_js("kaabalah-engine",f"JSON.stringify(Kaabalah.calculateKaabalisticLifePath({base_date}))")
        result["personal"]=_js("kaabalah-engine",f"JSON.stringify({{personalYear:Kaabalah.calculatePersonalYear({base_date},new Date()),challenges:Kaabalah.calculateChallenges({base_date}),fibonacci:Kaabalah.calculateFibonacciCycle({base_date},new Date()),dateEnergies:Kaabalah.getDateEnergies({base_date})}})")
    if feature in ("gematria","all") and word:
        result["gematria"]=_js("kaabalah-engine",f"JSON.stringify({{forward:Kaabalah.calculateGematria('{word}'),reverse:Kaabalah.reverseGematria(Kaabalah.calculateGematria('{word}')?.value||0)}})")
    if feature in ("odu","all"):
        result["odu"]=_js("kaabalah-engine",f"JSON.stringify(Kaabalah.calculateOdu({base_date}))")
    if feature in ("tarot","all"):
        result["tarot_spreads"]=_js("kaabalah-engine","JSON.stringify(Kaabalah.listTarotSpreads())")
    if feature in ("tree","all"):
        result["tree_of_life"]=_js("kaabalah-engine","JSON.stringify(Kaabalah.buildKaabalisticMapData({}))")
    return result

# ===== 奇门三式 =====
def _qimen(year,month,day,hour=None,feature="qimen"):
    result={"system":"qimen","engine":feature,"_hint":"QimenEngine 7局4流派已返回日家。LiuRen一键排盘含课体+三传+神将+22原子函数。小六壬掌诀推算。自探索:Object.keys(QimenEngine)/Object.keys(LiuRen)"}
    if feature in ("qimen","all"):
        _js_load("qimen-engine")
        result["qimen"]=_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'rijia',year:{year},month:{month},day:{day}}}))")
    if feature in ("liuren","all"):
        _js_load("liuren-engine")
        result["liuren"]=_js("liuren-engine",f"JSON.stringify(LiuRen.getLiuRenByDate(new Date({year},{month-1},{day},12,0)))")
    if feature in ("xiaoliuren","all"):
        from lunar_python import Lunar
        lunar=Lunar.fromYmd(year,month,day)
        result["xiaoliuren"]={"lunar_month":lunar.getMonth(),"lunar_day":lunar.getDay(),"hour":hour or 12}
    return result

# ===== 六爻梅花 =====
def _yijing(method="time",seed=None,year=None,month=None,day=None,feature="all"):
    result={"system":"yijing","engine":"","_hint":"ichingshifa(Iching类)已返回起卦+解卦。meihua_yi梅花/taixuanshifa太玄/jingjue荆诀可用。JS双引擎对照(IchingShifa)。自探索: dir(ichingshifa)/dir(meihua_yi)/dir(Taixuan)/dir(jingjue)"}
    hex_values=None
    try:
        sys.path.insert(0,os.path.dirname(__file__))
        from ichingshifa import Iching
        i=Iching()
        if method=="dayan" or method=="manual":
            # 先调JS取爻值（双引擎用同一组）
            _js_load("iching-shifa-engine")
            js_yao=_js("iching-shifa-engine","JSON.stringify(IchingShifa.dayan())")
            result["iching_shifa_js_yao"]=js_yao
            # 传同爻值给Python
            gua_str=json.loads(js_yao).get("yao","697887") if isinstance(js_yao,str) else "697887"
            result["ichingshifa"]=str(i.qigua_manual(year or 2026,month or 1,day or 1,12,0,gua_str))
        elif method=="time" and all([year,month,day]):
            hex_data=i.qigua_time(year,month,day,12,0)
            result["ichingshifa"]=str(hex_data)
        elif method=="number":
            n=seed if seed is not None else 42
            hex_data=i.qigua_manual(2026,1,1,12,0,f"{n}")
            result["ichingshifa"]=str(hex_data)
        else:
            hex_data=i.qigua_now()
            result["ichingshifa"]=str(hex_data)
        result["engine"]+="ichingshifa"
        # 尝试取爻值
        if hasattr(hex_data,'lines') or hasattr(hex_data,'values'):
            hex_values=getattr(hex_data,'lines',None) or getattr(hex_data,'values',None)
    except Exception as e: result["py_error"]=str(e)
    # meihua_yi
    try:
        import meihua_yi
        if method=="time" and all([year,month,day]):
            result["meihua"]=str(meihua_yi.qigua_time())
        result["engine"]+="+meihua_yi"
    except: pass
    if feature=="all":
        if not hex_values:
            _js_load("iching-shifa-engine")
            result["iching_shifa_js"]=_js("iching-shifa-engine","JSON.stringify(IchingShifa.dayan())")
        result["engine"]+="+iching-shifa-engine"
    return result

# ===== 路由表 =====
_ROUTER={"塔罗":_tarot,"tarot":_tarot,"雷诺曼":_lenormand,"lenormand":_lenormand,"八字":_bazi,"bazi":_bazi,"紫微":_ziwei,"ziwei":_ziwei,"现代西洋占星":_western_astro,"现代占星":_western_astro,"西洋占星":_western_astro,"western_astro":_western_astro,"传统西洋占星":_traditional_astro,"traditional_astro":_traditional_astro,"吠陀":_vedic,"vedic":_vedic,"人类图":_human_design,"human_design":_human_design,"灵数卡巴拉":_kabbalah,"kabbalah":_kabbalah,"奇门":_qimen,"qimen":_qimen,"六爻梅花":_yijing,"yijing":_yijing}

def mingli_run(system,params=None,bridge=None):
    global _bridge
    if bridge is not None: _bridge=bridge
    if isinstance(params,str):
        try: params=json.loads(params)
        except: params={}
    if not isinstance(params,dict): params={}
    func=_ROUTER.get(system)
    if not func: return json.dumps({"error":f"未知系统:{system}","available":list_systems()},ensure_ascii=False)
    try:
        result=func(**params)
        return json.dumps(result,ensure_ascii=False,default=str)
    except Exception as e:
        import traceback
        return json.dumps({"error":str(e),"traceback":traceback.format_exc(),"system":system},ensure_ascii=False)

def list_systems():
    return sorted(set(k for k in _ROUTER if not k.isascii()))
