"""Route:  yijing"""
import json, sys, os, random as _rnd
from ._shared import _js, _js_load

def _extract_yao(hex_data):
    """从Python ichingshifa结果中提取6位爻值字符串(6789)。
    大衍筮法=[爻值串(6位), 本卦名, 变卦名, 卦爻辞dict, 动爻分析...]。
    原bug:对全元素调int()遇上中文卦名崩溃。"""
    if hex_data is None:
        return None
    if isinstance(hex_data, str) and len(hex_data) >= 6 and all(c in '6789' for c in hex_data[:6]):
        return hex_data[:6]
    if isinstance(hex_data, dict):
        _da = hex_data.get("大衍筮法")
        if isinstance(_da, (list, tuple)) and len(_da) > 0:
            v = _da[0]
            if isinstance(v, str) and len(v) >= 6:
                for c in v[:6]:
                    if c not in '6789': break
                else:
                    return v[:6]
    return None

def _yao_from_seed(seed, method):
    """用seed确定性生成6个爻值(6789)，method决定概率分布。"""
    rng = _rnd.Random(seed)
    if method == "coin":
        ws = [1, 3, 3, 1]        # 三枚硬币: 6=1/8, 7=3/8, 8=3/8, 9=1/8
    elif method in ("dayan", "manual"):
        ws = [1, 5, 7, 3]        # 大衍筮法: 6=1/16, 7=5/16, 8=7/16, 9=3/16
    else:
        ws = [1, 1, 1, 1]        # 默认均匀
    return "".join(str(v) for v in rng.choices([6, 7, 8, 9], weights=ws, k=6))

