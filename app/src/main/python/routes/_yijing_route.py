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
        # 使用 qigua_manual() 确保飛神等字段真实计算，不手搭
        if yao_string and not hex_data:
            try:
                hex_data = i.qigua_manual(_yr,_mo,_dy,_hr,0, yao_string)
            except Exception:
                pass
            if not hex_data:
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

    # === 第2.5步：Python engine 额外数据（十二长生/策轨/节气旺相——GitHub版特有方法） ===
    if hex_data:
        try:
            gz = i.gangzhi(_yr,_mo,_dy,_hr,_min)
            if gz and len(gz) >= 4:
                dg = gz[2][0]  # 日干
                hg = gz[3][0]  # 时干
                # 2.5.1 十二长生（日支运/时支运）——按天干阳顺阴逆
                luck_day = i.find_shier_luck(dg)
                luck_hour = i.find_shier_luck(hg)
                result["十二长生"] = {"日支运": luck_day, "时支运": luck_hour}
                # Per-爻时支十二长生（attach到本卦/之卦每爻）
                for gua_key in ("本卦", "之卦"):
                    gua = hex_data.get(gua_key, {})
                    zhi_list = gua.get("地支", [])
                    if len(zhi_list) == 6:
                        gua["时支十二长生"] = [luck_hour.get(z, "") for z in zhi_list]
                # 2.5.2 日空/时空
                try:
                    ds = i.daykong_shikong(_yr,_mo,_dy,_hr,_min)
                    if ds:
                        result["日空"] = ds.get("日空")
                        result["时空"] = ds.get("時空")
                except: pass
                # 2.5.3 节气八卦旺相
                try:
                    from ichingshifa.jieqi import jq, gong_wangzhuai
                    jq_name = jq(_yr,_mo,_dy,_hr,_min)
                    ws = gong_wangzhuai(jq_name)
                    result["节气旺相"] = {
                        "节气": jq_name,
                        "旺": ws[1].get("旺"),
                        "相": ws[1].get("相"),
                        "卦旺衰表": ws[0],
                    }
                except: pass
                # 2.5.4 农历日期 + 月建
                try:
                    lunar = i.lunar_date_d(_yr,_mo,_dy)
                    if lunar: result["农历"] = "%d年%d月%d日" % (lunar["年"], lunar["月"], lunar["日"])
                except: pass
                try:
                    lm = i.find_lunar_month(gz[0]).get(lunar.get("月")) if lunar else None
                    if lm: result["月建"] = lm
                except: pass
        except Exception:
            pass

    # === 第2.6步：衍生分析（世应/飞伏/动爻/互卦——参照display_pan_m逻辑） ===
    if hex_data and yao_string:
        try:
            bg = hex_data.get("本卦", {})
            bz = bg.get("六親用神", [])
            bzhi = bg.get("地支", [])
            bwx = bg.get("五行", [])
            bsy = bg.get("世應爻", [])
            bfs = bg.get("伏神", {})
            if not isinstance(bfs, dict): bfs = {}
            b6s = bg.get("六獸", [])
            if not b6s: b6s = result.get("six_months_stars", [])

            # 世应位置索引
            shi_idx = bsy.index("世") if "世" in bsy else -1
            ying_idx = bsy.index("應") if "應" in bsy else -1
            shi_z = bzhi[shi_idx] if 0 <= shi_idx < len(bzhi) else ""
            ying_z = bzhi[ying_idx] if 0 <= ying_idx < len(bzhi) else ""

            # 2.6.1 卦缺六亲（参照display_pan_m line 708-714）
            missing = set("官父妻兄子") - set(bz)
            if missing: result["卦缺六亲"] = "".join(missing)

            # 2.6.2 世应基础信息（位置/地支/六亲）
            if shi_idx >= 0:
                result["世爻"] = {"位置": shi_idx, "地支": shi_z, "六亲": bz[shi_idx] if shi_idx < len(bz) else ""}
            if ying_idx >= 0:
                result["应爻"] = {"位置": ying_idx, "地支": ying_z, "六亲": bz[ying_idx] if ying_idx < len(bz) else ""}

            # 2.6.3 六亲持世 + 六神持世/持应（参照display_pan_m line 842-843, 856-860）
            if shi_idx >= 0 and shi_idx < len(bz):
                result["持世"] = bz[shi_idx]
            if shi_idx >= 0 and b6s and shi_idx < len(b6s):
                result["世神"] = b6s[shi_idx]
            if ying_idx >= 0 and b6s and ying_idx < len(b6s):
                result["应神"] = b6s[ying_idx]

            # 2.6.4 世应五行关系（参照display_pan_m line 829: self.find_wx_relation(shi[2], ying[2])）
            if shi_z and ying_z:
                try: result["世应关系"] = i.find_wx_relation(shi_z, ying_z)
                except: pass

            # 2.6.5 世应长生（从时支十二长生取）
            lk_h = result.get("十二长生", {}).get("时支运", {})
            if shi_z and shi_z in lk_h: result["世爻长生"] = lk_h[shi_z]
            if ying_z and ying_z in lk_h: result["应爻长生"] = lk_h[ying_z]

            # 2.6.6 世应旬空判断（参照display_pan_m line 848-851, 861-863）
            # 源代码同时检测 日空 和 时空（display_pan_m 战场版逻辑）
            rk = result.get("日空", "")
            sk2 = result.get("时空", "")
            shi_kong = set()
            if rk: shi_kong.update(rk)
            if sk2: shi_kong.update(sk2)
            if shi_z and shi_z in shi_kong:
                result["世爻旬空"] = True
            if ying_z and ying_z in shi_kong:
                result["应爻旬空"] = True

            # 2.6.7 飞伏关系（参照display_pan_m line 755: self.find_wx_relation(flygodyao[0], fugodyao[0])）
            if bfs and bfs.get("伏神爻"):
                try:
                    fly_z = bfs.get("本卦伏神所在爻", "")[2:]
                    fu_z = bfs.get("伏神爻", "")[2:]
                    if fly_z and fu_z:
                        result["飞伏关系"] = i.find_wx_relation(fly_z[0], fu_z[0])
                except: pass

            # 2.6.8 动爻分析
            # 注意：源代码display_pan_m line 879-885 优先取9(老阳)，无9才取6(老阴)
            # 不能简单地 position-first
            if "9" in yao_string:
                d_i = yao_string.index("9")
            elif "6" in yao_string:
                d_i = yao_string.index("6")
            else:
                d_i = -1
            if d_i >= 0:
                d_z = bzhi[d_i] if d_i < len(bzhi) else ""
                d_lq = bz[d_i] if d_i < len(bz) else ""
                d_wx = bwx[d_i] if d_i < len(bwx) else ""
                d_info = {"位置": d_i, "值": yao_string[d_i]}
                if d_z: d_info["地支"] = d_z
                if d_lq: d_info["六亲"] = d_lq
                if d_wx: d_info["五行"] = d_wx
                result["动爻"] = d_info
                # 动爻与世/应五行关系（参照display_pan_m line 903-908）
                if d_z and shi_z:
                    try: result["动世关系"] = i.find_wx_relation(d_z, shi_z)
                    except: pass
                if d_z and ying_z:
                    try: result["动应关系"] = i.find_wx_relation(d_z, ying_z)
                    except: pass
                # 动爻与日辰时支刑克（参照display_pan_m line 886-889: yingke字典）
                try:
                    from ichingshifa import yingke
                    gz2 = i.gangzhi(_yr,_mo,_dy,_hr,_min)
                    if gz2 and len(gz2) >= 4:
                        ri_z = gz2[2][1] if len(gz2[2]) > 1 else ""
                        hr_z = gz2[3][1] if len(gz2[3]) > 1 else ""
                        xk_list = []
                        if ri_z and i.multi_key_dict_get(yingke, ri_z+d_z):
                            xk_list.append("日辰")
                        if hr_z and i.multi_key_dict_get(yingke, hr_z+d_z):
                            xk_list.append("时支")
                        if xk_list: result["动爻刑克"] = "/".join(xk_list)
                except: pass

            # 2.6.9 互卦（参照display_pan_m line 697: wugua = ogua[1:4]+gb[2:5]）
            try:
                gb = yao_string.replace("9","8").replace("6","7")
                wu_str = yao_string.replace("9","7").replace("6","8")[1:4] + gb[2:5]
                result["互卦"] = wu_str
                # 互卦上下卦名
                eg = {'777':"乾",'778':"兌",'787':"離",'788':"震",
                      '877':"巽",'878':"坎",'887':"艮",'888':"坤"}
                wu_down = eg.get(wu_str[0:3], "")
                wu_up = eg.get(wu_str[3:6], "")
                if wu_down and wu_up: result["互卦卦名"] = wu_down+wu_up
            except: pass

            # 2.6.10 下卦上卦旺衰（参照display_pan_m line 808-813: eightgua + gong_wangzhuai）
            try:
                ogua = yao_string.replace("6","8").replace("9","7")
                eg2 = {'777':"乾金",'778':"兌金",'787':"離火",'788':"震木",
                       '877':"巽木",'878':"坎水",'887':"艮土",'888':"坤土"}
                dw_gua = eg2.get(ogua[0:3], "")
                up_gua = eg2.get(ogua[3:6], "")
                ws = result.get("节气旺相", {}).get("卦旺衰表", {})
                if dw_gua and ws:
                    result["下卦旺衰"] = dw_gua[0] + ws.get(dw_gua[0], "")
                if up_gua and ws:
                    result["上卦旺衰"] = up_gua[0] + ws.get(up_gua[0], "")
            except: pass

            # 2.6.11 爻象可视化（参照display_pan_m line 716-717: guayaodict）
            try:
                vd = {"6":"▅▅ ▅▅ X","7":"▅▅▅▅▅  ","8":"▅▅ ▅▅  ","9":"▅▅▅▅▅ O"}
                vis = {"本卦": [vd[v] for v in yao_string if v in vd]}
                gb_vis = [vd[v.replace("6","7").replace("9","8")] for v in yao_string if v in "6789"]
                if gb_vis: vis["之卦"] = gb_vis
                wu_v = result.get("互卦", "")
                if wu_v and len(wu_v) == 6:
                    vis["互卦"] = [vd[v] for v in wu_v if v in vd]
                if vis: result["爻象"] = vis
            except: pass

        except Exception:
            pass

    # === 第3步：JS decodePan（通用——凡有爻值均调用） ===
    #    decodePan内嵌了calculateQingyiXingXiu，不额外调
    if yao_string:
        try:
            yao_safe = json.dumps(yao_string)
            _js_load("iching-shifa-engine")
            # 验证引擎实际已加载（处理context被重置导致静默失效的情况）
            if json.loads(_js("iching-shifa-engine", "JSON.stringify(typeof IchingShifa)")) == "undefined":
                _js_load("iching-shifa-engine")  # 重载一次
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
                "JSON.stringify({"
                "  pan:pan,gaoDaoYiDuan:gdyd,"
                "  fourPillars:siZhu,riChen:riChen,dayKong:dayKong,jieQi:jieQi"
                "})"))
            if isinstance(js_decode, dict) and 'error' not in js_decode:
                result["iching_shifa_pan"] = js_decode
                result["engine"] += "+iching-shifa-engine"
                result["_hint"] += (" iching-shifa-engine(JS)完整排盘:本卦/之卦/互卦/纳甲/六亲/六神/世应/神煞/旬空/月建/动爻推辞+高岛易断+青衣星宿+四柱+节气。"
                    "静态词典(不变,不用随盘返回): 用eval_javascript(library='iching-shifa-engine', action='eval')按需查——"
                    "IchingShifa.GUA64_ORDER, BAGUA_XIANG, LIU_SHOU, LIU_QIN, XINGXIU_28, JIEQI_NAMES, TIAN_GAN, DI_ZHI, JIAZI_60, NAYIN_60."
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
                # 调用引擎自带的 analyze_ti_yong 算体用生克关系
                try:
                    rel = meihua_yi.analyze_ti_yong(hg["ti"]["element"], hg["yong"]["element"])
                    hg["体用生克"] = rel[0]
                    hg["体用吉凶"] = rel[1]
                except: pass
                result["meihua_formatted"]=meihua_yi.format_hexagram_text(mh_lines,mh_moving)
            else:
                # 无动爻 → 引擎 compute_hexagrams 会崩，手工搭体用
                mu=mh_lines[1:4]+mh_lines[2:5]; bg=meihua_yi.BAGUA
                hg={"main":{"lines":mh_lines,"bot":bg[tuple(mh_lines[0:3])],"top":bg[tuple(mh_lines[3:6])]},
                    "mutual":{"lines":mu,"bot":bg[tuple(mu[0:3])],"top":bg[tuple(mu[3:6])]},
                    "changed":{"lines":list(mh_lines),"bot":bg[tuple(mh_lines[0:3])],"top":bg[tuple(mh_lines[3:6])]},
                    "ti":bg[tuple(mh_lines[0:3])],"yong":bg[tuple(mh_lines[3:6])],"moving_indices":[]}
                try:
                    rel = meihua_yi.analyze_ti_yong(hg["ti"]["element"], hg["yong"]["element"])
                    hg["体用生克"] = rel[0]
                    hg["体用吉凶"] = rel[1]
                except: pass
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

    # ---- jingjue（坚荆诀，按qigua() api调用） ----
    try:
        import jingjue
        result["engine"] += "+jingjue"
        jg = jingjue.qigua()
        if jg and len(jg) >= 2:
            result["jingjue"] = {"天干": jg[0], "卦辞": jg[1]}
    except: pass

    return result
