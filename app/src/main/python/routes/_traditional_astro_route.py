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
        # 扩展6个传统阿拉伯点 (Paulus/Bonatti 公式, 昼夜分离)
        lots["death"]=_lot(sa_lon,m_lon) if is_day else _lot(m_lon,sa_lon)
        lots["sickness"]=_lot(ma_lon,sa_lon) if is_day else _lot(sa_lon,ma_lon)
        lots["captivity"]=_lot(sa_lon,s_lon) if is_day else _lot(s_lon,sa_lon)
        lots["faith"]=_lot(me_lon,m_lon) if is_day else _lot(m_lon,me_lon)
        lots["commerce"]=_lot(me_lon,s_lon) if is_day else _lot(s_lon,me_lon)
        lots["success"]=_lot(j_lon,s_lon) if is_day else _lot(s_lon,j_lon)
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