# ===== 六爻梅花 =====
def _yijing(method="time", seed=None, year=None, month=None, day=None, feature="all"):
    # 自动生成seed以实现复盘
    if seed is None:
        seed = _rnd.randrange(1, 2**31)
    result = {
        "system": "yijing", "engine": "", "seed": seed,
        "_hint":"ichingshifa(Python)+iching-shifa-engine(JS)双引擎对照:同一爻值各自出解读。"
        "本路由返回六爻+梅花+太玄+荆诀四套数据。"
        "六爻与梅花易数共用数据入口(system='六爻'或'梅花易数'或'六爻梅花')，数据含六爻+梅花双份。"
        "system='六爻'→六爻纳甲模板, system='梅花易数'→梅花易数模板。"
        "seed参数用于复盘:传同一seed+同一method→同一组卦。"}

    # ---- 确定有效日期时间 ----
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
    if not (_yr and _mo and _dy):
        import datetime as _dt
        _now=_dt.datetime.now()
        _yr,_mo,_dy,_hr,_min=_now.year,_now.month,_now.day,12,0
    result["date_used"] = {"year":_yr,"month":_mo,"day":_dy}

    # === 第1步：生成爻值 + Python引擎排盘 ===
    yao_string = None
    hex_data = None
    try:
        sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
        from ichingshifa import Iching
        i = Iching()

        if method in ("time", "now"):
            hex_data = i.qigua_time(_yr,_mo,_dy,12,0)
            yao_string = _extract_yao(hex_data)
        else:
            # dayan/manual/number/coin: 先确定爻值
            if method in ("dayan", "manual"):
                yao_string = _yao_from_seed(seed, "dayan")
            elif method == "number":
                yao_string = _yao_from_seed(seed, "number")
            elif method == "coin":
                yao_string = _yao_from_seed(seed, "coin")
            else:
                hex_data = i.qigua_time(_yr,_mo,_dy,12,0)
                yao_string = _extract_yao(hex_data)

            # 非time方法: 从爻值手动构建hex_data(qigua_manual不存在于原库)
            if yao_string and not hex_data:
                gz = i.gangzhi(_yr,_mo,_dy,12,0)
                dg = gz[2] if gz and len(gz) > 2 else None
                details = i.mget_bookgua_details(yao_string)
                if details:
                    by = yao_string.replace("6","7").replace("9","8")
                    bg = i.decode_gua(yao_string, dg) if dg else {}
                    zg = i.decode_gua(by, dg) if dg else {}
                    hex_data = {"日期":"%d年%d月%d日"%(_yr,_mo,_dy), "大衍筮法":details, "本卦":bg, "之卦":zg, "飛神":""}

        if hex_data:
            result["ichingshifa"] = hex_data
            result["engine"] += "ichingshifa"

    except Exception as e:
        result["py_error"] = str(e)

    # === 第2步：Python 通用 enrichment（用巳生成的爻值） ===
    if hex_data and isinstance(hex_data, dict):
        try:
            gz = None
            try:
                gz = i.gangzhi(_yr,_mo,_dy,12,0)
                if gz and len(gz) > 2:
                    rg = gz[2][0] if gz[2] else "癸"
                    result["six_months_stars"] = i.find_six_mons(rg)
                    result["shier_luck"] = i.find_shier_luck(rg)
                if gz and len(gz) >= 4:
                    try: result["count_yy"] = i.count_yy(gz[0],gz[1],gz[2],gz[3])
                    except: pass
            except: pass
            try: result["daykong"] = i.daykong_shikong(_yr,_mo,_dy,12,0)
            except: pass
            try: result["innate_cegui"] = i.innate_cegui(_yr,_mo,_dy,12,0)
            except: pass
            try: result["acquired_cegui"] = i.acquired_cegui(_yr,_mo,_dy,12,0)
            except: pass
            ben = hex_data.get("本卦",{})
            zhi = hex_data.get("之卦",{})
            result["decode_gua"] = ben
            if zhi:
                result["decode_two_gua"] = {"本卦":ben,"之卦":zhi}
        except: pass
        if yao_string:
            try: result["gua_description"] = i.show_sixtyfourguadescription(yao_string)
            except: pass
            try:
                if len(yao_string) >= 6:
                    hv2 = yao_string[:6]
                    if all(c in '6789' for c in hv2):
                        result["guaike"] = i.guaike(_yr,_mo,_dy,12,0,int(hv2[:3]),int(hv2[3:6]))
            except: pass

    # === 第3步：JS 双引擎对照（同一爻值→decodePan完整排盘+高岛+青衣） ===
    if yao_string:
        try:
            yao_safe = json.dumps(yao_string)
            _js_load("iching-shifa-engine")
            js_decode = json.loads(_js("iching-shifa-engine",
                "var r="+yao_safe+";"
                "var pan=null;try{pan=IchingShifa.decodePan(r,{year:"+str(_yr)+",month:"+str(_mo)+",day:"+str(_dy)+",hour:12});}catch(e){pan={error:e.message}}"
                "var gdyd=null;try{gdyd=IchingShifa.getGaoDaoYiDuan(r);}catch(e){}"
                "var qyxx=null;try{qyxx=IchingShifa.calculateQingyiXingXiu(r,"+str(_yr)+","+str(_mo)+","+str(_dy)+");}catch(e){}"
                # 日辰计算：1900-01-01=甲戌日(cycle idx 10)
                "var _rd=function(y,m,d){var t=new Date(y,m-1,d),r=new Date(1900,0,1);"
                "var idx=((Math.round((t-r)/86400000)+10)%60+60)%60;"
                "return ['甲','乙','丙','丁','戊','己','庚','辛','壬','癸'][idx%10]+['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥'][idx%12]};"
                "var riChen=_rd("+str(_yr)+","+str(_mo)+","+str(_dy)+");"
                # 展开pan的所有顶层字段
                "var liuyao={};"
                "if(pan&&!pan.error){Object.keys(pan).forEach(function(k){liuyao[k]=pan[k]});}"
                "liuyao.riChen=riChen;"
                "JSON.stringify({pan:pan,gaoDaoYiDuan:gdyd,qingyiXingXiu:qyxx,liuyao:liuyao})"))
            if isinstance(js_decode, dict) and 'error' not in js_decode:
                result["iching_shifa_pan"] = js_decode
                # 展开liuyao到顶层，方便AI直接访问
                liuyao = js_decode.get("liuyao", {})
                if isinstance(liuyao, dict):
                    for k, v in liuyao.items():
                        result["liuyao_" + k] = v
                result["engine"] += "+iching-shifa-engine"
                # _hint 追加 JS 可用API
                result["_hint"] += (" iching-shifa-engine(JS)完整排盘:本卦/之卦/互卦/纳甲/六亲/六神/世应/神煞/旬空/月建/动爻推辞+高岛易断+青衣星宿。"
                    "自探索:Object.keys(IchingShifa)含timeQiGua/timeQiGua/lueshifa/threeNumberQiGua/solarToLunar/shenSha等。")
        except Exception as e:
            if "py_error" not in result: result["py_error"] = str(e)

    # ---- 梅花易数（独立于六爻） ----
    try:
        import meihua_yi
        result["engine"] += "+meihua_yi"
        if method == "coin":
            _rnd.seed(seed)
            coin_result = meihua_yi.qigua_coin()
            result["meihua_coin"] = {
                "lines":[y for y in coin_result[0]],
                "moving":[y for y in coin_result[1]],
                "details":coin_result[2],
            }
        else:
            time_result = meihua_yi.qigua_time()
            result["meihua_time"] = {
                "lines":[y for y in time_result[0]],
                "moving":[y for y in time_result[1]],
            }
        gua = result.get("meihua_coin") or result.get("meihua_time") or {}
        if gua.get("lines"):
            result["meihua_formatted"] = meihua_yi.format_hexagram_text(gua["lines"],gua.get("moving",[]))
            result["meihua_gua_name"] = meihua_yi.get_gua_name(gua["lines"])
            try:
                lines_list = gua["lines"]
                moving_positions = gua.get("moving",[])
                hg = meihua_yi.compute_hexagrams(lines_list,moving_positions)
                if isinstance(hg, dict):
                    result["meihua_tiyong"] = hg
                elif isinstance(hg, (list, tuple)) and len(hg) >= 4:
                    result["meihua_tiyong"] = {"original":hg[0],"mutual":hg[1],"changed":hg[2],"ti_yong":hg[3]}
            except: pass
    except: pass

    # ---- taixuanshifa ----
    try:
        import taixuanshifa
        result["engine"] += "+taixuanshifa"
        tx = taixuanshifa.Taixuan(_yr,_mo,_dy,12)
        try: result["taixuan_pan"] = tx.pan()
        except: pass
        try: result["taixuan_qigua"] = tx.qigua_number()
        except: pass
        try: result["taixuan_dz"] = tx.getdz()
        except: pass
        try: result["taixuan_dz_date"] = tx.getdz_date()
        except: pass
    except: pass

    # ---- jingjue ----
    try:
        import jingjue
        result["engine"] += "+jingjue"
        if hasattr(jingjue,'jie'):
            result["jingjue"] = jingjue.jie(seed or "777777")
    except: pass

    return result
