"""Route:  vedic"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 吠陀 =====
def _vedic(year,month,day,hour,tz,lat=None,lon=None,depth="standard"):
    # 兼容字符串参数
    if isinstance(lat, str): lat = float(lat)
    if isinstance(lon, str): lon = float(lon)
    if isinstance(tz, str): tz = float(tz)
    date_str=f"{year}-{month:02d}-{day}"
    # 构建时区偏移字符串
    tz_vd = float(tz) if tz is not None else 8.0
    tz_vd_sign = "+" if tz_vd >= 0 else "-"
    tz_vd_abs = abs(tz_vd)
    tz_vd_str = f"{tz_vd_sign}{int(tz_vd_abs):02d}:{int((tz_vd_abs - int(tz_vd_abs)) * 60 + 0.5):02d}"
    iso_vd_date = f"{date_str}T{hour:02d}:00:00{tz_vd_str}"

    result={"system":"vedic"}
    # ===== 默认主力: PyJHora (Python/Chaquopy) =====
    try:
        from jhora import const, utils
        from jhora.panchanga import drik
        from jhora.horoscope.chart import house, strength, raja_yoga, yoga, dosha, ashtakavarga, arudhas
        from jhora.horoscope.dhasa.graha import vimsottari
        place=drik.Place("loc",lat or 0,lon or 0,float(tz) if tz and str(tz).lstrip('-+').replace('.','',1).isdigit() else 0)
        jd_local=utils.julian_day_number(drik.Date(year,month,day),(hour,0,0))
        # 1. 排盘
        pp=drik.dhasavarga(jd_local,place,1)
        asc_raw=drik.ascendant(jd_local,place)
        asc_house,asc_long=drik.dasavarga_from_long(asc_raw[0]*30+asc_raw[1],1)
        pp+=[[const._ascendant_symbol,(asc_house,asc_long)]]
        p_to_h={p:h for p,(h,_) in pp}
        h_to_p=utils.get_house_planet_list_from_planet_positions(pp)
        result["pyjhora"]={"planets":str(pp[:9]),"lagna":{"rasi":asc_raw[0],"deg":asc_raw[1],"nak":asc_raw[2],"pada":asc_raw[3]}}
        result["engine"]="PyJHora"
        # 2. Panchanga (独立try)
        try:
            result["panchanga"]={
                "tithi": drik.tithi(jd_local,place),
                "nakshatra": drik.nakshatra(jd_local,place),
                "yogam": drik.yogam(jd_local,place),
                "karana": drik.karana(jd_local,place),
                "vaara": drik.vaara(jd_local,place),
                "sunrise": drik.sunrise(jd_local,place),
                "sunset": drik.sunset(jd_local,place),
            }
        except: pass
        # 3. 宫位分析 (独立try)
        try:
            result["houses"]={
                "planets_in_quadrants": house.get_planets_in_quadrants(p_to_h),
                "planets_in_trines": house.get_planets_in_trines(p_to_h),
                "planets_in_dushthanas": house.get_planets_in_dushthanas(p_to_h),
            }
        except: pass
        # 4. Shadbala (独立try)
        try: result["shadbala"]=str(strength.shad_bala(jd_local,place))
        except: pass
        try: result["bhava_bala"]=str(strength.bhava_bala(jd_local,place))
        except: pass
        # 5. Ashtakavarga (独立try)
        try: result["ashtakavarga"]=str(ashtakavarga.get_ashtaka_varga(p_to_h))
        except: pass
        # 6. Raja Yoga (独立try)
        try: result["raja_yoga"]=str(raja_yoga.get_raja_yoga_details(jd_local,place))
        except: pass
        try: result["yoga_details"]=str(yoga.get_yoga_details(jd_local,place))
        except: pass
        # 7. Dosha (独立try)
        try: result["dosha"]={"manglik":str(dosha.manglik(pp))}
        except: pass
        # 8. Arudha (独立try)
        try: result["arudha"]=str(arudhas.bhava_arudhas_from_planet_positions(pp))
        except: pass
        # 9. Vimshottari Dasha (独立try)
        try: result["vimshottari"]=str(vimsottari.get_vimsottari_dhasa_bhukthi(jd_local,place))
        except: pass
    except Exception as e:
        result["pyjhora_error"]=str(e)
        result["engine"]=""
    # ===== 辅助: NodeJhora (DE440精密+Jaimini+Ashtakavarga+Yoga+Shadbala) =====
    if lat and lon:
        _js_load("node-jhora-engine")
        nj=_js("node-jhora-engine",
                "try{"
                "var dt=NodeJhora.DateTime.fromISO('%s');"
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
                    iso_vd_date, lat, lon, lat, lon))
        result["nodejhora"]=nj
        result["engine"]+="+NodeJhora"
    result["_hint"]=("PyJHora已全量:Panchanga/Shadbala/Ashtakavarga/RajaYoga774/Dosha/Arudha/Vimshottari。\nJS引擎(NodeJhora/Caelus/NatalEngine)走eval_javascript直接调。\n自探索:dir(jhora)更多Dasha。Object.keys(NodeJhora)/Object.keys(Caelus)")
    # ===== 深度模式: NatalEngine(文本) + Caelus(分盘/Ashtottari) + PyJHora深度补充 =====
    if depth=="deep":
        # JS引擎(APK上Python桥可能不通,独立try)
        try:
            _js_load("natalengine-engine")
            v=_js("natalengine-engine",f"JSON.stringify(NatalEngine.calculateVedic('{date_str}',{hour},{tz},{lat or 0},{lon or 0}))")
            if v and 'bridge not available' not in v: result["natal"]=v; result["engine"]+="+NatalEngine"
        except: pass
        try:
            _js_load("caelus-engine")
            if lat and lon:
                c=_js("caelus-engine","var e=new Caelus.Engine(Caelus.embeddedData);var jd=Caelus.isoToJd('%s');var chart=e.chartAt(jd,%f,%f,{zodiac:'sidereal'});var moonLon=e.longitude('moon',jd,{zodiac:'sidereal:lahiri'});JSON.stringify({varga9:Caelus.vargaAt(e,jd,9),vimshottari:Caelus.vimshottariDashas(moonLon,jd),ashtottari:Caelus.ashtottariAt(e,jd,jd,%f,%f),yogini:Caelus.yoginiAt(e,jd,jd,%f,%f)})"%(iso_vd_date,lat,lon,lat,lon,lat,lon))
                if c and 'bridge not available' not in c: result["caelus_deep"]=c; result["engine"]+="+Caelus"
        except: pass
        # PyJHora深度
        try:
            from jhora.horoscope.dhasa.graha import ashtottari as a_py, yogini as y_py
            result["varga_d9"]=str(drik.dhasavarga(jd_local,place,9))
            result["varga_d10"]=str(drik.dhasavarga(jd_local,place,10))
            result["varga_d60"]=str(drik.dhasavarga(jd_local,place,60))
            result["ashtottari_dasha"]=str(a_py.get_ashtottari_dhasa_bhukthi(jd_local,place))
            result["yogini_dasha"]=str(y_py.get_dhasa_bhukthi(drik.Date(year,month,day),(hour,0,0),place))
            result["engine"]+="+PyJHora_deep"
        except: pass
        # raasi模块(Narayana/Chara)可能缺失,单独try
        try:
            from jhora.horoscope.dhasa.raasi import narayana, chara
            result["narayana_dasha"]=str(narayana.narayana_dhasa_for_rasi_chart(drik.Date(year,month,day),(hour,0,0),place))
            result["chara_dasha"]=str(chara.get_dhasa_antardhasa(drik.Date(year,month,day),(hour,0,0),place))
            result["engine"]+="+raasi"
        except: pass
    return result
