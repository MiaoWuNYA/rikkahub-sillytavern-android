"""Route:  yijing"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 六爻梅花 =====
def _yijing(method="time",seed=None,year=None,month=None,day=None,feature="all"):
    result={"system":"yijing","engine":"","_hint":
        "ichingshifa(Iching类)已返回起卦+解卦。meihua_yi梅花全API+taixuanshifa太玄+jingjue荆诀。JS引擎对照(IchingShifa)。自探索:dir(ichingshifa)/dir(meihua_yi)/dir(Taixuan)/dir(jingjue)"}
    hex_values=None

    # ---- 确定有效的日期时间（所有后续计算共用） ----
    _seed_dt=None
    if seed is not None and not (year and month and day):
        import datetime as _dt
        try:
            _s=seed/1000 if seed>1e12 else seed
            _seed_dt=_dt.datetime.fromtimestamp(_s,_dt.timezone.utc).astimezone(_dt.timezone(_dt.timedelta(hours=8)))
        except: pass

    _yr,_mo,_dy,_hr,_min=year,month,day,12,0
    if _seed_dt is not None:
        _yr,_mo,_dy,_hr,_min=_seed_dt.year,_seed_dt.month,_seed_dt.day,_seed_dt.hour,_seed_dt.minute
    if not (_yr and _mo and _dy):  # 无指定时间 → 使用当前时间
        import datetime as _dt
        _now=_dt.datetime.now()
        _yr,_mo,_dy,_hr,_min=_now.year,_now.month,_now.day,12,0

    # ---- ichingshifa 起卦（按方法分支） ----
    try:
        sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
        from ichingshifa import Iching
        i=Iching()

        if method=="dayan" or method=="manual":
            _js_load("iching-shifa-engine")
            js_yao=_js("iching-shifa-engine","JSON.stringify(IchingShifa.dayan())")
            result["iching_shifa_js_yao"]=js_yao
            gua_str=json.loads(js_yao).get("yao","697887") if isinstance(js_yao,str) else "697887"
            hex_data=i.qigua_manual(_yr,_mo,_dy,12,0,gua_str)
            result["ichingshifa"]=hex_data
            hex_values=gua_str

        elif method=="time":
            hex_data=i.qigua_time(_yr,_mo,_dy,12,0)
            result["ichingshifa"]=hex_data

        elif method=="number":
            n=seed if seed is not None else 42
            import random as _rnd
            _rnd.seed(n)
            hex_data=i.bookgua()
            result["ichingshifa"]=hex_data

        else:
            # 默认路径：使用统一时间起卦，与 enrichment 保持一致
            hex_data=i.qigua_time(_yr,_mo,_dy,12,0)
            result["ichingshifa"]=hex_data

        result["engine"]+="ichingshifa"

        # === 统一提取 hex_values（所有方法都在这里补提） ===
        if hex_values is None:
            if isinstance(hex_data, str) and len(hex_data) >= 6 and all(c in '6789' for c in hex_data[:6]):
                # bookgua() 直接返回6位爻值字符串
                hex_values = hex_data[:6]
            elif isinstance(hex_data, dict):
                _da = hex_data.get("大衍筮法")
                if isinstance(_da, (list, tuple)) and len(_da) > 0:
                    hex_values = "".join(str(int(x)) for x in _da[:6])
                if hex_values is None:
                    # 降级：从"本卦"的 lines 字段提取
                    _ben = hex_data.get("本卦", {})
                    if isinstance(_ben, dict) and "lines" in _ben:
                        _lines = _ben["lines"]
                        if isinstance(_lines, (list, tuple)) and len(_lines) == 6:
                            hex_values = "".join(str(y) for y in _lines)

        # === 通用 enrichment（一直缺失了！） ===
        try: result["daykong"]=i.daykong_shikong(_yr,_mo,_dy,12,0)
        except: pass
        try: result["innate_cegui"]=i.innate_cegui(_yr,_mo,_dy,12,0)
        except: pass
        try: result["acquired_cegui"]=i.acquired_cegui(_yr,_mo,_dy,12,0)
        except: pass
        try:
            gz=i.gangzhi(_yr,_mo,_dy,12,0)
            if gz and len(gz)>2:
                rg=gz[2][0] if gz[2] else "癸"
                result["six_months_stars"]=i.find_six_mons(rg)
                result["shier_luck"]=i.find_shier_luck(rg)
            # 五行旺相休囚 (神煞计数, CI patch已加校验)
            if gz and len(gz)>=4:
                try: result["count_yy"]=i.count_yy(gz[0],gz[1],gz[2],gz[3])
                except: pass
        except: pass
        try:
            if isinstance(hex_data, dict):
                ben=hex_data.get("本卦",{})
                zhi=hex_data.get("之卦",{})
                result["decode_gua"]=ben
                if zhi:
                    result["decode_two_gua"]={"本卦":ben,"之卦":zhi}
            else:
                result["decode_gua"]={"raw_hex": hex_values}
        except: pass
        if hex_values:
            try: result["gua_description"]=i.show_sixtyfourguadescription(str(hex_values))
            except: pass
            try:
                if len(str(hex_values))>=6:
                    hv=str(hex_values)
                    result["guaike"]=i.guaike(_yr,_mo,_dy,12,0,int(hv[:3]),int(hv[3:6]))
            except: pass
    except Exception as e: result["py_error"]=str(e)

    # ---- 梅花易数（独立起卦，与六爻无关） ----
    try:
        import meihua_yi
        result["engine"]+="+meihua_yi"
        if method=="coin":
            coin_result=meihua_yi.qigua_coin()
            result["meihua_coin"]={
                "lines":[y for y in coin_result[0]],
                "moving":[y for y in coin_result[1]],
                "details":coin_result[2],
            }
        else:
            time_result=meihua_yi.qigua_time()
            result["meihua_time"]={
                "lines":[y for y in time_result[0]],
                "moving":[y for y in time_result[1]],
            }
        gua=result.get("meihua_coin") or result.get("meihua_time") or {}
        if gua.get("lines"):
            result["meihua_formatted"]=meihua_yi.format_hexagram_text(gua["lines"],gua.get("moving",[]))
            result["meihua_gua_name"]=meihua_yi.get_gua_name(gua["lines"])
            try:
                lines_list=gua["lines"]
                moving_positions=gua.get("moving",[])
                hg=meihua_yi.compute_hexagrams(lines_list,moving_positions)
                if isinstance(hg, dict):
                    result["meihua_tiyong"] = hg
                elif isinstance(hg, (list, tuple)) and len(hg) >= 4:
                    result["meihua_tiyong"] = {"original":hg[0],"mutual":hg[1],"changed":hg[2],"ti_yong":hg[3]}
            except: pass
    except: pass

    # ---- taixuanshifa（使用共用日期时间） ----
    try:
        import taixuanshifa
        result["engine"]+="+taixuanshifa"
        tx=taixuanshifa.Taixuan(_yr,_mo,_dy,12)
        try: result["taixuan_pan"]=tx.pan()
        except: pass
        try: result["taixuan_qigua"]=tx.qigua_number()
        except: pass
        try: result["taixuan_dz"]=tx.getdz()
        except: pass
        try: result["taixuan_dz_date"]=tx.getdz_date()
        except: pass
    except: pass

    # ---- jingjue ----
    try:
        import jingjue
        result["engine"]+="+jingjue"
        if hasattr(jingjue,'jie'):
            result["jingjue"]=jingjue.jie(seed or "777777")
    except: pass

    # ---- JS 双引擎对照（hex_values 可用时始终运行） ----
    if hex_values is not None:
        _js_load("iching-shifa-engine")
        yao_str_safe=json.dumps(str(hex_values))
        # iching_shifa_js：JS 解码的原始爻值
        try:
            result["iching_shifa_js"]=json.loads(_js("iching-shifa-engine","JSON.stringify("+yao_str_safe+")"))
        except: pass
        # iching_shifa_pan：decodePan + 高岛易断 + 青衣星宿
        try:
            result["iching_shifa_pan"]=json.loads(_js("iching-shifa-engine",
                "var r="+yao_str_safe+";"
                "var pan=IchingShifa.decodePan(r,{year:"+str(_yr)+",month:"+str(_mo)+",day:"+str(_dy)+",hour:12});"
                "var gdyd=null;try{gdyd=IchingShifa.getGaoDaoYiDuan(r);}catch(e){}"
                "var qyxx=null;try{qyxx=IchingShifa.calculateQingyiXingXiu(r,"+str(_yr)+","+str(_mo)+","+str(_dy)+");}catch(e){}"
                "JSON.stringify({pan:pan,gaoDaoYiDuan:gdyd,qingyiXingXiu:qyxx})"))
        except: pass
        result["engine"]+="+iching-shifa-engine"
    return result
