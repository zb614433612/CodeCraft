"""
ETL: 获取实时行情快照，UPSERT 到 stock_realtime
数据源：腾讯财经 qt.gtimg.cn （延迟低，字段全）
"""
import requests
import pymysql
import time
import datetime

DB_CONFIG = {
    'host': 'localhost', 'port': 3306,
    'user': 'root', 'password': 'Zb960321',
    'database': 'agent_deepseek', 'charset': 'utf8mb4'
}

TENCENT_URL = 'http://qt.gtimg.cn/q='

def get_tencent_codes(cursor):
    """获取所有股票代码，转为腾讯格式"""
    cursor.execute("SELECT symbol, market FROM stock_info ORDER BY symbol")
    codes = []
    mapping = []
    for symbol, market in cursor.fetchall():
        prefix = 'sh' if market == 'SH' else 'sz'
        codes.append(f"{prefix}{symbol}")
        mapping.append((symbol, market))
    return codes, mapping

def fetch_realtime(codes):
    """批量调腾讯接口获取实时行情"""
    # 腾讯接口一次最多支持约100只股票，分批请求
    all_data = {}
    session = requests.Session()
    session.trust_env = False
    headers = {'User-Agent': 'Mozilla/5.0'}

    batch_size = 80
    for i in range(0, len(codes), batch_size):
        batch = codes[i:i+batch_size]
        url = TENCENT_URL + ','.join(batch)
        try:
            r = session.get(url, headers=headers, timeout=10)
            r.encoding = 'gbk'
            for line in r.text.strip().split('\n'):
                if not line.strip():
                    continue
                try:
                    # Format: v_code="fields~...";
                    code = line.split('=')[0].strip().split('_')[-1]
                    data_str = line.split('"')[1] if '"' in line else ''
                    fields = data_str.split('~')
                    all_data[code] = fields
                except:
                    continue
        except Exception as e:
            print(f"  Batch error ({i}-{i+batch_size}): {e}")
        time.sleep(1)

    return all_data

def parse_realtime(fields, symbol, market):
    """解析腾讯实时行情（全部88字段）"""
    if len(fields) < 48:
        return None
    try:
        update_time = None
        if fields[30]:
            t = fields[30].strip()
            if len(t) >= 14:
                update_time = f"{t[:4]}-{t[4:6]}-{t[6:8]} {t[8:10]}:{t[10:12]}:{t[12:14]}"
            elif len(t) >= 8:
                update_time = f"{datetime.date.today()} {t[:2]}:{t[2:4]}:{t[4:6]}"

        def fval(i, t=float):
            return t(fields[i]) if i < len(fields) and fields[i] else None

        return {
            'ts_code': f"{symbol}.{market}",
            'name': fields[1] if len(fields) > 1 else '',
            'price': fval(3) or 0,
            'open': fval(5) or 0,
            'high': fval(33) or 0,
            'low': fval(34) or 0,
            'pre_close': fval(4) or 0,
            'change_': fval(31) or 0,
            'pct_change': fval(32) or 0,
            'volume': int(fval(6, int) * 100) if fval(6) else 0,
            'amount': fval(37) * 10000 if fval(37) else 0,
            'bid_prices': [fval(i) or 0 for i in [9, 11, 13, 15, 17]],
            'bid_volumes': [int(fval(i, int) or 0) for i in [10, 12, 14, 16, 18]],
            'ask_prices': [fval(i) or 0 for i in [19, 21, 23, 25, 27]],
            'ask_volumes': [int(fval(i, int) or 0) for i in [20, 22, 24, 26, 28]],
            'turnover_rate': fval(38),
            'pe': fval(39),
            'pb': fval(46),
            'total_mv': fval(45) * 10000 if fval(45) else None,
            'circ_mv': fval(44) * 10000 if fval(44) else None,
            'limit_up': fval(47),
            'limit_down': fval(48),
            'buy_vol': int(fval(7, int) * 100) if fval(7) else None,
            'sell_vol': int(fval(8, int) * 100) if fval(8) else None,
            'amplitude': fval(43),
            'order_diff': int(fval(50, int)) if fval(50) is not None else None,
            'avg_price': fval(51),
            'volume_ratio': fval(52),
            'pe_ttm': fval(53),
            'eps': fval(62),
            'bvps': fval(65),
            'capital_reserve': fval(66),
            'net_profit_growth': fval(63),
            'revenue_growth': fval(64),
            'total_shares': int(fval(72, int)) if fval(72) else None,
            'circ_shares': int(fval(73, int)) if fval(73) else None,
            'pcf': fval(71),
            'raw_data': fields[:],
            'update_time': update_time or datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        }
    except (ValueError, IndexError) as e:
        return None

