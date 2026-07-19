"""mingli_router — 命理统一入口"""
import json, sys, os
from routes._shared import _js, _js_load

# Import all route functions
from routes._tarot_route import _tarot
from routes._lenormand_route import _lenormand
from routes._bazi_route import _bazi
from routes._ziwei_route import _ziwei
from routes._western_astro_route import _western_astro
from routes._traditional_astro_route import _traditional_astro
from routes._vedic_route import _vedic
from routes._human_design_route import _human_design
from routes._kabbalah_route import _kabbalah
from routes._qimen_route import _qimen
from routes._yijing_route import _yijing

# ===== 路由表 =====
_ROUTER={
    "塔罗":_tarot,"tarot":_tarot,"韦特":_tarot,"塔罗牌":_tarot,
    "雷诺曼":_lenormand,"lenormand":_lenormand,
    "八字":_bazi,"bazi":_bazi,"四柱":_bazi,"生辰八字":_bazi,"排盘":_bazi,
    "紫微":_ziwei,"ziwei":_ziwei,"紫微斗数":_ziwei,"紫薇":_ziwei,
    "现代西洋占星":_western_astro,"现代占星":_western_astro,"西洋占星":_western_astro,"western_astro":_western_astro,"星座":_western_astro,
    "传统西洋占星":_traditional_astro,"traditional_astro":_traditional_astro,"古典占星":_traditional_astro,"中世纪占星":_traditional_astro,
    "吠陀":_vedic,"vedic":_vedic,"印度占星":_vedic,"jyotish":_vedic,"吠陀占星":_vedic,
    "人类图":_human_design,"human_design":_human_design,"humandesign":_human_design,
    "灵数卡巴拉":_kabbalah,"kabbalah":_kabbalah,"生命灵数":_kabbalah,"卡巴拉":_kabbalah,"生命数字":_kabbalah,
    "奇门":_qimen,"qimen":_qimen,"奇门遁甲":_qimen,"奇门三式":_qimen,
    "六爻梅花":_yijing,"yijing":_yijing,"六爻":_yijing,"梅花易数":_yijing,"易经":_yijing,"周易":_yijing,
}

def mingli_run(system,params=None,bridge=None):
    import routes._shared as _shared_mod
    if bridge is not None: _shared_mod._bridge = bridge
    if isinstance(params,str):
        try: params=json.loads(params)
        except: params={}
    if not isinstance(params,dict): params={}
    func=_ROUTER.get(system)
    if not func: return json.dumps({"error":f"\u672a\u77e5\u7cfb\u7edf:{system}","available":list_systems()},ensure_ascii=False)
    try:
        result=func(**params)
        return json.dumps(result,ensure_ascii=False,default=str)
    except Exception as e:
        import traceback
        return json.dumps({"error":str(e),"traceback":traceback.format_exc(),"system":system},ensure_ascii=False)

def list_systems():
    return sorted(set(k for k in _ROUTER if not k.isascii()))
