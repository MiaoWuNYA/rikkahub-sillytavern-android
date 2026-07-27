"""Route: traditional astro — Hellenistic/Medieval traditional Western astrology"""
import json, sys, os, datetime
from ._shared import _js, _js_load, compute_jd

def _traditional_astro(year, month, day, hour, tz_offset, lat, lon, minute=0):
    tz_offset = float(tz_offset) if tz_offset is not None else 0.0
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)

    # --- Swiss Ephemeris path setup ---
    import swisseph as swe
    _ephe_found = False
    import flatlib as _flatlib
    for _p in [
        os.path.join(os.path.dirname(swe.__file__), 'ephe'),
        os.path.join(os.path.dirname(_flatlib.__file__), 'resources', 'swefiles'),
    ]:
        if os.path.isdir(_p) and any(f.endswith('.se1') for f in os.listdir(_p)):
            swe.set_ephe_path(_p); os.environ['SE_EPHE_PATH'] = _p
            _ephe_found = True; break
    if not _ephe_found:
        import site
        for _sp in site.getsitepackages():
            for _sub in ['swisseph/ephe', 'flatlib/resources/swefiles']:
                _p = os.path.join(_sp, _sub)
                if os.path.isdir(_p) and any(f.endswith('.se1') for f in os.listdir(_p)):
                    swe.set_ephe_path(_p); os.environ['SE_EPHE_PATH'] = _p
                    _ephe_found = True; break
            if _ephe_found: break

    from flatlib.chart import Chart
    from flatlib.datetime import Datetime
    from flatlib.geopos import GeoPos
    from flatlib import const
    from flatlib.dignities.essential import ruler, exalt, dayTrip, nightTrip, partTrip, score as ess_score, isPeregrine, almutem, getInfo
    from flatlib.dignities.accidental import AccidentalDignity, viaCombusta, haiz, light as _light
    from flatlib.protocols.temperament import Temperament
    from flatlib.protocols.almutem import compute as almutem_compute
    from flatlib.protocols import behavior as _behavior
    from flatlib.aspects import hasAspect, getAspect as _getAspect
    from flatlib.tools import chartdynamics
    from flatlib.tools.arabicparts import getPart as _getPart, FORMULAS as _PART_FORMULAS
    from flatlib.ephem.tools import syzygyJD
    from flatlib import props as _props

    dt = Datetime(f"{year}/{month:02d}/{day}", f"{hour}:{minute:02d}", tz_offset)
    pos = GeoPos(lat, lon)
    chart = Chart(dt, pos, IDs=const.LIST_OBJECTS)
    is_day = chart.isDiurnal()
    asc_lon = chart.getAngle(const.ASC).lon
    jd = compute_jd(year, month, day, hour, minute, tz_offset)

    # --- Internal planetary data ---
    planets = {}
    for name in const.LIST_OBJECTS:
        try:
            o = chart.getObject(name)
            planets[name.lower()] = {"lon": o.lon, "sign": o.sign, "signlon": o.signlon,
                                     "retrograde": o.isRetrograde(), "speed": o.meanMotion()}
        except: pass
    for pname, pdata in planets.items():
        for i in range(1, 13):
            h = chart.getHouse(getattr(const, f"HOUSE{i}"))
            if h.inHouse(pdata["lon"]):
                pdata["house"] = i; break

    # --- Sign lookup tables ---
    sign_names = ["Aries", "Taurus", "Gemini", "Cancer", "Leo", "Virgo",
                  "Libra", "Scorpio", "Sagittarius", "Capricorn", "Aquarius", "Pisces"]
    sign_mode = ["Cardinal", "Fixed", "Mutable",
                 "Cardinal", "Fixed", "Mutable",
                 "Cardinal", "Fixed", "Mutable",
                 "Cardinal", "Fixed", "Mutable"]
    sign_gender = ["Masculine", "Feminine", "Masculine",
                   "Feminine", "Masculine", "Feminine",
                   "Masculine", "Feminine", "Masculine",
                   "Feminine", "Masculine", "Feminine"]

    bnames_7 = ["sun", "moon", "mercury", "venus", "mars", "jupiter", "saturn"]

    # --- 7-planet output ---
    objs = {}
    for name in const.LIST_OBJECTS[:7]:
        try:
            o = chart.getObject(name)
            pn = name.lower()
            h_obj = chart.getHouse(getattr(const, f"HOUSE{next(i for i in range(1, 13) if chart.getHouse(getattr(const, f'HOUSE{i}')).inHouse(o.lon))}"))
            hn = int(h_obj.id.replace("House", ""))
            h_cond = ["Angular", "Succedent", "Cadent"][(hn - 1) % 3]
            # Triplicity rulers
            trip_day = dayTrip(o.sign)
            trip_night = nightTrip(o.sign)
            trip_part = partTrip(o.sign)
            # Mercury sect: rises before sun = diurnal, after = nocturnal
            mercury_sect = None
            if pn == "mercury":
                try:
                    sun_o = chart.getObject(const.SUN)
                    mercury_sect = "diurnal" if o.lon < sun_o.lon else "nocturnal"
                except: pass
            objs[pn] = {"sign": o.sign, "signlon": o.signlon, "lon": o.lon,
                        "sign_mode": sign_mode[sign_names.index(o.sign)],
                        "sign_gender": sign_gender[sign_names.index(o.sign)],
                        "house": hn, "house_condition": h_cond,
                        "speed": planets.get(pn, {}).get("speed", 0),
                        "retrograde": o.isRetrograde(),
                        "ruler": ruler(o.sign), "exalt": exalt(o.sign),
                        "triplicity_day": trip_day,
                        "triplicity_night": trip_night,
                        "triplicity_part": trip_part,
                        "score": ess_score(name, o.sign, o.signlon),
                        "peregrine": isPeregrine(name, o.sign, o.signlon),
                        "mercury_sect": mercury_sect,
                        "fertility": None, "figure": None}
        except: pass

    # --- Placidus houses (reference) ---
    houses = {}
    for i in range(1, 13):
        try:
            h = chart.getHouse(getattr(const, f"HOUSE{i}"))
            houses[f"house{i}"] = {"sign": h.sign, "lon": h.lon}
        except: pass

    # --- Whole Sign Houses ---
    try:
        asc_sign_index = int(asc_lon // 30)
        houses_whole_sign = [{"house": i + 1, "sign": sign_names[(asc_sign_index + i) % 12],
                              "lon": ((asc_sign_index + i) % 12) * 30,
                              "mode": sign_mode[(asc_sign_index + i) % 12],
                              "gender": sign_gender[(asc_sign_index + i) % 12],
                              "condition": ["Angular", "Succedent", "Cadent"][i % 3]}
                             for i in range(12)]
    except: houses_whole_sign = []

    # --- Essential dignities (per-planet breakdown) ---
    dignities = {}
    for name in const.LIST_OBJECTS[:7]:
        try:
            o = chart.getObject(name)
            dignities[name.lower()] = getInfo(o.sign, o.signlon)
        except: pass

    # --- Accidental dignities ---
    accidental = {}
    for name in const.LIST_OBJECTS[:7]:
        try:
            o = chart.getObject(name)
            ad = AccidentalDignity(o, chart)
            accidental[name.lower()] = {"score": ad.score(),
                                        "score_properties": ad.getScoreProperties(),
                                        "combust": ad.isCombust(), "cazimi": ad.isCazimi(),
                                        "orientality": ad.orientality(),
                                        "augmenting_light": ad.isAugmentingLight(),
                                        "under_sun": ad.isUnderSun(), "voc": ad.isVoc(),
                                        "joy_house": ad.inHouseJoy(),
                                        "joy_sign": ad.inSignJoy(),
                                        "haiz": haiz(o, chart), "via_combusta": viaCombusta(o),
                                        "light": _light(o, chart.getObject(const.SUN)),
                                        "auxilied": ad.isAuxilied(),
                                        "surrounded": ad.isSurrounded()}
        except: pass

    # --- Sect ---
    diurnal_planets = {"sun", "jupiter", "saturn"}
    nocturnal_planets = {"moon", "venus", "mars"}
    sect = {}
    for pname in planets:
        if pname == "mercury":
            sect[pname] = "common"
        elif is_day and pname in diurnal_planets:
            sect[pname] = "in_sect"
        elif not is_day and pname in nocturnal_planets:
            sect[pname] = "in_sect"
        else:
            sect[pname] = "out_of_sect"

    # --- Arabic Parts / Hermetic Lots (13 core, Caelus-verified formulas) ---
    lots = {}
    try:
        s_lon = planets["sun"]["lon"]; m_lon = planets["moon"]["lon"]
        me_lon = planets["mercury"]["lon"]; v_lon = planets["venus"]["lon"]
        ma_lon = planets["mars"]["lon"]; j_lon = planets["jupiter"]["lon"]
        sa_lon = planets["saturn"]["lon"]
        # lot(asc, a, b, is_day) = asc ± (a - b)
        _l = lambda a, b: (asc_lon + (a - b if is_day else b - a)) % 360

        # -- 7 Hermetic Lots --
        lots["fortune"] = _l(m_lon, s_lon)        # asc ± (moon - sun)
        lots["spirit"] = _l(s_lon, m_lon)          # asc ± (sun - moon)
        lots["eros"] = _l(v_lon, lots["spirit"])   # asc ± (venus - spirit) — requires spirit
        lots["necessity"] = _l(lots["fortune"], me_lon)  # asc ± (fortune - mercury)
        lots["courage"] = _l(lots["fortune"], ma_lon)     # asc ± (fortune - mars)
        lots["victory"] = _l(j_lon, lots["spirit"])       # asc ± (jupiter - spirit)
        lots["nemesis"] = _l(lots["fortune"], sa_lon)     # asc ± (fortune - saturn)

        # -- Family / Relationship lots --
        lots["marriage_m"] = _l(v_lon, sa_lon)    # asc ± (venus - saturn)
        lots["marriage_f"] = _l(sa_lon, v_lon)    # asc ± (saturn - venus), inverse
        lots["children"] = _l(j_lon, sa_lon)      # asc ± (jupiter - saturn)
        lots["father"] = _l(s_lon, sa_lon)        # asc ± (sun - saturn)
        lots["mother"] = _l(m_lon, v_lon)          # asc ± (moon - venus)
        lots["friends"] = _l(me_lon, m_lon)       # asc ± (mercury - moon)
    except: pass

    # --- Traditional configurations (flatlib aspects) ---
    configs = []
    brows = {}
    for n in bnames_7:
        try: brows[n] = chart.getObject(getattr(const, n.upper()))
        except: pass
    fast = ["moon", "mercury", "venus"]

    # --- Aspect helper ---
    _is_app = lambda a: a and a.movement() == const.APPLICATIVE
    _is_sep = lambda a: a and a.movement() == const.SEPARATIVE
    _ASPECT_NAMES = {0: "conjunction", 60: "sextile", 90: "square", 120: "trine", 180: "opposition"}

    # --- Natal aspects (flatlib) ---
    aspects = []
    try:
        for i in range(len(bnames_7)):
            for j in range(i + 1, len(bnames_7)):
                try:
                    a, b = bnames_7[i], bnames_7[j]
                    asp = _getAspect(brows[a], brows[b], const.MAJOR_ASPECTS)
                    if asp:
                        mov = asp.movement()
                        aspects.append({
                            "a": a, "b": b,
                            "aspect": _ASPECT_NAMES.get(asp.type, str(asp.type)),
                            "orb": asp.orb,
                            "phase": "applying" if mov == const.APPLICATIVE else "separating" if mov == const.SEPARATIVE else mov,
                            "direction": getattr(asp, 'direction', None)
                        })
                except: pass
    except: aspects = []

    try:
        # Collection of Light: fast planet aspects A and B, A and B don't aspect each other
        for c in fast:
            fo = brows[c]
            for a in bnames_7:
                if a == c: continue
                for b in bnames_7:
                    if b == c or b == a: continue
                    if hasAspect(fo, brows[a], const.MAJOR_ASPECTS) and \
                       hasAspect(fo, brows[b], const.MAJOR_ASPECTS) and \
                       not hasAspect(brows[a], brows[b], const.MAJOR_ASPECTS):
                        configs.append({"type": "collection_of_light", "collector": c, "planets": [a, b]})

        # Translation of Light: fast planet separating from A, applying to B
        for c in fast:
            for a in bnames_7:
                if a == c: continue
                for b in bnames_7:
                    if b == c or b == a: continue
                    try:
                        aa = _getAspect(brows[c], brows[a], const.MAJOR_ASPECTS)
                        ab = _getAspect(brows[c], brows[b], const.MAJOR_ASPECTS)
                        if _is_sep(aa) and _is_app(ab):
                            configs.append({"type": "translation_of_light", "translator": c, "from": a, "to": b})
                    except: pass

        # Refranation: applying aspect, one planet retrograde
        for a in bnames_7:
            for b in bnames_7:
                if a >= b: continue
                try:
                    asp = _getAspect(brows[a], brows[b], const.MAJOR_ASPECTS)
                    if _is_app(asp) and (brows[a].isRetrograde() or brows[b].isRetrograde()):
                        configs.append({"type": "refranation", "planets": [a, b],
                                        "retrograde": a if brows[a].isRetrograde() else b})
                except: pass

        # Besiegement: Mars + Saturn both aspect a planet
        for c in bnames_7:
            besieging = [m for m in ["mars", "saturn"] if m != c and hasAspect(brows[m], brows[c], const.MAJOR_ASPECTS)]
            if len(besieging) >= 2:
                configs.append({"type": "besiegement", "planet": c, "besiegers": besieging})

        # Prohibition: faster planet aspects B before slow A can perfect its aspect to B
        for a in bnames_7:
            for b in bnames_7:
                if a >= b: continue
                try:
                    asp_ab = _getAspect(brows[a], brows[b], const.MAJOR_ASPECTS)
                    if not _is_app(asp_ab): continue
                    a_spd = abs(planets.get(a, {}).get("speed", 0))
                    b_spd = abs(planets.get(b, {}).get("speed", 0))
                    for p in bnames_7:
                        if p == a or p == b: continue
                        p_spd = abs(planets.get(p, {}).get("speed", 0))
                        if p_spd <= max(a_spd, b_spd): continue
                        try:
                            asp_pb = _getAspect(brows[p], brows[b], const.MAJOR_ASPECTS)
                            if _is_app(asp_pb):
                                configs.append({"type": "prohibition", "planets": [a, b], "prohibitor": p})
                        except: pass
                except: pass

        # Frustration: applying aspect, slower planet retrograde (can never perfect)
        for a in bnames_7:
            for b in bnames_7:
                if a >= b: continue
                try:
                    asp = _getAspect(brows[a], brows[b], const.MAJOR_ASPECTS)
                    if not _is_app(asp): continue
                    a_retro = brows[a].isRetrograde()
                    b_retro = brows[b].isRetrograde()
                    a_slower = abs(planets.get(a, {}).get("speed", 0)) < abs(planets.get(b, {}).get("speed", 0))
                    b_slower = abs(planets.get(b, {}).get("speed", 0)) < abs(planets.get(a, {}).get("speed", 0))
                    if (a_retro and a_slower) or (b_retro and b_slower):
                        is_refranation = (a_retro and not a_slower) or (b_retro and not b_slower)
                        if not is_refranation:
                            configs.append({"type": "frustration", "planets": [a, b],
                                            "retrograde_node": a if a_retro else b})
                except: pass
    except: pass

    # --- Reception (mutual reception, disposits, receives) ---
    reception = {}
    try:
        dyn = chartdynamics.ChartDynamics(chart)
        c_planets = [const.SUN, const.MOON, const.MERCURY, const.VENUS, const.MARS, const.JUPITER, const.SATURN]
        for a in c_planets:
            for b in c_planets:
                if a != b:
                    mr = dyn.mutualReceptions(a, b)
                    if mr: reception[f"{a}_vs_{b}"] = {"mutual_reception": mr}
                    disp = dyn.disposits(a, b)
                    if disp: reception[f"{a}_disposits_{b}"] = disp
                    recv = dyn.receives(a, b)
                    if recv: reception[f"{a}_receives_{b}"] = recv
    except: reception = {}

    # --- Temperament ---
    try:
        t = Temperament(chart)
        temperament = {"score": t.getScore(), "factors": t.getFactors()}
    except: temperament = {}

    # --- Almutem ---
    try: alm = almutem_compute(chart)
    except: alm = {}

    # --- Populate sign fertility/figure for each object ---
    try:
        _sf = _props.sign.fertility
        _bf = _props.sign.figureBestial
        _fh = _props.sign.figureHuman
        _fw = _props.sign.figureWild
        for pn in objs:
            s = objs[pn]["sign"]
            objs[pn]["fertility"] = _sf.get(s)
            if s in _fw: objs[pn]["figure"] = "wild"
            elif s in _bf: objs[pn]["figure"] = "bestial"
            elif s in _fh: objs[pn]["figure"] = "human"
            else: objs[pn]["figure"] = None
    except: pass

    # --- Extra Arabic Parts (flatlib engine) ---
    extra_lots = {}
    try:
        for pid in ["Pars Faith", "Pars Substance", "Pars Brothers", "Pars Diseases",
                     "Pars Death", "Pars Travel", "Pars Enemies", "Pars Horsemanship",
                     "Pars Saturn", "Pars Jupiter", "Pars Mars", "Pars Venus", "Pars Mercury"]:
            try:
                p = _getPart(pid, chart)
                extra_lots[pid.lower().replace("pars ", "").replace(" ", "_")] = {
                    "lon": p.lon, "sign": p.sign, "signlon": p.signlon
                }
            except: pass
    except: pass

    # --- Pre-natal Syzygy ---
    syzygy = {}
    try:
        from flatlib.ephem import swe as _flatlib_swe
        s_jd = syzygyJD(jd)
        s_sun = _flatlib_swe.sweObjectLon(const.SUN, s_jd)
        s_moon = _flatlib_swe.sweObjectLon(const.MOON, s_jd)
        s_dist = abs(s_sun - s_moon)
        syzygy = {
            "jd": s_jd,
            "type": "new_moon" if s_dist < 180 else "full_moon",
            "sun_lon": s_sun, "moon_lon": s_moon,
        }
        syzygy["sun_sign"] = sign_names[int(s_sun // 30)]
        syzygy["pre_natal_conjunct"] = int(s_sun // 30) == int(planets["sun"]["lon"] // 30)
    except: pass

    # --- Behavior Protocol ---
    behavior = {}
    try:
        bf = _behavior.compute(chart)
        behavior = {item[0]: item[1] for item in bf}
    except: pass

    # --- Lunar Nodes ---
    nodes = {}
    try:
        nn = chart.getObject(const.NORTH_NODE)
        nodes["north"] = {"lon": nn.lon, "sign": nn.sign, "signlon": nn.signlon, "house": None}
        for i in range(1, 13):
            h = chart.getHouse(getattr(const, f"HOUSE{i}"))
            if h.inHouse(nn.lon):
                nodes["north"]["house"] = i; break
        sn_lon = (nn.lon + 180) % 360
        sn_sign_idx = int(sn_lon // 30)
        nodes["south"] = {"lon": sn_lon, "sign": sign_names[sn_sign_idx], "signlon": sn_lon % 30, "house": None}
        for i in range(1, 13):
            h = chart.getHouse(getattr(const, f"HOUSE{i}"))
            if h.inHouse(sn_lon):
                nodes["south"]["house"] = i; break
    except: pass

    # --- Lord of the Geniture (planet with highest essential + accidental score) ---
    lord_of_geniture = {}
    try:
        best_score = float('-inf')
        best_pn = None
        for pn in bnames_7:
            es = objs.get(pn, {}).get("score", 0) or 0
            ac = accidental.get(pn, {}).get("score", 0) or 0
            if es + ac > best_score:
                best_score = es + ac
                best_pn = pn
        if best_pn:
            lord_of_geniture = {"planet": best_pn, "total_score": best_score}
    except: pass

    # --- Hyleg (simplified Hellenistic) ---
    hyleg = {}
    try:
        primary = "sun" if is_day else "moon"
        secondary = "moon" if is_day else "sun"
        pri_house = objs.get(primary, {}).get("house", 0)
        sec_house = objs.get(secondary, {}).get("house", 0)
        if pri_house in (1, 7, 9, 10, 11):
            hyleg = {"body": primary, "method": "luminary_in_good_house"}
        elif sec_house in (1, 7, 9, 10, 11):
            hyleg = {"body": secondary, "method": "luminary_in_good_house"}
        elif 1 in [objs.get(p, {}).get("house") for p in bnames_7]:
            hyleg = {"body": "ascendant", "method": "no_luminary_in_good_house"}
        else:
            hyleg = {"body": None, "method": "no_hyleg_found"}
    except: pass

    # --- Doryphory (planets in aspectual configuration with Sun) ---
    doryphory = {}
    try:
        sun_obj = brows.get("sun")
        if sun_obj:
            for pn in bnames_7:
                if pn == "sun": continue
                try:
                    asp = _getAspect(sun_obj, brows[pn], const.MAJOR_ASPECTS)
                    if asp and asp.type in (0, 60, 90, 120, 180):
                        doryphory[pn] = {"aspect": _ASPECT_NAMES.get(asp.type), "orb": asp.orb}
                except: pass
    except: pass

    # --- Result (flatlib portion) ---
    result = {
        "system": "traditional_astrology", "engine": "flatlib",
        "objects": objs, "houses": houses, "houses_whole_sign": houses_whole_sign,
        "asc": chart.getAngle(const.ASC), "mc": chart.getAngle(const.MC),
        "dignities": dignities, "accidental": accidental,
        "sect": {"is_day": is_day, "planets": sect},
        "temperament": temperament, "almutem": alm,
        "arabic_parts": lots, "extra_arabic_parts": extra_lots,
        "syzygy": syzygy, "behavior": behavior, "nodes": nodes,
        "lord_of_geniture": lord_of_geniture, "hyleg": hyleg, "doryphory": doryphory,
        "aspects": aspects,
        "configurations": configs, "reception": reception,
    }

    # --- Caelus JS (分阶段): traditional timing + condition matrix + ZR + electional ---
    caelus_data = {}
    caelus_errors = []
    try:
        jd = compute_jd(year, month, day, hour, minute, tz_offset)
        now = datetime.datetime.now()
        now_jd = compute_jd(now.year, now.month, now.day, now.hour, now.minute, 0)
        _js_load("caelus-engine")

        def _cp(name, js):
            try:
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

        # Phase 1 (~7s): Engine init + condition matrix + ZR + antiscion + scalar fast data
        _cp("p1",
            "var __cr;try{"
            "var e=new Caelus.Engine(Caelus.embeddedData);"
            "var jd=%s;var nowJd=%s;var _lat=%s;var _lon=%s;"
            "var chart=e.chartAt(jd,_lat,_lon,{});"
            "var isDay=%s;"
            "var sf=function(fn){try{return fn()}catch(ex){return null}};"
            "var p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];"
            "var isDayStr=isDay?'day':'night';"
            # condition matrix
            "var cond={};"
            "p7.forEach(function(b){try{"
            "var body=chart.bodies[b];if(!body)return;"
            "cond[b]={"
            "dignityScore:Caelus.dignityScore(b,body.lon,isDayStr),"
            "pheno:Caelus.pheno(e,b,jd),"
            "solarPhase:Caelus.solarPhase(e,b,jd),"
            "house:Caelus.houseOf(body.lon,chart.cusps),"
            "angularity:Caelus.angularity(Caelus.houseOf(body.lon,chart.cusps))"
            "}}catch(ex){}});"
            # zodiacal releasing from Spirit + Fortune
            "var ascDeg=chart.angles.asc;"
            "var spLon=sf(function(){return Caelus.lotSpirit(ascDeg,chart.bodies.sun.lon,chart.bodies.moon.lon,isDay)});"
            "var ftLon=sf(function(){return Caelus.lotFortune(ascDeg,chart.bodies.sun.lon,chart.bodies.moon.lon,isDay)});"
            "var spSign=spLon!==null?Math.floor(spLon/30)%%12:-1;"
            "var ftSign=ftLon!==null?Math.floor(ftLon/30)%%12:-1;"
            "var zrSpirit=spSign>=0?sf(function(){return{zrRelease:Caelus.zrRelease(spSign,jd,2,75),active:Caelus.zrAt(e,jd,nowJd,_lat,_lon,'spirit')}}):null;"
            "var zrFortune=ftSign>=0?sf(function(){return{zrRelease:Caelus.zrRelease(ftSign,jd,2,75),active:Caelus.zrAt(e,jd,nowJd,_lat,_lon,'fortune')}}):null;"
            "__cr=JSON.stringify({"
            "conditions:cond,"
            "zodiacalReleasing:{spirit:zrSpirit,fortune:zrFortune},"
            "almutenFiguris:Caelus.almuten(ascDeg,isDayStr),"
            "profections:Caelus.profection(%d,jd,nowJd),"
            "firdaria:Caelus.firdaria(isDay,jd),"
            "cusps:chart.cusps,"
            "antiscion:{"
            "sun:Caelus.antiscion(chart.bodies.sun.lon),"
            "moon:Caelus.antiscion(chart.bodies.moon.lon),"
            "mercury:Caelus.antiscion(chart.bodies.mercury.lon),"
            "venus:Caelus.antiscion(chart.bodies.venus.lon),"
            "mars:Caelus.antiscion(chart.bodies.mars.lon),"
            "jupiter:Caelus.antiscion(chart.bodies.jupiter.lon),"
            "saturn:Caelus.antiscion(chart.bodies.saturn.lon)"
            "},"
            "contraAntiscion:{"
            "sun:Caelus.contraAntiscion(chart.bodies.sun.lon),"
            "moon:Caelus.contraAntiscion(chart.bodies.moon.lon),"
            "mercury:Caelus.contraAntiscion(chart.bodies.mercury.lon),"
            "venus:Caelus.contraAntiscion(chart.bodies.venus.lon),"
            "mars:Caelus.contraAntiscion(chart.bodies.mars.lon),"
            "jupiter:Caelus.contraAntiscion(chart.bodies.jupiter.lon),"
            "saturn:Caelus.contraAntiscion(chart.bodies.saturn.lon)"
            "},"
            "planetaryHour:Caelus.planetaryHour(e,nowJd,_lat,_lon),"
            "voidOfCourse:Caelus.voidOfCourse(e,nowJd),"
            "lunarPhases:Caelus.lunarPhases(e,nowJd,nowJd+90,8)"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p1:'+ex.message})};__cr"
            % (jd, now_jd, lat, lon, "true" if is_day else "false",
               int(asc_lon / 30)))

        # Phase 2a (~12s): Progressions + transits + starConjunctions + returns + declination + eclipses + riseSet
        _cp("p2a",
            "var __cr;try{"
            "var jd=%s;var nowJd=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];}"
            "var _returns={};['mercury','venus','mars','jupiter','saturn'].forEach(function(b){"
            "_returns[b]=sf(function(){return Caelus.returns(e,b,jd,jd,jd+365*3,'tropical').slice(0,3)})});"
            "__cr=JSON.stringify({"
            "solarArc:Caelus.solarArc(e,jd,jd),"
            "progressedMoon:sf(function(){return Caelus.progressedLongitude(e,'moon',jd,jd+365*30)}),"
            "progressedSun:sf(function(){return Caelus.progressedLongitude(e,'sun',jd,jd+365*30)}),"
            "progressedOther:{mercury:sf(function(){return Caelus.progressedLongitude(e,'mercury',jd,jd+365*30)}),"
            "venus:sf(function(){return Caelus.progressedLongitude(e,'venus',jd,jd+365*30)}),"
            "mars:sf(function(){return Caelus.progressedLongitude(e,'mars',jd,jd+365*30)}),"
            "jupiter:sf(function(){return Caelus.progressedLongitude(e,'jupiter',jd,jd+365*30)}),"
            "saturn:sf(function(){return Caelus.progressedLongitude(e,'saturn',jd,jd+365*30)})},"
            "transits:sf(function(){return Caelus.transitAspects(chart,e,nowJd,{bodies:p7})}),"
            "transitPositions:(function(){var tp={};p7.concat(['mean_node']).forEach(function(b){try{"
            "var lon=e.longitude(b,nowJd,{zodiac:'tropical'});"
            "var sg=['Aries','Taurus','Gemini','Cancer','Leo','Virgo','Libra','Scorpio','Sagittarius','Capricorn','Aquarius','Pisces'][Math.floor(lon/30)%12];"
            "tp[b]={lon:lon,sign:sg}}catch(ex){}});return tp})(),"
            "declinationAspects:sf(function(){return Caelus.declinationAspects(e,p7,nowJd,1)}),"
            "returns:_returns,"
            "starConjunctions:sf(function(){return e.starConjunctions(chart,{orb:.5,maxMag:2.5})}),"
            "eclipses:sf(function(){return{solar:Caelus.solarEclipses(e,nowJd,nowJd+180).slice(0,2),"
            "lunar:Caelus.lunarEclipses(e,nowJd,nowJd+180).slice(0,2)}}),"
            "riseSet:{sun:{rise:sf(function(){return Caelus.riseSet(e,'sun',jd,_lat,_lon,'rise',{searchDays:1})}),"
            "set:sf(function(){return Caelus.riseSet(e,'sun',jd,_lat,_lon,'set',{searchDays:1})})},"
            "moon:{rise:sf(function(){return Caelus.riseSet(e,'moon',jd,_lat,_lon,'rise',{searchDays:1})}),"
            "set:sf(function(){return Caelus.riseSet(e,'moon',jd,_lat,_lon,'set',{searchDays:1})})}}"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p2a:'+ex.message})};__cr"
            % (jd, now_jd, lat, lon, jd, lat, lon, jd, lat, lon))

        # Phase 2b (~12s): Stations + parans + primaryDirections
        _cp("p2b",
            "var __cr;try{"
            "var jd=%s;var nowJd=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];}"
            "var _stations={};p7.forEach(function(b){"
            "_stations[b]=sf(function(){return Caelus.stations(e,b,nowJd,nowJd+120,5)})});"
            "__cr=JSON.stringify({"
            "stations:_stations,"
            "parans:sf(function(){return Caelus.parans(e,nowJd,_lat,p7,30)}),"
            "primaryDirections:sf(function(){return Caelus.primaryDirections(e,jd,_lat,_lon)})"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p2b:'+ex.message})};__cr"
            % (jd, now_jd, lat, lon, jd, lat, lon, jd, lat, lon))

        # Phase 3 (~10s): Electional (chartFeatures + searchConfigurations 90d)
        _cp("p3",
            "var __cr;try{"
            "var jd=%s;var nowJd=%s;var _lat=%s;var _lon=%s;"
            "if(typeof e==='undefined'){"
            "e=new Caelus.Engine(Caelus.embeddedData);chart=e.chartAt(%s,%s,%s,{});"
            "isDay=Caelus.isDayChart(e,%s,%s,%s);"
            "sf=function(fn){try{return fn()}catch(ex){return null}};"
            "isDayStr=isDay?'day':'night';"
            "p7=['sun','moon','mercury','venus','mars','jupiter','saturn'];}"
            "var natVec=Caelus.chartFeatures(e,jd,{bodies:p7,zodiac:'tropical'});"
            "__cr=JSON.stringify({"
            "electional:{"
            "natalFeatures:natVec,"
            "search:Caelus.searchConfigurations(e,natVec,{start:nowJd,end:nowJd+90,step:1,limit:10,bodies:p7,zodiac:'tropical'})"
            "}"
            "})"
            "}catch(ex){__cr=JSON.stringify({error:'p3:'+ex.message})};__cr"
            % (jd, now_jd, lat, lon, jd, lat, lon, jd, lat, lon))

        if caelus_data:
            result["caelus"] = caelus_data
            result["engine"] += "+Caelus"
        if caelus_errors:
            result["caelus_error"] = "; ".join(caelus_errors)
    except Exception as e:
        result["caelus_error"] = f"caelus_exception: {e}"
        import traceback; result["caelus_tb"] = traceback.format_exc()

    result["_hint"] = (
        "flatlib:本质尊贵/偶然尊贵/Sect/阿拉伯点(7Hermetic+家族+13extra)/Almutem/气质/"
        "结构/Reception/行为/LordOfGeniture/简化Hyleg/Doryphory/交点/产前朔望/"
        "星座 fertility+figure/三主(Triplicity)/Syzygy。"
        "Caelus:推运(Firdaria75y/主限/小限/太阳弧)+次限推运(月/日+水金火木土30yr)"
        "+ZR(Spirit+Fortune,当前活跃+L1-L2时限)+行运Aspects(当前)+行运位置(全7星+北交经度/星座)"
        "+FixedStars(maxMag4)+月相/日月食/留(全7星)/行星回归(水金火木土3yr)"
        "+空亡(当前)/时主星/Parans/映点+反映点/赤纬相位/日出日落"
        "/条件矩阵(dignityScore+pheno+solarPhase+house+angularity)+AlmutenFiguris"
        "+择时(Caelus.chartFeatures+searchConfigurations)(90天内最佳时机查询)。"
    )
    return result