def upsert(cursor, conn, records):
    sql = """INSERT INTO stock_realtime
             (ts_code, name, price, open, high, low, pre_close, change_, pct_change,
              volume, amount, bid_prices, bid_volumes, ask_prices, ask_volumes,
              turnover_rate, pe, pb, total_mv, circ_mv, limit_up, limit_down,
              buy_vol, sell_vol, amplitude, order_diff, avg_price, volume_ratio,
              pe_ttm, eps, bvps, capital_reserve, net_profit_growth, revenue_growth,
              total_shares, circ_shares, pcf, raw_data, update_time)
             VALUES (%(ts_code)s, %(name)s, %(price)s, %(open)s, %(high)s, %(low)s,
                     %(pre_close)s, %(change_)s, %(pct_change)s, %(volume)s, %(amount)s,
                     %(bid_prices)s, %(bid_volumes)s, %(ask_prices)s, %(ask_volumes)s,
                     %(turnover_rate)s, %(pe)s, %(pb)s, %(total_mv)s, %(circ_mv)s,
                     %(limit_up)s, %(limit_down)s,
                     %(buy_vol)s, %(sell_vol)s, %(amplitude)s, %(order_diff)s,
                     %(avg_price)s, %(volume_ratio)s, %(pe_ttm)s, %(eps)s, %(bvps)s,
                     %(capital_reserve)s, %(net_profit_growth)s, %(revenue_growth)s,
                     %(total_shares)s, %(circ_shares)s, %(pcf)s, %(raw_data)s,
                     %(update_time)s)
             AS new
             ON DUPLICATE KEY UPDATE
             name=new.name, price=new.price, open=new.open, high=new.high,
             low=new.low, pre_close=new.pre_close, change_=new.change_,
             pct_change=new.pct_change, volume=new.volume, amount=new.amount,
             bid_prices=new.bid_prices, bid_volumes=new.bid_volumes,
             ask_prices=new.ask_prices, ask_volumes=new.ask_volumes,
             turnover_rate=new.turnover_rate, pe=new.pe, pb=new.pb,
             total_mv=new.total_mv, circ_mv=new.circ_mv,
             limit_up=new.limit_up, limit_down=new.limit_down,
             buy_vol=new.buy_vol, sell_vol=new.sell_vol,
             amplitude=new.amplitude, order_diff=new.order_diff,
             avg_price=new.avg_price, volume_ratio=new.volume_ratio,
             pe_ttm=new.pe_ttm, eps=new.eps, bvps=new.bvps,
             capital_reserve=new.capital_reserve,
             net_profit_growth=new.net_profit_growth,
             revenue_growth=new.revenue_growth,
             total_shares=new.total_shares, circ_shares=new.circ_shares,
             pcf=new.pcf, raw_data=new.raw_data,
             update_time=new.update_time"""
    count = 0
    for rec in records:
        import json
        for json_field in ['bid_prices', 'bid_volumes', 'ask_prices', 'ask_volumes', 'raw_data']:
            if isinstance(rec.get(json_field), list):
                rec[json_field] = json.dumps(rec[json_field])
        try:
            cursor.execute(sql, rec)
            count += 1
        except Exception as e:
            print(f"    Skip {rec.get('ts_code', '?')}: {e}")
    conn.commit()
    return count

def main():
    print("=== ETL: Real-time Snapshots ===")
    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()

    codes, mapping = get_tencent_codes(cursor)
    print(f"  Fetching {len(codes)} stocks from Tencent...")

    all_data = fetch_realtime(codes)
    print(f"  Received data for {len(all_data)} stocks")

    records = []
    for (symbol, market) in mapping:
        prefix = 'sh' if market == 'SH' else 'sz'
        tencent_code = f"{prefix}{symbol}"
        fields = all_data.get(tencent_code)
        if fields:
            rec = parse_realtime(fields, symbol, market)
            if rec:
                records.append(rec)

    print(f"  Parsed {len(records)} valid records")

    if records:
        count = upsert(cursor, conn, records)
        print(f"  Upserted {count} records")

    cursor.close()
    conn.close()
    print("  Done")

if __name__ == '__main__':
    main()
