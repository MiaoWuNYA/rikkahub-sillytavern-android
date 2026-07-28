"""Route:  bazi"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 八字 =====
def _bazi(year, month, day, hour, minute=0, gender=1, feature="bazi"):
    # 兼容Kotlin传来的"male"/"female"字符串
    if isinstance(gender, str):
        gender = 1 if gender.lower() in ("male", "男", "m") else 0
    from lunar_python import Solar
    s = Solar.fromYmdHms(year, month, day, hour, minute, 0)
    l = s.getLunar()
    ec = l.getEightChar()
    yun = ec.getYun(gender)
    dayun_list = []
    for dy in yun.getDaYun():
        gz = dy.getGanZhi()
        if gz: dayun_list.append({"ganzhi":gz, "start_age":dy.getStartAge(), "end_age":dy.getEndAge(),
                                   "liunian":[ln.getGanZhi() for ln in dy.getLiuNian()]})
    # 独立模块模式 — 跳过排盘, 直接返回
    if feature in ("shengxiao","luohou"):
        result = {"system":"bazi"}
        try:
            import sys as _sys; _sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
            if feature == "shengxiao":
                from bazi_china import shengxiao as _sx
                zhi = l.getYearZhi()
                attrs = _sx.zhi_atts.get(zhi, {})
                animals = dict(_sx.shengxiaos)
                def _to_animal(v):
                    if isinstance(v, tuple): return [animals.get(x,x) for x in v]
                    return animals.get(v,v)
                result["shengxiao_pairing"] = {"zhi": zhi, "animal": animals.get(zhi,zhi),
                    "relations": {k: _to_animal(v) for k,v in attrs.items()}}
            elif feature == "luohou":
                from bazi_china import luohou as _lh
                result["luohou"] = {
                    "yearly_nine_stars": _lh.yearly_nine_stars(year),
                    "monthly_nine_stars": _lh.monthly_nine_stars(l.getYearZhi()),
                    "daily_nine_stars": _lh.daily_nine_stars(l),
                }
                try: result["luohou"]["jizhu"] = _lh.get_jizhu(l.getYearGan(), l.getYearZhi())
                except: pass
            result["_hint"] = f"独立{feature}模式已返回。需要完整八字请去掉feature参数。"
            return result
        except Exception as e:
            return {"system":"bazi","error":str(e),"_hint":"bazi_china不可用(仅APK内)。lunar_python基础数据已返回。"}
    result = {
        "system":"bazi","engine":"lunar_python",
        "four_pillars":{
            "year":{"gan":ec.getYearGan(),"zhi":ec.getYearZhi(),"ganzhi":ec.getYear(),"wuxing":ec.getYearWuXing(),"nayin":ec.getYearNaYin(),"xunkong":ec.getYearXunKong(),"hide_gan":ec.getYearHideGan(),"shishen":ec.getYearShiShenGan(),"shishen_zhi":ec.getYearShiShenZhi(),"xun":ec.getYearXun(),"dishi":ec.getYearDiShi()},
            "month":{"gan":ec.getMonthGan(),"zhi":ec.getMonthZhi(),"ganzhi":ec.getMonth(),"wuxing":ec.getMonthWuXing(),"nayin":ec.getMonthNaYin(),"xunkong":ec.getMonthXunKong(),"hide_gan":ec.getMonthHideGan(),"shishen":ec.getMonthShiShenGan(),"shishen_zhi":ec.getMonthShiShenZhi(),"xun":ec.getMonthXun(),"dishi":ec.getMonthDiShi()},
            "day":{"gan":ec.getDayGan(),"zhi":ec.getDayZhi(),"ganzhi":ec.getDay(),"wuxing":ec.getDayWuXing(),"nayin":ec.getDayNaYin(),"xunkong":ec.getDayXunKong(),"hide_gan":ec.getDayHideGan(),"shishen":ec.getDayShiShenGan(),"shishen_zhi":ec.getDayShiShenZhi(),"xun":ec.getDayXun(),"zhi_index":ec.getDayZhiIndex(),"dishi":ec.getDayDiShi()},
            "time":{"gan":ec.getTimeGan(),"zhi":ec.getTimeZhi(),"ganzhi":ec.getTime(),"wuxing":ec.getTimeWuXing(),"nayin":ec.getTimeNaYin(),"xunkong":ec.getTimeXunKong(),"hide_gan":ec.getTimeHideGan(),"shishen":ec.getTimeShiShenGan(),"shishen_zhi":ec.getTimeShiShenZhi(),"xun":ec.getTimeXun(),"dishi":ec.getTimeDiShi()},
        },
        "dayun":dayun_list,"start_year":yun.getStartYear(),"start_month":yun.getStartMonth(),"start_day":yun.getStartDay(),"start_hour":yun.getStartHour(),"start_solar":yun.getStartSolar(),"gender":gender,
        "solar":s.toFullString(),"lunar":l.toFullString(),"shengxiao":l.getYearZhi(),"season":l.getSeason(),
        "jieqi":{k:v for k,v in (l.getJieQiTable() or {}).items()},
        "taiyuan":{"ganzhi":ec.getTaiYuan(),"nayin":ec.getTaiYuanNaYin()},
        "taixi":{"ganzhi":ec.getTaiXi(),"nayin":ec.getTaiXiNaYin()},
        "minggong_nayin":ec.getMingGongNaYin(),"shengong_nayin":ec.getShenGongNaYin(),
    }
    # Lunar 日柱实用数据: 彭祖百忌+吉神凶煞+方位+日禄
    try:
        result["day_extra"] = {
            "pengzu_gan": l.getPengZuGan(), "pengzu_zhi": l.getPengZuZhi(),
            "ji_shen": l.getDayJiShen(), "xiong_sha": l.getDayXiongSha(),
            "cai_shen": l.getDayPositionCaiDesc(), "fu_shen": l.getDayPositionFuDesc(),
            "xi_shen": l.getDayPositionXiDesc(), "yang_gui": l.getDayPositionYangGuiDesc(),
            "yin_gui": l.getDayPositionYinGuiDesc(), "day_lu": l.getDayLu(),
            "day_chong": l.getDayChongDesc(), "day_sha": l.getDaySha(),
            "xiu": l.getXiu(), "xiu_luck": l.getXiuLuck(),
            "nine_star": {"year": l.getYearNineStar(), "month": l.getMonthNineStar(),
                           "day": l.getDayNineStar(), "time": l.getTimeNineStar()},
            "liuyao": l.getLiuYao(), "zhixing": l.getZhiXing(),
            "festivals": l.getFestivals(), "other_festivals": l.getOtherFestivals(),
            "shujiu": l.getShuJiu(), "fu": l.getFu(), "hou": l.getHou(),
            "prev_jieqi": l.getPrevJieQi(), "next_jieqi": l.getNextJieQi(),
            "current_jieqi": l.getCurrentJieQi(), "wuhou": l.getWuHou(),
            "yuexiang": l.getYueXiang(),
            # 日太岁方位(八字神煞)
            "day_position_tai_sui": l.getDayPositionTaiSui(), "day_position_tai_sui_desc": l.getDayPositionTaiSuiDesc(),
            # 日冲干支生肖(八字干支关系)
            "day_chong_gan": l.getDayChongGan(), "day_chong_gan_tie": l.getDayChongGanTie(), "day_chong_shengxiao": l.getDayChongShengXiao(),
        }
    except: pass

    # feature="all"时追加独立模块
    if feature=="all":
        try:
            from bazi_china import shengxiao, luohou
            zhi = l.getYearZhi()
            attrs = shengxiao.zhi_atts.get(zhi, {})
            animals = dict(shengxiao.shengxiaos)
            def _to_animal(v):
                if isinstance(v, tuple): return [animals.get(x,x) for x in v]
                return animals.get(v,v)
            result["shengxiao_pairing"] = {"zhi": zhi, "animal": animals.get(zhi,zhi),
                "relations": {k: _to_animal(v) for k,v in attrs.items()}}
            result["luohou"] = {"yearly_nine_stars": luohou.yearly_nine_stars(year), "monthly_nine_stars": luohou.monthly_nine_stars(l.getYearZhi()), "daily_nine_stars": luohou.daily_nine_stars(l)}
            try: result["luohou"]["jizhu"] = luohou.get_jizhu(l.getYearGan(), l.getYearZhi())
            except: pass
        except: pass
    # bazi_china: 神煞/纳音/调候/干支关系/流月/生肖等(仅APK)
    try:
        sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
        from bazi_china import datas, shengxiao, sizi, luohou, ganzhi, yue
        yg,yz=ec.getYearGan(),ec.getYearZhi(); dg,dz=ec.getDayGan(),ec.getDayZhi()
        tg,tz=ec.getTimeGan(),ec.getTimeZhi(); mg,mz=ec.getMonthGan(),ec.getMonthZhi()
        ygz=ec.getYear(); mgz=ec.getMonth(); dgz=ec.getDay(); tgz=ec.getTime()
        # Helper: 查tuple key字典(天干五合/地支六冲六害等)
        def _tup(d, v):
            for k,val in d.items():
                if isinstance(k,tuple) and v in k: return val
            return ""
        def _strk(d, v):
            for k,val in d.items():
                if isinstance(k,str) and v in k: return val
            return ""
        # 天干→五行 映射(用于学堂/文昌等查表)
        _WX={"甲":"木","乙":"木","丙":"火","丁":"火","戊":"土","己":"土","庚":"金","辛":"金","壬":"水","癸":"水"}
        # 三合: 取包含该地支的组合, 如"寅"在"寅午戌"中
        def _find_3he(d, z):
            for k, v in d.items():
                if isinstance(k, str) and z in k: return f"{k}={v}"
            return ""
        # 刑: 所有包含该地支的tuple
        def _find_xing(d, z):
            out = []
            for k, v in d.items():
                if isinstance(k, tuple) and z in k: out.append(v.strip() if isinstance(v, str) else str(v))
            return "; ".join(out) if out else ""
        # 自坐十神: 日支主气藏干相对日干的十神
        def _self_zuo_deity(dg, dz):
            if not dg or not dz: return ""
            main_gan = next(iter(ganzhi.zhi5.get(dz, {})), "")
            if not main_gan: return ""
            return ganzhi.ten_deities.get(dg, {}).get(main_gan, "")
        result["extra"] = {
            "nayin":{"year":datas.nayins.get((yg,yz),""),"month":datas.nayins.get((mg,mz),""),"day":datas.nayins.get((dg,dz),""),"time":datas.nayins.get((tg,tz),"")},
            "rizhu":datas.rizhus.get(dg+dz,""),
            "minggong":datas.minggongs.get(ec.getMingGong()[-1:],""),
            "shengong":datas.minggongs.get(ec.getShenGong()[-1:],""),
            # 日/干/年/月 神煞
            "day_shen":{k:v.get(dz,"") for k,v in datas.day_shens.items()},
            "g_shen":{k:v.get(dg,"") for k,v in datas.g_shens.items()},
            "year_shen":{k:v.get(yz,"") for k,v in datas.year_shens.items()},
            "month_shen":{k:v.get(mz,"") for k,v in datas.month_shens.items()},
            # 追加神煞: 天乙/文昌/学堂/天印/金神/旺/劫煞/禄库
            "tianyi":{"day":datas.tianyis.get(dg,""),"year":datas.tianyis.get(yg,"")},
            "wenxing":datas.wenxing.get(dg,""),
            "xuetang":datas.xuetangs.get(_WX.get(dg,""),("",""))[0] if hasattr(datas,'xuetangs') else "",
            "jinshen":datas.jins.get(dg,""),
            "wang":datas.wangs.get(dz,""),
            "jiesha":datas.jieshas.get(yz,""),
            "lu_ku_cai":datas.lu_ku_cai.get(dg,""),
            # 神煞详细解释 (孤辰寡宿/大耗/天德/月德等)
            "shensha_detail":{k:v for k,v in datas.shens_infos.items()
                if any(s in k for s in [str(dz),str(mz),str(yz)])} if hasattr(datas,'shens_infos') else {},
            # 金不换/调候用神/建禄/自坐/格/司令/休囚
            "jinbuhuan":datas.jinbuhuan.get(dgz,""),
            "tiaohou":datas.tiaohous.get(dgz,""),
            "jianlu":datas.jianlus.get((dg,dz),"") if hasattr(datas,'jianlus') else "",
            "self_zuo":datas.self_zuo.get(_self_zuo_deity(dg, dz),"") if hasattr(datas,'self_zuo') else "",
            "ge":datas.ges.get(_WX.get(dg,""),{}).get(mz,"") if hasattr(datas,'ges') else "",
            "siling":datas.siling.get(mz,"") if hasattr(datas,'siling') else "",
            "xiuqiu":{z:datas.xiuqius.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(datas,'xiuqius') else {},
            "chen_shi":datas.chens.get(tz,"") if hasattr(datas,'chens') else "",
            # 干支关系 (全量)
            "gan_he":{g:_tup(ganzhi.gan_hes,g) for g in [yg,dg,tg] if g},
            "gan_chong":{g:_tup(ganzhi.gan_chongs,g) for g in [yg,dg,tg] if g} if hasattr(ganzhi,'gan_chongs') else {},
            "zhi_he":{z:_strk(ganzhi.zhi_6hes,z) for z in [yz,mz,dz,tz] if z},
            "zhi_3he":{z:_find_3he(ganzhi.zhi_3hes,z) for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi_3hes') else {},
            "zhi_half_3he":{z:_tup(ganzhi.zhi_half_3hes,z) for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi_half_3hes') else {},
            "zhi_3hui":{z:_strk(ganzhi.zhi_huis,z) for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi_huis') else {},
            "zhi_chong":{z:_tup(ganzhi.zhi_chongs,z) for z in [yz,mz,dz,tz] if z},
            "zhi_xing":{z:_find_xing(ganzhi.zhi_xings,z) for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi_xings') else {},
            "zhi_po":{z:_tup(ganzhi.zhi_poes,z) for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi_poes') else {},
            "zhi_hai":{z:_tup(ganzhi.zhi_haies,z) for z in [yz,mz,dz,tz] if z},
            # 藏干/十神/干支健康
            "zhi_zang":{z:ganzhi.zhi_zangs.get(z,"") for z in [yz,mz,dz,tz] if z},
            "ten_deities":{g:ganzhi.ten_deities.get(dg,{}).get(g,"") for g in [yg,mg,tg] if g},
            "gan_health":ganzhi.gan_health.get(_WX.get(dg,""),"") if hasattr(ganzhi,'gan_health') else "",
            # 流月
            "yue_month":yue.months.get(mgz,"") if mgz else "",
            # 生肖
            "shengxiao":{z:datas.shengxiaos.get(z,"") for z in [yz,dz,tz] if z},
            # 空亡 (tuple key)
            "kongwang":datas.empties.get((dg,dz),""),
            # 日柱古诀(60日柱赋文)
            "days60": datas.days60.get(dgz,"") if hasattr(datas,'days60') else "",
            # 禄类型(日干在不同柱的禄格细节)
            "lu_types": {} if not hasattr(datas,'lu_types') else
                {g: {str(k):v for k,v in datas.lu_types.get(g,{}).items()
                     if any(z in str(k) for z in [yz,mz,dz,tz])}
                 for g in [yg,dg,tg] if g in datas.lu_types},
            # 马星变种(精确(gan,zhi)匹配)
            "ma_zhu": {str(gz): datas.ma_zhus.get(gz,"") for gz in [(yg,yz),(dg,dz),(tg,tz)]
                      if gz in datas.ma_zhus} if hasattr(datas,'ma_zhus') else {},
            # 伤官/天元(日主五行)
            "shang_guan": datas.shang_guans.get(_WX.get(dg,""),"") if hasattr(datas,'shang_guans') else "",
            "tianyuan": datas.tianyuans.get(_WX.get(dg,""),"") if hasattr(datas,'tianyuans') else "",
            # 空亡五行
            "kongwang_wx": datas.emptie4s.get((dg,dz),"") if hasattr(datas,'emptie4s') else "",
            # 玉堂贵人/天印
            "yutang": datas.yutangs.get(dg,"") if hasattr(datas,'yutangs') else "",
            "tianyin": datas.tianyin.get(dg,"") if hasattr(datas,'tianyin') else "",
            # 建除十二神(l.getZhiXing()返回名称)
            "jianchu": next((list(v) for v in datas.jianchus.values() if v[0]==l.getZhiXing()), ["",""]) if hasattr(datas,'jianchus') else [],
            # 干支意象(诗歌/比喻)
            "gan_desc": {g: ganzhi.gan_desc.get(g,"") for g in [yg,dg,tg] if g} if hasattr(ganzhi,'gan_desc') else {},
            "zhi_desc": {z: ganzhi.zhi_desc.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi_desc') else {},
            # 温度(寒暖燥湿量化)
            "temp_gan": {g: ganzhi.temps.get(g,"") for g in [yg,dg,tg] if g} if hasattr(ganzhi,'temps') else {},
            "temp_zhi": {z: ganzhi.temps.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'temps') else {},
            # 双向冲/刑(str→str查法)
            "chongs": {z: ganzhi.chongs.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'chongs') else {},
            "xings": {z: ganzhi.xings.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'xings') else {},
            # 自刑(辰午酉亥)
            "zhi_zixing": [z for z in [yz,mz,dz,tz] if z and z in ganzhi.zhi_zixings] if hasattr(ganzhi,'zhi_zixings') else [],
            # 拱合/拱会(地支两两配对)
            "gong_he": {pair: ganzhi.gong_he.get(pair,"") for pair in [yz+mz, yz+dz, yz+tz, mz+dz, mz+tz, dz+tz]
                      if hasattr(ganzhi,'gong_he') and pair in ganzhi.gong_he},
            "gong_hui": {pair: ganzhi.gong_hui.get(pair,"") for pair in [yz+mz, yz+dz, yz+tz, mz+dz, mz+tz, dz+tz]
                       if hasattr(ganzhi,'gong_hui') and pair in ganzhi.gong_hui},
            # 库(四墓五行)
            "kus": {z: ganzhi.kus.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'kus') else {},
            # 干支五行映射
            "gan_wuxing": {g: ganzhi.gan5.get(g,"") for g in [yg,dg,tg] if g} if hasattr(ganzhi,'gan5') else {},
            "zhi_wuxing": {z: ganzhi.zhi_wuhangs.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi_wuhangs') else {},
            # 五行生克关系(日主→四柱天干)
            "wuxing_relation": {g: ganzhi.relations.get((_WX.get(dg,""), _WX.get(g,"")), "")
                              for g in [yg,mg,tg,dg] if g and _WX.get(g)} if hasattr(ganzhi,'relations') else {},
            # 官星(日主)
            "guan": list(ganzhi.guans.get(dg, ())) if hasattr(ganzhi,'guans') else [],
            # 天干脏腑
            "gan_zang": {g: ganzhi.gan_zangs.get(g,"") for g in [yg,dg,tg] if g} if hasattr(ganzhi,'gan_zangs') else {},
            # 特殊干支
            "datouxiu": [g for g in [ygz,mgz,dgz,tgz] if g in ganzhi.datouxiu] if hasattr(ganzhi,'datouxiu') else [],
            "xiaotouxiu": [g for g in [ygz,mgz,dgz,tgz] if g in ganzhi.xiaotouxiu] if hasattr(ganzhi,'xiaotouxiu') else [],
            # 短句诗词(gan3/gan4/zhi3)
            "gan3": {g: ganzhi.gan3.get(g,"") for g in [yg,dg,tg] if g} if hasattr(ganzhi,'gan3') else {},
            "gan4": {g: ganzhi.gan4.get(g,"") for g in [yg,dg,tg] if g} if hasattr(ganzhi,'gan4') else {},
            "zhi3": {z: ganzhi.zhi3.get(z,"") for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi3') else {},
            # 上下合(戊子/辛巳/壬午/丁亥: 日柱精确匹配)
            "up_down_he": any((dg,dz)==p for p in datas.up_down_hes) if hasattr(datas,'up_down_hes') else False,
            # 旺相休囚死(日主五行)
            "xiuqiu_dz": datas.xiuqius.get(dz,{}).get(_WX.get(dg,""),"") if hasattr(datas,'xiuqius') else "",
            # 藏干(含权重天数)
            "zhi_canggan": {z: dict(ganzhi.zhi5.get(z,{})) for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi5') else {},
            "zhi_canggan_list": {z: ganzhi.zhi5_list.get(z,[]) for z in [yz,mz,dz,tz] if z} if hasattr(ganzhi,'zhi5_list') else {},
        }
        result["engine"] += " + bazi_china"
        result["_hint"] = "lunar_python+bazi_china全量:四柱(含十神干支+旬+地煞)+大运(含起运详情)+胎元/胎息/命宫/身宫纳音+日柱吉凶神+彭祖百忌+财福喜阳阴贵神方位+日禄+二十八宿+日冲。bazi_china:金不换/调候/建禄/自坐/干支关系(五合/三合三会/六合/冲/刑/破/害)+十神/藏干/空亡/流月/生肖/格/司令/休囚/时辰诗。" \
            "神煞:天乙/文昌/学堂/天印/金神/旺/劫煞/禄库/孤辰寡宿大耗天德月德。" \
            "扩展:60日柱古诀/禄类型/马星/伤官天元/空亡五行/玉堂贵人/建除/星宿/干支意象/温度/自刑/拱合拱会/库/五行生克/官星/脏腑/短句诗词/上下合/藏干权重"
    except Exception:
        result["_hint"] = "lunar_python已返回排盘+大运。bazi_china不可用(仅APK内)"
    return result