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
    result={"system":"western_astrology","engine":"natalengine-js","natal":natal,"_hint":"NatalEngine已返回日月升+7星+元素+相位+合盘。Caelus(JS)另231函数:本命18/推运7/合盘3/行运12/恒星2/ACG。自探索:Object.keys(Caelus)"}
    if depth=="deep":
        _js_load("caelus-engine")
        c=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%sT%02d:00:00+08:00');var chart=e.chartAt(jd,%f,%f,{});JSON.stringify({signature:Caelus.chartSignature(chart),patterns:Caelus.detectPatterns(chart),firdaria:Caelus.firdariaAt(e,jd,jd,%f,%f),profections:Caelus.profectionAt(e,jd,jd,%f,%f)})"%(date_str,hour,lat,lon,lat,lon,lat,lon))
        result["caelus"]=c
    return result

# ===== 传统西洋占星 =====
def _traditional_astro(year,month,day,hour,tz_offset,lat,lon):
    from flatlib.chart import Chart
    from flatlib.datetime import Datetime
    from flatlib.geopos import GeoPos
    from flatlib import const
    dt=Datetime(f"{year}/{month:02d}/{day}",f"{hour}:00",tz_offset)
    pos=GeoPos(lat,lon)
    chart=Chart(dt,pos,IDs=const.LIST_OBJECTS)
    objs={}
    for name in const.LIST_OBJECTS:
        try:
            o=chart.getObject(name)
            objs[name]={"sign":o.sign,"signlon":o.signlon,"lon":o.lon,"retrograde":o.isRetrograde()}
        except: pass
    houses={}
    for i in range(1,13):
        try:
            h=chart.getHouse(getattr(const,f"HOUSE{i}"))
            houses[f"house{i}"]={"sign":h.sign,"lon":h.lon}
        except: pass
    return {"system":"traditional_astrology","engine":"flatlib","objects":objs,"houses":houses,"asc":str(chart.getAngle(const.ASC)),"mc":str(chart.getAngle(const.MC)),"_hint":"flatlib另有:dignityOf/almuten/temperament/primaryDirections/profection/arabianParts/antiscia/reception。自探索:dir(flatlib)"}

# ===== 吠陀 =====
def _vedic(year,month,day,hour,tz,lat=None,lon=None,depth="standard"):
    date_str=f"{year}-{month:02d}-{day}"
    result={"system":"vedic"}
    # 默认主力: PyJHora (Python/Chaquopy)
    try:
        from jhora import utils; from jhora.panchanga import drik
        place=drik.Place("loc",lat or 0,lon or 0,float(tz))
        jd_local=utils.julian_day_number(drik.Date(year,month,day),(hour,0,0))
        pp=drik.dhasavarga(jd_local,place,1)
        result["pyjhora"]={"planets":str(pp[:9]),"lagna":str(drik.ascendant(jd_local,place))}
        result["engine"]="PyJHora"
        result["_hint"]=("PyJHora主力:54Dasha/Panchanga/Varga/RajaYoga774/Tajaka/匹配已就绪。"
            "NatalEngine(JS):Rasi+27宿+Dasha文本。Caelus深度:varga(D1-D60)/ashtottari/yogini。"
            "NodeJhora(JS):DE440/Shadbala/Ashtakavarga/Jaimini。自探索:dir(jhora)")
    except Exception as e:
        result["pyjhora_error"]=str(e)
        result["engine"]=""
        result["_hint"]="PyJHora不可用，回退JS引擎。"
    # JS辅助引擎(始终运行,补充PyJHora无法覆盖的数据)
    _js_load("natalengine-engine")
    v=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateVedic('{date_str}',{hour},{tz},{lat or 0},{lon or 0}))")
    result["natal"]=v
    result["engine"]+="+NatalEngine"
    if depth=="deep":
        _js_load("caelus-engine")
        if lat and lon:
            c=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%sT%02d:00:00+08:00');var chart=e.chartAt(jd,%f,%f,{zodiac:'sidereal'});var moonLon=e.longitude('moon',jd,{zodiac:'sidereal:lahiri'});JSON.stringify({varga9:Caelus.vargaAt(e,jd,9),vimshottari:Caelus.vimshottariDashas(moonLon,jd),ashtottari:Caelus.ashtottariAt(e,jd,jd,%f,%f),yogini:Caelus.yoginiAt(e,jd,jd,%f,%f)})"%(date_str,hour,lat,lon,lat,lon,lat,lon))
            result["caelus_deep"]=c
            _js_load("node-jhora-engine")
            nj=_js("node-jhora-engine",
                    "try{"
                    "var dt=NodeJhora.DateTime.fromISO('%sT%02d:00:00+08:00');"
                    "var nj=NodeJhora.EphemerisEngine.getInstance();"
                    "var planets=nj.getPlanets(dt,{latitude:%f,longitude:%f},{ayanamsaOrder:1});"
                    "var jd=nj.julday(dt);"
                    "var houses=nj.getHouses(jd,%f,%f,'W',true);"
                    "JSON.stringify({planets:planets,houses:houses})"
                    "}catch(e){JSON.stringify({error:e.message})}" % (
                        date_str, hour, lat, lon, lat, lon))
            result["nodejhora"]=nj
            result["engine"]+="+Caelus+NodeJhora"
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
