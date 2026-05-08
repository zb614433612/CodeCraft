"""
ETL: 获取1分钟K线数据，写入 stock_kline_min（freq=1）
主数据源：腾讯财经 minute/query（20 线程并发）
降级数据源：东方财富 push2his（klt=1）
"""
import requests
import pymysql
import time
import datetime
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

DB_CONFIG = {
    'host': 'localhost', 'port': 3306,
    'user': 'root', 'password': 'Zb960321',
    'database': 'agent_deepseek', 'charset': 'utf8mb4'
}

EM_KLINE_URL = 'http://push2his.eastmoney.com/api/qt/stock/kline/get'
EM_HEADERS = {'User-Agent': 'Mozilla/5.0', 'Referer': 'https://quote.eastmoney.com/'}
TENCENT_URL = 'http://ifzq.gtimg.cn/appstock/app/minute/query'
TENCENT_HEADERS = {'User-Agent': 'Mozilla/5.0'}

# 默认今天，可通过 --date YYYY-MM-DD 覆盖
TRADE_DATE = datetime.date.today().strftime('%Y-%m-%d')

# 全局Session（禁用代理避免干扰）
_SESSION = requests.Session()
_SESSION.trust_env = False
# 全局探测：东方财富是否可用（快速检测，失败则全走腾讯）
_EM_ALIVE = None
# 东方财富限流：全局 1 请求/秒
_EM_RATE_LOCK = threading.Lock()
_EM_LAST_REQ = 0.0


def get_stocks(cursor):
    cursor.execute("SELECT ts_code, symbol, market FROM stock_info ORDER BY symbol")
    return cursor.fetchall()


def get_secid(market, symbol):
    return f"{'1' if symbol.startswith(('6','9')) else '0'}.{symbol}"


def get_tencent_code(market, symbol):
    prefix = 'sh' if market == 'SH' else 'sz'
    return f"{prefix}{symbol}"


# ====================== 数据源1：东方财富 ======================

def probe_em():
    """快速探测东方财富是否可用（全局只测一次）"""
    global _EM_ALIVE
    if _EM_ALIVE is not None:
        return _EM_ALIVE
    try:
        r = _SESSION.get(EM_KLINE_URL, params={
            'secid': '1.600000', 'fields1': 'f1,f2',
            'fields2': 'f51,f52', 'klt': '1', 'fqt': '1',
            'beg': '0', 'end': '20500000',
            'ut': '7eea3edcaed734bea9cbfc24409ed989'
        }, headers=EM_HEADERS, timeout=10)
        _EM_ALIVE = r.status_code == 200 and r.text.strip() != ''
    except Exception:
        _EM_ALIVE = False
    return _EM_ALIVE


# ====================== 数据源1：东方财富 ======================

def fetch_em_1min(market, symbol):
    """尝试东方财富接口（全局限流 1 请求/秒）"""
    if not probe_em():
        return None
    secid = get_secid(market, symbol)
    params = {
        'secid': secid,
        'fields1': 'f1,f2,f3,f4,f5,f6',
        'fields2': 'f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61',
        'klt': '1', 'fqt': '1',
        'beg': '0', 'end': '20500000',
        'ut': '7eea3edcaed734bea9cbfc24409ed989'
    }
    global _EM_LAST_REQ
    with _EM_RATE_LOCK:
        now = time.time()
        gap = now - _EM_LAST_REQ
        if gap < 1.0:
            time.sleep(1.0 - gap)
        _EM_LAST_REQ = time.time()
    try:
        r = _SESSION.get(EM_KLINE_URL, params=params, headers=EM_HEADERS, timeout=5)
        data = r.json()
        if data.get('rc') == 0 and data.get('data') and data['data'].get('klines'):
            return data['data']['klines']
        return None
    except Exception:
        return None


def parse_em_kline(line, ts_code):
    """解析东方财富分钟K线"""
    parts = line.split(',')
    if len(parts) < 11:
        return None
    dt = parts[0].strip()
    if ' ' not in dt:
        return None
    date_str, time_str = dt.split(' ')
    if len(time_str) == 5:
        time_str += ':00'
    try:
        return {
            'ts_code': ts_code,
            'trade_date': date_str,
            'minute': time_str,
            'freq': 1,
            'open': float(parts[1]),
            'high': float(parts[3]),
            'low': float(parts[4]),
            'close': float(parts[2]),
            'vol': int(float(parts[5])),
            'amount': float(parts[6]) if parts[6] else 0,
        }
    except (ValueError, TypeError, IndexError):
        return None


# ====================== 数据源2：腾讯财经（降级） ======================

def fetch_tencent_minute(market, symbol):
    """腾讯分钟接口（降级），返回 per-minute 数据"""
    tcode = get_tencent_code(market, symbol)
    try:
        r = _SESSION.get(f"{TENCENT_URL}?code={tcode}", headers=TENCENT_HEADERS, timeout=10)
        data = r.json()
        if data.get('code') != 0:
            return None
        # 路径: data.sh600000.data.data
        path = data.get('data', {}).get(tcode, {}).get('data', {}).get('data')
        if not path or not isinstance(path, list):
            return None
        return path
    except Exception:
        return None


