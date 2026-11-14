"""couponfmt —— 通用兑换码格式分析与离线测试数据生成工具。

模块结构：
    charset    字符集归类与正则字符类推导
    checksum   校验位算法与自动识别
    analyzer   格式归纳引擎
    generator  测试码生成
    validator  格式校验
    reporter   统计报告
    exporter   TXT/CSV 导出
    storage    规则持久化
    cli        命令行交互界面
"""

__version__ = "1.0.0"
__all__ = [
    "charset",
    "checksum",
    "analyzer",
    "generator",
    "validator",
    "reporter",
    "exporter",
    "storage",
]
