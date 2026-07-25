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
def _yijing(method="time", seed=None, year=None, month=None, day=None, hour=None, feature="all", yao=None, numbers=None):
    # 自动生成seed以实现复盘
    if seed is None:
        seed = _rnd.randrange(1, 2**31)
    result = {
        "system": "yijing", "engine": "", "seed": seed,
        "_hint":"ichingshifa(Python)+iching-shifa-engine(JS)双引擎对照:同一爻值各自出解读。"
        "本路由返回六爻+梅花+太玄+荆诀四套数据。"
        "六爻与梅花易数共用数据入口(system='六爻'或'梅花易数'或'六爻梅花')，数据含六爻+梅花双份。"
        "六爻与梅花同源同卦：meihua_data从iching_shifa_js_yao(yao_string)转换而来，"
        "两种体系用同一组数值起卦，结果一致。"
        "system='六爻'→六爻纳甲模板, system='梅花易数'→梅花易数模板。"
        "seed参数用于复盘:传同一seed+同一method→同一组卦。"
        "起卦法及特性: "
        "time(默认,Python时间→不可复盘)/js_time(JS梅花时间)/"
        "dayan(JS大衍,真随机→不可复盘)/lueshifa(JS略筮,真随机→不可复盘)/"
        "three_number(JS三数,同seed同结果→可复盘)/"
        "number_array(JS数组,同seed同结果→可复盘)/"
        "manual_input/manual(手动输爻,需传yao参数)/"
        "coin(Python硬币,同seed同结果→可复盘)/"
        "number(Py均匀随机,同seed同结果→可复盘)。"
        "JS起卦法用JS引擎生成爻值→Python引擎同样解码→双引擎对照。"}

    # ---- 确定有效日期时间（未传参时取当前时间） ----
    _yr,_mo,_dy=year,month,day
    _hr=hour if hour is not None else 12
    _min=0
    if not (_yr and _mo and _dy):
        import datetime as _dt
        _now=_dt.datetime.now()
        _yr,_mo,_dy,_hr,_min=_now.year,_now.month,_now.day,_now.hour,_now.minute
    result["date_used"] = {"year":_yr,"month":_mo,"day":_dy,"hour":_hr}

    # === 第1步：统一爻值生成（择优引擎起卦） ===
    #   time(now)         → Python qigua_time（直接produces hex_data）
    #   dayan             → JS IchingShifa.dayan()（大衍筮法）
    #   lueshifa          → JS IchingShifa.lueshifa()（略筮法）
    #   three_number      → JS IchingShifa.threeNumberQiGua()（3数）
    #   number_array      → JS IchingShifa.numberArrayQiGua()（数组+时辰）
    #   manual_input      → 接受外部yao参数；无则seed生成→JS manualQiGua()校验
    #   coin/number       → Python _yao_from_seed（JS无对应起卦）
    #   * 所有非time方法：Python引擎从同一爻值重建hex_data，保证双引擎全量数据
    yao_string = None
    hex_data = None
    try:
        sys.path.insert(0,os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
        from ichingshifa import Iching
        i = Iching()

        if method == "time":
            hex_data = i.qigua_time(_yr,_mo,_dy,_hr,_min)
            yao_string = _extract_yao(hex_data)
        elif method == "dayan":
            _js_load("iching-shifa-engine")
            yao_string = json.loads(_js("iching-shifa-engine", "JSON.stringify(IchingShifa.dayan())"))
        elif method == "lueshifa":
            _js_load("iching-shifa-engine")
            yao_string = json.loads(_js("iching-shifa-engine", "JSON.stringify(IchingShifa.lueshifa())"))
        elif method == "three_number":
            _js_load("iching-shifa-engine")
            s=str(seed).zfill(3)  # seed=868 → "868", seed=12 → "012"
            yao_string = json.loads(_js("iching-shifa-engine",
                "JSON.stringify(IchingShifa.threeNumberQiGua(%s,%s,%s))" % (s[0],s[1],s[2])))
        elif method == "number_array":
            if numbers is None:
                raise ValueError("number_array 起卦法需要传入 numbers 参数（数字列表）")
            _js_load("iching-shifa-engine")
            hour_zhi=((_hr+1)//2)%12+1
            yao_string = json.loads(_js("iching-shifa-engine",
                "JSON.stringify(IchingShifa.numberArrayQiGua(%s,%d))" % (json.dumps(numbers),hour_zhi)))
        elif method == "js_time":
            _js_load("iching-shifa-engine")
            _tmp = json.loads(_js("iching-shifa-engine",
                "try{var sl=IchingShifa.solarToLunar(%d,%d,%d,%d);"
                "var yz=sl.yearGanZhi.di;"
                "var hz=sl.hourGanZhi.di;"
                "JSON.stringify(IchingShifa.timeQiGua(%d,%d,%d,%d,sl.month,sl.day,yz,hz));"
                "}catch(e){JSON.stringify({error:e.message})}"
                % (_yr,_mo,_dy,_hr,_yr,_mo,_dy,_hr)))
            yao_string = _tmp if isinstance(_tmp, str) and len(_tmp)==6 and all(c in '6789' for c in _tmp) else None
        elif method in ("manual_input", "manual"):
            raw = yao if yao else _yao_from_seed(seed, "dayan")
            _js_load("iching-shifa-engine")
            _m = _js("iching-shifa-engine", "JSON.stringify(IchingShifa.manualQiGua(%s))" % json.dumps(raw))
            if _m and _m.startswith('"') and len(_m) >= 8:
                yao_string = json.loads(_m)
        elif method in ("coin", "number"):
            yao_string = _yao_from_seed(seed, method)
        else:
            hex_data = i.qigua_time(_yr,_mo,_dy,_hr,_min)
            yao_string = _extract_yao(hex_data)

        # 非time方法：Python引擎从爻值重建hex_data（双引擎对照）
        if yao_string and not hex_data:
            gz = i.gangzhi(_yr,_mo,_dy,_hr,_min)
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

        if yao_string:
            result["iching_shifa_js_yao"] = yao_string

    except Exception as e:
        result["py_error"] = str(e)

    # === 第2步：Python enrichment（六兽） ===
    if hex_data and yao_string:
        try:
            gz = i.gangzhi(_yr,_mo,_dy,_hr,_min)
            if gz and len(gz) > 2:
                rg = gz[2][0] if gz[2] else "癸"
                result["six_months_stars"] = i.find_six_mons(rg)
        except: pass

    # === 第3步：JS decodePan（通用——凡有爻值均调用） ===
    #    decodePan内嵌了calculateQingyiXingXiu，不额外调
    if yao_string:
        try:
            yao_safe = json.dumps(yao_string)
            _js_load("iching-shifa-engine")
            js_decode = json.loads(_js("iching-shifa-engine",
                "var r="+yao_safe+";"
                "var pan=null;try{pan=IchingShifa.decodePan(r,{year:"+str(_yr)+",month:"+str(_mo)+",day:"+str(_dy)+",hour:"+str(_hr)+"});}catch(e){pan={error:e.message}}"
                "var gdyd=null;try{gdyd=IchingShifa.getGaoDaoYiDuan(r);}catch(e){}"
                "var _rd=function(y,m,d){var t=new Date(y,m-1,d),r=new Date(1900,0,1);"
                "var idx=((Math.round((t-r)/86400000)+10)%60+60)%60;"
                "return ['甲','乙','丙','丁','戊','己','庚','辛','壬','癸'][idx%10]+['子','丑','寅','卯','辰','巳','午','未','申','酉','戌','亥'][idx%12]};"
                "var riChen=_rd("+str(_yr)+","+str(_mo)+","+str(_dy)+");"
                "var siZhu=null;try{var sl=IchingShifa.solarToLunar("+str(_yr)+","+str(_mo)+","+str(_dy)+","+str(_hr)+");siZhu={year:sl.yearGanZhi,month:sl.monthGanZhi,day:sl.dayGanZhi,hour:sl.hourGanZhi};}catch(e){}"
                "var dayKong=null;try{if(pan&&pan.ganZhiDay){dayKong=IchingShifa.calcXunKong(pan.ganZhiDay.gz);}}catch(e){}"
                "var jieQi=null;try{jieQi=IchingShifa.getCurrentSolarTerm("+str(_yr)+","+str(_mo)+","+str(_dy)+");}catch(e){}"
                "var liuyao={};"
                "if(pan&&!pan.error){Object.keys(pan).forEach(function(k){liuyao[k]=pan[k]});}"
                "liuyao.riChen=riChen;"
                "var consts=null;try{consts={"
                "  GUA64_ORDER:IchingShifa.GUA64_ORDER,"
                "  BAGUA_XIANG:IchingShifa.BAGUA_XIANG,"
                "  LIU_SHOU:IchingShifa.LIU_SHOU,"
                "  LIU_QIN:IchingShifa.LIU_QIN,"
                "  XINGXIU_28:IchingShifa.XINGXIU_28,"
                "  JIEQI_NAMES:IchingShifa.JIEQI_NAMES,"
                "  TIAN_GAN:IchingShifa.TIAN_GAN,"
                "  DI_ZHI:IchingShifa.DI_ZHI,"
                "  JIAZI_60:IchingShifa.JIAZI_60,"
                "  NAYIN_60:IchingShifa.NAYIN_60"
                "};}catch(e){}"
                "JSON.stringify({"
                "  pan:pan,gaoDaoYiDuan:gdyd,liuyao:liuyao,"
                "  fourPillars:siZhu,riChen:riChen,dayKong:dayKong,jieQi:jieQi,"
                "  constants:consts"
                "})"))
            if isinstance(js_decode, dict) and 'error' not in js_decode:
                result["iching_shifa_pan"] = js_decode
                result["engine"] += "+iching-shifa-engine"
                result["_hint"] += (" iching-shifa-engine(JS)完整排盘:本卦/之卦/互卦/纳甲/六亲/六神/世应/神煞/旬空/月建/动爻推辞+高岛易断+青衣星宿+四柱+节气+64卦库+纳音表+28宿+甲子。"
                    "自探索:Object.keys(IchingShifa)含lueshifa/threeNumberQiGua/numberArrayQiGua/manualQiGua/solarToLunar等。")
        except Exception as e:
            if "py_error" not in result: result["py_error"] = str(e)

    # ---- 梅花易数（从yao_string经官方API转换，与六爻同源同卦） ----
    if yao_string:
        try:
            import meihua_yi
            result["engine"] += "+meihua_yi"
            # 用引擎官方 qigua_coin(coin_results=...) 做转换，不走手动构造
            coin_vals=[int(c) for c in yao_string]
            mh_lines,mh_moving,mh_details=meihua_yi.qigua_coin(coin_results=coin_vals)
            result["meihua_data"]={
                "lines":mh_lines,"moving":mh_moving,"details":mh_details}
            result["meihua_gua_name"]=meihua_yi.get_gua_name(mh_lines)
            if mh_moving:
                # 有动爻 → 走引擎 compute_hexagrams 做体用
                hg=meihua_yi.compute_hexagrams(mh_lines,mh_moving)
                result["meihua_formatted"]=meihua_yi.format_hexagram_text(mh_lines,mh_moving)
            else:
                # 无动爻 → 引擎 compute_hexagrams 会崩，手工搭体用
                mu=mh_lines[1:4]+mh_lines[2:5]; bg=meihua_yi.BAGUA
                hg={"main":{"lines":mh_lines,"bot":bg[tuple(mh_lines[0:3])],"top":bg[tuple(mh_lines[3:6])]},
                    "mutual":{"lines":mu,"bot":bg[tuple(mu[0:3])],"top":bg[tuple(mu[3:6])]},
                    "changed":{"lines":list(mh_lines),"bot":bg[tuple(mh_lines[0:3])],"top":bg[tuple(mh_lines[3:6])]},
                    "ti":bg[tuple(mh_lines[0:3])],"yong":bg[tuple(mh_lines[3:6])],"moving_indices":[]}
            result["meihua_tiyong"]=hg
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
