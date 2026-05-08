"""
ETL: 补全1分钟K线数据
主数据源：AKShare（东方财富），仅提供最近约5个交易日
降级数据源：腾讯财经 minute/query（AKShare失败时自动切换）
"""
import akshare as ak
import pandas as pd
import requests
import pymysql
import time
import datetime
import sys

DB_CONFIG = {
    'host': 'localhost', 'port': 3306,
    'user': 'root', 'password': 'Zb960321',
    'database': 'agent_deepseek', 'charset': 'utf8mb4'
}

SLEEP = 1
RETRY_SLEEP = 3
TENCENT_URL = 'http://ifzq.gtimg.cn/appstock/app/minute/query'
TENCENT_HEADERS = {'User-Agent': 'Mozilla/5.0'}

_SESSION = requests.Session()
_SESSION.trust_env = False

# 全局探测：东方财富是否可用
_EM_ALIVE = None


def probe_em():
    """快速探测东方财富是否可用（全局只测一次）"""
    global _EM_ALIVE
    if _EM_ALIVE is not None:
        return _EM_ALIVE
    try:
        r = _SESSION.get(
            'http://push2his.eastmoney.com/api/qt/stock/kline/get',
            params={'secid': '1.600000', 'fields1': 'f1,f2', 'fields2': 'f51,f52',
                    'klt': '1', 'fqt': '1', 'beg': '0', 'end': '20500000',
                    'ut': '7eea3edcaed734bea9cbfc24409ed989'},
            headers={'User-Agent': 'Mozilla/5.0', 'Referer': 'https://quote.eastmoney.com/'},
            timeout=10
        )
        _EM_ALIVE = r.status_code == 200 and r.text.strip() != ''
    except Exception:
        _EM_ALIVE = False
    return _EM_ALIVE


def get_stocks(cursor):
    cursor.execute("SELECT ts_code, symbol, market FROM stock_info ORDER BY symbol")
    return cursor.fetchall()


# ====================== 数据源1：AKShare（东方财富） ======================

def fetch_akshare(symbol, retries=1):
    """尝试 AKShare 接口（东方财富），探测到EM不可用时直接返回None"""
    if not probe_em():
        return None
    for attempt in range(retries + 1):
        try:
            df = ak.stock_zh_a_hist_min_em(symbol=symbol, period='1')
            if df is None or df.empty:
                return None
            return df
        except Exception as e:
            if attempt < retries:
                print(f"    akshare retry [{symbol}] attempt {attempt+1}/{retries}: {e}")
                time.sleep(RETRY_SLEEP * (attempt + 1))
            else:
                return None


def parse_akshare_row(row, ts_code):
    """解析 AKShare DataFrame 行"""
    try:
        dt_str = str(row['时间'])
        if ' ' not in dt_str:
            return None
        date_str, time_str = dt_str.split(' ')
        if len(time_str) == 5:
            time_str += ':00'
        vol_val = int(row['成交量'])
        return {
            'ts_code': ts_code,
            'trade_date': date_str,
            'minute': time_str,
            'freq': 1,
            'open': float(row['开盘']),
            'high': float(row['最高']),
            'low': float(row['最低']),
            'close': float(row['收盘']),
            'vol': vol_val,
            'amount': float(row['成交额']) if row['成交额'] else 0.0,
        }
    except (ValueError, TypeError, KeyError):
        return None


# ====================== 数据源2：腾讯财经（降级） ======================

def fetch_tencent(market, symbol):
    """腾讯分钟接口（降级）"""
    prefix = 'sh' if market == 'SH' else 'sz'
    tcode = f"{prefix}{symbol}"
    try:
        r = _SESSION.get(f"{TENCENT_URL}?code={tcode}", headers=TENCENT_HEADERS, timeout=10)
        data = r.json()
        if data.get('code') != 0:
            return None
        path = data.get('data', {}).get(tcode, {}).get('data', {}).get('data')
        if not path or not isinstance(path, list):
            return None
        return path
    except Exception:
        return None


def parse_tencent(raw_lines, ts_code, trade_date=None):
    """解析腾讯分钟数据 -> 近似K线
    注意：腾讯返回的成交量/成交额是累积值，需减前一分钟得到当前分钟实际值
    """
    if trade_date is None:
        trade_date = datetime.date.today().strftime('%Y-%m-%d')
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

def fetch_1min(symbol, market):
    """先试 AKShare，失败降级到腾讯"""
    df = fetch_akshare(symbol)
    if df is not None:
        return 'akshare', df

    time.sleep(0.5)
    tk = fetch_tencent(market, symbol)
    if tk:
        return 'tencent', tk

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
            count += 1
        except Exception:
            pass
    conn.commit()
    return count


def main():
    # 解析命令行参数 --date YYYY-MM-DD
    trade_date = None
    if len(sys.argv) >= 3 and sys.argv[1] == '--date':
        trade_date = sys.argv[2]

    print("=== ETL: 1-minute K-line backfill (AKShare -> Tencent fallback) ===")
    print(f"  Date: {trade_date or datetime.date.today().strftime('%Y-%m-%d')}")
    print(f"  Time: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    em_ok = probe_em()
    print(f"  Eastmoney API: {'OK' if em_ok else 'DOWN (use Tencent fallback)'}")

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    stocks = get_stocks(cursor)
    total = len(stocks)
    print(f"  Stocks: {total}")

    stats = {'ok': 0, 'err': 0, 'rec': 0, 'akshare': 0, 'tencent': 0}
    t0 = time.time()

    for idx, (ts_code, symbol, market) in enumerate(stocks, 1):
        symbol_str = str(symbol)
        source, data = fetch_1min(symbol_str, market)
        if data is None:
            stats['err'] += 1
            if idx % 100 == 0 or idx <= 3:
                print(f"  [{idx}/{total}] {ts_code} SKIP (both sources failed)")
            time.sleep(SLEEP)
            continue

        if source == 'akshare':
            records = []
            for _, r in data.iterrows():
                rec = parse_akshare_row(r, ts_code)
                if rec:
                    records.append(rec)
            stats['akshare'] += 1
        else:
            records = parse_tencent(data, ts_code, trade_date)
            stats['tencent'] += 1

        n = insert_batch(cursor, conn, records)
        stats['rec'] += n
        stats['ok'] += 1

        if idx % 100 == 0 or idx <= 3:
            el = time.time() - t0
            print(f"  [{idx}/{total}] {ts_code} [{source}] {len(records)} rows, {n} saved, "
                  f"total rec: {stats['rec']:,}, {el/60:.1f}min")

        time.sleep(SLEEP)

    cursor.close()
    conn.close()
    el = time.time() - t0
    print(f"\n  Done: {stats['ok']} ok, {stats['err']} err, {stats['rec']:,} records, {el/60:.1f}min")
    print(f"  Sources: AKShare={stats['akshare']}, Tencent={stats['tencent']}")

if __name__ == '__main__':
    main()