def parse_tencent_minute(raw_lines, ts_code, trade_date=None):
    """
    解析腾讯分钟数据 -> 近似K线
    输入: ["0930 9.27 4747 4400468.82", "0931 9.26 17163 15915880.00", ...]
    注意：腾讯返回的成交量/成交额是累积值，需减前一分钟得到当前分钟实际值
    输出: OHLC 记录（open/high/low 基于相邻价格近似）
    """
    if trade_date is None:
        trade_date = TRADE_DATE
    records = []
    prev_price = None
    prev_vol = 0
    prev_amount = 0.0

    for line in raw_lines:
        parts = line.strip().split()
        if len(parts) < 4:
            continue

        time_str = parts[0]
        if len(time_str) == 4:
            time_str = f"{time_str[:2]}:{time_str[2:]}:00"

        try:
            price = float(parts[1])
            cum_vol = int(parts[2])
            cum_amount = float(parts[3])

            # 减前一分钟累积值得当前分钟实际值
            vol = cum_vol - prev_vol
            amount = cum_amount - prev_amount
            prev_vol = cum_vol
            prev_amount = cum_amount

            # 近似 OHLC：只有当前价格，用相邻价格推open/high/low
            close = price
            open_ = prev_price if prev_price is not None else price
            high_ = max(price, prev_price) if prev_price is not None else price
            low_ = min(price, prev_price) if prev_price is not None else price
            prev_price = price

            records.append({
                'ts_code': ts_code,
                'trade_date': trade_date,
                'minute': time_str,
                'freq': 1,
                'open': open_,
                'high': high_,
                'low': low_,
                'close': close,
                'vol': vol,
                'amount': amount,
            })
        except (ValueError, TypeError):
            continue

    return records


# ====================== 统一入口 ======================

def fetch_1min_fallback(market, symbol):
    """先走腾讯（主数据源），失败降级到东方财富"""
    tk = fetch_tencent_minute(market, symbol)
    if tk:
        return 'tencent', tk

    klines = fetch_em_1min(market, symbol)
    if klines:
        return 'em', klines

    return None, None


def insert_batch(cursor, conn, records):
    if not records:
        return 0
    sql = """INSERT INTO stock_kline_min
             (ts_code, trade_date, minute, freq, open, high, low, close, vol, amount)
             VALUES (%(ts_code)s, %(trade_date)s, %(minute)s, %(freq)s,
                     %(open)s, %(high)s, %(low)s, %(close)s, %(vol)s, %(amount)s)
             ON DUPLICATE KEY UPDATE
             open=VALUES(open), high=VALUES(high), low=VALUES(low), close=VALUES(close),
             vol=VALUES(vol), amount=VALUES(amount)"""
    count = 0
    for rec in records:
        try:
            cursor.execute(sql, rec)
            count += cursor.rowcount
        except Exception:
            pass
    conn.commit()
    return count


def process_stock_batch(batch):
    """并发工作函数：处理一批股票，每个线程独立 DB 连接"""
    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    stats = {'ok': 0, 'err': 0, 'rec': 0, 'em': 0, 'tencent': 0}

    for ts_code, symbol, market in batch:
        source, klines = fetch_1min_fallback(market, symbol)
        if not klines:
            stats['err'] += 1
            continue

        if source == 'em':
            records = [parse_em_kline(k, ts_code) for k in klines]
        else:
            records = parse_tencent_minute(klines, ts_code, TRADE_DATE)

        records = [r for r in records if r is not None]
        count = insert_batch(cursor, conn, records) if records else 0
        stats['rec'] += count
        stats[source] += 1
        stats['ok'] += 1

    cursor.close()
    conn.close()
    return stats


def main():
    global TRADE_DATE
    if len(sys.argv) >= 3 and sys.argv[1] == '--date':
        TRADE_DATE = sys.argv[2]

    print("=== ETL: 1-minute K-line (Tencent primary → Eastmoney fallback, 20 concurrent) ===", flush=True)
    print(f"  Date: {TRADE_DATE}", flush=True)

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    stocks = get_stocks(cursor)
    total = len(stocks)
    cursor.close()
    conn.close()
    print(f"  Stocks: {total}", flush=True)

    # 均匀分片，每片约 50 只
    chunk_size = max(1, total // 100)
    chunks = [stocks[i:i + chunk_size] for i in range(0, total, chunk_size)]

    total_stats = {'ok': 0, 'err': 0, 'rec': 0, 'em': 0, 'tencent': 0}
    t0 = time.time()

    with ThreadPoolExecutor(max_workers=20) as executor:
        futures = {executor.submit(process_stock_batch, chunk): chunk for chunk in chunks}
        for future in as_completed(futures):
            s = future.result()
            for k in total_stats:
                total_stats[k] += s[k]
            done = total_stats['ok'] + total_stats['err']
            pct = done / total * 100
            el = time.time() - t0
            print(f"  [{done}/{total}] {pct:.0f}%  ok={total_stats['ok']}  err={total_stats['err']}  "
                  f"rec={total_stats['rec']}  {el:.0f}s", flush=True)

    el = time.time() - t0
    print(f"\n  Done: {total_stats['ok']} ok, {total_stats['err']} err, {total_stats['rec']} records")
    print(f"  Sources: EM={total_stats['em']}, Tencent={total_stats['tencent']}, {el/60:.1f}min", flush=True)

if __name__ == '__main__':
    main()
