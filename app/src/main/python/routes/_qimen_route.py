"""Route:  qimen"""
import json, sys, os
from ._shared import _js, _js_load

# ===== 奇门三式 =====
def _qimen(year,month,day,hour=None,feature="all"):
    result={"system":"qimen","engine":""}
    # Qimen Dunjia (日家+时家)
    if feature in ("qimen","all"):
        _js_load("qimen-engine")
        result["qimen"]=_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'rijia',year:{year},month:{month},day:{day}}}))")
        result["engine"]="QimenEngine"
        if hour is not None:
            result["qimen_hourly"]=_js("qimen-engine",f"JSON.stringify(QimenEngine.generate({{type:'shijia',year:{year},month:{month},day:{day},hour:{hour}}}))")
            result["engine"]+="+shijia"
        # 解读层: 格局+星门神详解+运筹
        result["qimen_analysis"]=_js("qimen-engine",
            "JSON.stringify({"
            "jiGe:QimenEngine.getAllJiGe(),xiongGe:QimenEngine.getAllXiongGe(),"
            "starDetail:['天蓬','天芮','天冲','天辅','天禽','天心','天柱','天任','天英'].map(function(s){return QimenEngine.getStarDetail(s)}),"
            "doorDetail:['休','生','伤','杜','景','死','惊','开'].map(function(d){return QimenEngine.getDoorDetail(d)}),"
            "godDetail:['值符','螣蛇','太阴','六合','白虎','玄武','九地','九天'].map(function(g){return QimenEngine.getGodDetail(g)}),"
            "palaceDetail:[1,2,3,4,5,6,7,8,9].map(function(p){return QimenEngine.getPalaceDetail(p)}),"
            "specialPatterns:QimenEngine.detectSpecialPatterns?QimenEngine.detectSpecialPatterns():null"
            "})")
    # 大六壬
    if feature in ("liuren","all"):
        _js_load("liuren-engine")
        result["liuren"]=_js("liuren-engine",f"JSON.stringify(LiuRen.getLiuRenByDate(new Date({year},{month-1},{day},12,0)))")
        result["engine"]+="+LiuRen"
    # 小六壬(完整掌诀推算)
    if feature in ("xiaoliuren","all"):
        from lunar_python import Lunar
        lunar=Lunar.fromYmd(year,month,day)
        mn=lunar.getMonth(); dn=lunar.getDay(); h=hour or 12
        zhangjue=["大安","留连","速喜","赤口","小吉","空亡"]
        meanings={
            "大安":"身不动时,五行属木,颜色青色,方位东方。谋事主一、五、七。有静止、心安、吉祥之含义。",
            "留连":"卒未归时,五行属水,颜色黑色,方位北方。谋事主二、八、十。有暗昧、纠缠、拖延、漫长之含义。",
            "速喜":"人即至时,五行属火,颜色红色,方位南方。谋事主三、六、九。有快速、喜庆、吉利之含义。",
            "赤口":"官事凶时,五行属金,颜色白色,方位西方。谋事主四、七、十。有不吉、惊恐、凶险、口舌是非之含义。",
            "小吉":"人来喜时,五行属木,颜色绿色,方位东方。谋事主一、五、七。有和合、吉利、贵人、顺利之含义。",
            "空亡":"音信稀时,五行属土,颜色黄色,方位中央。谋事主三、六、九。有谋事落空、劳而无成之含义。",
        }
        idx=((mn-1)+(dn-1)+(h-1))%6
        name=zhangjue[idx]
        result["xiaoliuren"]={
            "lunar_month":mn,"lunar_day":dn,"hour":h,
            "zhangjue":name,"index":idx,
            "meaning":meanings.get(name,""),
        }
        result["engine"]+="+小六壬"
    result["_hint"]=("QimenEngine日家/时家已返回。LiuRen一键排盘含课体+三传+神将+22原子函数。小六壬完整掌诀。"
        "QimenEngine另有阴遁/阳遁/置闰/拆补4流派。自探索:Object.keys(QimenEngine)/Object.keys(LiuRen)")
    return result