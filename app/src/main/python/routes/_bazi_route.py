"""Route:  bazi"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 八字 =====
def _bazi(year, month, day, hour, gender=1, feature="bazi"):
    # 兼容Kotlin传来的"male"/"female"字符串
    if isinstance(gender, str):
        gender = 1 if gender.lower() in ("male", "男", "m") else 0
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
            "year":{"gan":ec.getYearGan(),"zhi":ec.getYearZhi(),"ganzhi":ec.getYear(),"wuxing":ec.getYearWuXing(),"nayin":ec.getYearNaYin(),"xunkong":ec.getYearXunKong(),"hide_gan":ec.getYearHideGan(),"shishen":ec.getYearShiShenGan(),"shishen_zhi":ec.getYearShiShenZhi(),"xun":ec.getYearXun(),"dishi":ec.getYearDiShi()},
            "month":{"gan":ec.getMonthGan(),"zhi":ec.getMonthZhi(),"ganzhi":ec.getMonth(),"wuxing":ec.getMonthWuXing(),"nayin":ec.getMonthNaYin(),"xunkong":ec.getMonthXunKong(),"hide_gan":ec.getMonthHideGan(),"shishen":ec.getMonthShiShenGan(),"shishen_zhi":ec.getMonthShiShenZhi(),"xun":ec.getMonthXun()},
            "day":{"gan":ec.getDayGan(),"zhi":ec.getDayZhi(),"ganzhi":ec.getDay(),"wuxing":ec.getDayWuXing(),"nayin":ec.getDayNaYin(),"xunkong":ec.getDayXunKong(),"hide_gan":ec.getDayHideGan(),"shishen":ec.getDayShiShenGan(),"shishen_zhi":ec.getDayShiShenZhi(),"xun":ec.getDayXun(),"zhi_index":ec.getDayZhiIndex(),"dishi":ec.getDayDiShi()},
            "time":{"gan":ec.getTimeGan(),"zhi":ec.getTimeZhi(),"ganzhi":ec.getTime(),"wuxing":ec.getTimeWuXing(),"nayin":ec.getTimeNaYin(),"xunkong":ec.getTimeXunKong(),"hide_gan":ec.getTimeHideGan(),"shishen":ec.getTimeShiShenGan(),"shishen_zhi":ec.getTimeShiShenZhi(),"xun":ec.getTimeXun(),"dishi":ec.getTimeDiShi()},
        },
        "dayun":dayun_list,"start_age":yun.getStartYear(),"start_year":yun.getStartYear(),"start_month":yun.getStartMonth(),"start_day":yun.getStartDay(),"start_hour":yun.getStartHour(),"start_solar":str(yun.getStartSolar()),"gender":gender,
        "solar":s.toFullString(),"lunar":l.toFullString(),"shengxiao":l.getYearShengXiao(),"season":l.getSeason(),
        "jieqi":{k:str(v) for k,v in (l.getJieQiTable() or {}).items()},
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
            "prev_jieqi": str(l.getPrevJieQi()), "next_jieqi": str(l.getNextJieQi()),
            "current_jieqi": str(l.getCurrentJieQi()), "wuhou": l.getWuHou(),
            "yuexiang": l.getYueXiang(),
        }
    except: pass
    # 独立功能: 生肖配对 (无需八字排盘)
    if feature in ("shengxiao","all"):
        try:
            import sys; sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
            from bazi_china import shengxiao
            result["shengxiao_pairing"] = {z: shengxiao.output("", z, k)
                for z in [l.getYearShengXiao()] for k in ["合","冲","害","三合"]
                if hasattr(shengxiao, "output")}
        except: pass
    # 独立功能: 择日九宫飞星 (无需八字排盘)
    if feature in ("luohou","all"):
        try:
            import sys; sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__),'..')))
            from bazi_china import luohou
            result["luohou"] = {
                "yearly_nine_stars": str(luohou.yearly_nine_stars(year)),
                "monthly_nine_stars": str(luohou.monthly_nine_stars(l.getYearZhi())),
                "daily_nine_stars": str(luohou.daily_nine_stars(l)),
            }
            try: result["luohou"]["jizhu"] = str(luohou.get_jizhu(l.getYearGan(), l.getYearZhi()))
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
        result["extra"] = {
            "nayin":{"year":datas.nayins.get((yg,yz),""),"month":datas.nayins.get((mg,mz),""),"day":datas.nayins.get((dg,dz),""),"time":datas.nayins.get((tg,tz),"")},
            "rizhu":datas.rizhus.get(dg+dz,""),
            "minggong":datas.minggongs.get(ec.getMingGong()[-1:],""),
            "shengong":datas.minggongs.get(ec.getShenGong()[-1:],""),
            "day_shen":{k:v.get(dz,"") for k,v in datas.day_shens.items()},
            "g_shen":{k:v.get(dg,"") for k,v in datas.g_shens.items()},
            "year_shen":{k:v.get(yz,"") for k,v in datas.year_shens.items()},
            "month_shen":{k:v.get(mz,"") for k,v in datas.month_shens.items()},
            "sizi":{k: v for k,v in list(sizi.summarys.items())[:5]},
            "ganzhi_gan":ganzhi.Gan[:10], "ganzhi_zhi":ganzhi.Zhi[:12],
            # 金不换/调候用神/建禄/自坐
            "jinbuhuan":datas.jinbuhuan.get(dgz,""),
            "tiaohou":datas.tiaohous.get(dgz,""),
            "jianlu":datas.jianlus.get(ygz,""),
            "self_zuo":datas.self_zuo.get(dz,""),
            # 干支关系 (tuple/string key兼容)
            "gan_he":{g:_tup(ganzhi.gan_hes,g) for g in [yg,dg,tg] if g},
            "zhi_he":{z:_strk(ganzhi.zhi_6hes,z) for z in [yz,mz,dz,tz] if z},
            "zhi_chong":{z:_tup(ganzhi.zhi_chongs,z) for z in [yz,mz,dz,tz] if z},
            "zhi_hai":{z:_tup(ganzhi.zhi_haies,z) for z in [yz,mz,dz,tz] if z},
            # 藏干/十神
            "zhi_zang":{z:ganzhi.zhi_zangs.get(z,"") for z in [yz,mz,dz,tz] if z},
            "ten_deities":{g:ganzhi.ten_deities.get(dg,{}).get(g,"") for g in [yg,mg,tg] if g},
            # 流月
            "yue_month":yue.months.get(mgz,"") if mgz else "",
            # 生肖
            "shengxiao":{z:datas.shengxiaos.get(z,"") for z in [yz,dz,tz] if z},
            # 空亡 (tuple key)
            "kongwang":datas.empties.get((dg,dz),""),
        }
        result["engine"] += " + bazi_china"
        result["_hint"] = "lunar_python+bazi_china全量:四柱(含十神干支+旬+地煞)+大运(含起运详情)+胎元/胎息/命宫/身宫纳音+日柱吉凶神+彭祖百忌+财福喜阳阴贵神方位+日禄+二十八宿+日冲。bazi_china:金不换/调候/建禄/自坐/干支关系/藏干/十神/流月/生肖/空亡。" \
            "另有:luohou九宫飞星/shengxiao生肖配对。自探索:dir(datas)/dir(ganzhi)/dir(yue)"
    except Exception:
        result["_hint"] = "lunar_python已返回排盘+大运。bazi_china不可用(仅APK内)"
    return result