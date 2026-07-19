"""Route:  traditional astro"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 传统西洋占星 =====
def _traditional_astro(year,month,day,hour,tz_offset,lat,lon):
    tz_offset=float(tz_offset) if tz_offset is not None else 0.0
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    # 设置Swiss Ephemeris星历文件路径 (Android找不到硬编码:/users/ephe/)
    import swisseph as swe
    _ephe_found = False
    # Chaquopy下flatlib和swisseph都在site-packages，用模块实际位置定位
    import flatlib as _flatlib
    for _p in [
        os.path.join(os.path.dirname(swe.__file__),'ephe'),                      # swisseph包自带的ephe/
        os.path.join(os.path.dirname(_flatlib.__file__),'resources','swefiles'),  # flatlib自带的swefiles/
    ]:
        if os.path.isdir(_p) and any(f.endswith('.se1') for f in os.listdir(_p)):
            swe.set_ephe_path(_p)
            os.environ['SE_EPHE_PATH'] = _p
            _ephe_found = True
            break
    if not _ephe_found:
        # fallback: 搜site-packages
        import site
        for _sp in site.getsitepackages():
            for _sub in ['swisseph/ephe','flatlib/resources/swefiles']:
                _p = os.path.join(_sp, _sub)
                if os.path.isdir(_p) and any(f.endswith('.se1') for f in os.listdir(_p)):
                    swe.set_ephe_path(_p)
                    os.environ['SE_EPHE_PATH'] = _p
                    _ephe_found = True
                    break
            if _ephe_found: break
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
                "under_sun":ad.isUnderSun(),"voc":ad.isVoc(),"joy_house":ad.inHouseJoy()}
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
    # 全部Lots (flatlib内置公式, 自动昼夜分离)
    try:
        from flatlib.tools.arabicparts import PARS_FORTUNA, PARS_SPIRIT, PARS_VENUS, PARS_MARS
        from flatlib.tools.arabicparts import PARS_SUBSTANCE, PARS_ENEMIES, PARS_SATURN, PARS_JUPITER
        from flatlib.tools.arabicparts import PARS_WEDDING_MALE, PARS_WEDDING_FEMALE, PARS_SONS
        from flatlib.tools.arabicparts import PARS_FATHER, PARS_MOTHER, PARS_FRIENDS
        from flatlib.tools.arabicparts import PARS_DEATH, PARS_DISEASES, PARS_FAITH, PARS_MERCURY
        from flatlib.tools.arabicparts import PARS_BROTHERS, PARS_TRAVEL, PARS_HORSEMANSHIP
        lots={}
        # 原有15个 → flatlib 对应
        for key, p in [("fortune",PARS_FORTUNA),("spirit",PARS_SPIRIT),
                        ("eros",PARS_VENUS),("courage",PARS_MARS),
                        ("basis",PARS_SUBSTANCE),("nemesis",PARS_ENEMIES),
                        ("necessity",PARS_SATURN),("victory",PARS_JUPITER),
                        ("marriage_m",PARS_WEDDING_MALE),("marriage_f",PARS_WEDDING_FEMALE),
                        ("children_m",PARS_SONS),("children_f",PARS_SONS),  # flatlib无男女之分
                        ("father",PARS_FATHER),("mother",PARS_MOTHER),
                        ("friends",PARS_FRIENDS),
                        # 新增6个
                        ("death",PARS_DEATH),("sickness",PARS_DISEASES),
                        ("faith",PARS_FAITH),("commerce",PARS_MERCURY),
                        # 额外补3个flatlib有但之前没有的
                        ("brothers",PARS_BROTHERS),("travel",PARS_TRAVEL),
                        ("horsemanship",PARS_HORSEMANSHIP)]:
            try: lots[key]=str(getPart(p, chart))
            except: pass
        # success用Jupiter (Pars Jupiter = 胜利/成功)
        try: lots["success"]=str(getPart(PARS_JUPITER, chart))
        except: pass
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
    # 小限 (⚠️ flatlib profections 有除零bug，用 Caelus.profection 代替)
    try:
        prof=prof_compute(chart,dt)
        prof_asc=str(prof.getAngle(const.ASC))
    except:
        prof_asc="flatlib profections failed (known divide-by-zero bug), use Caelus.profection from caelus field"
    result = {"system":"traditional_astrology","engine":"flatlib",
        "objects":objs,"houses":houses,
        "asc":str(chart.getAngle(const.ASC)),"mc":str(chart.getAngle(const.MC)),
        "dignities":dignities,"accidental":accidental,
        "sect":{"is_day":is_day,"planets":sect},
        "temperament":temperament,"almutem":str(alm),
        "arabic_parts":lots,"profection_asc":prof_asc,
        "configurations":configs}
    # Caelus JS: Firdaria + 主限推运 (独立try, 失败不影响flatlib数据)
    try:
        tz_sign = "+" if tz_offset >= 0 else "-"
        tz_abs = abs(tz_offset)
        tz_str = f"{tz_sign}{int(tz_abs):02d}:{int((tz_abs - int(tz_abs)) * 60 + 0.5):02d}"
        iso_date = f"{year}-{month:02d}-{day}T{hour:02d}:00:00{tz_str}"
        _js_load("caelus-engine")
        c = _js("caelus-engine",
            "var e=new Caelus.Engine(Caelus.embeddedData);"
            "var jd=Caelus.isoToJd('%s');"
            "JSON.stringify({"
            "firdaria:Caelus.firdaria(%s,jd),"
            "primaryDirections:Caelus.primaryDirections(e,jd,%f,%f),"
            "solarReturn:Caelus.solarReturn(e,jd,jd+3650,jd+4015),"
            "profections:Caelus.profection(0,jd,jd+365)"
            "})" % (iso_date, "true" if is_day else "false", lat, lon))
        if c and 'bridge not available' not in c and 'error' not in c.lower():
            result["caelus"] = c
            result["engine"] = "flatlib+Caelus"
    except: pass
    result["_hint"] = ("flatlib已全量:本质尊贵/偶然尊贵/Sect/Lots/小限/Almutem/气质/结构。"
        "Zodiacal Releasing当前不支持。Caelus预取:Firdaria/主限推运/日弧。自探索:dir(flatlib)/Object.keys(Caelus)")
    return result
