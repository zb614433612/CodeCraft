"""
ETL: 日K线数据，写入 stock_kline_daily
数据源：
  --mode baostock（默认）：增量补全，从 baostock 拉取缺失日线
  --mode realtime：从 stock_realtime 表获取今日收盘数据构建日K线
"""
import argparse
import baostock as bs
import pymysql
import time
from datetime import datetime, timedelta, date as date_type

DB_CONFIG = {
    'host': 'localhost', 'port': 3306,
    'user': 'root', 'password': 'Zb960321',
    'database': 'agent_deepseek', 'charset': 'utf8mb4'
}

BATCH_SIZE = 1000
SLEEP = 1

INSERT_SQL = """INSERT INTO stock_kline_daily
                (ts_code, trade_date, open, high, low, close, pre_close, change_, pct_change,
                 vol, amount, turnover)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                open=VALUES(open), high=VALUES(high), low=VALUES(low), close=VALUES(close),
                pre_close=VALUES(pre_close), change_=VALUES(change_), pct_change=VALUES(pct_change),
                vol=VALUES(vol), amount=VALUES(amount), turnover=VALUES(turnover)"""


# ================== 模式1：从 stock_realtime 构建今日日K线 ==================

def mode_realtime():
    """
    从 stock_realtime 表读取今日收盘快照，写入 stock_kline_daily
    用于收盘后（实时行情采集完成之后）快速生成今日日K线
    """
    today = date_type.today()
    today_str = today.strftime('%Y-%m-%d')
    print(f"=== Mode: realtime -> 从 stock_realtime 构建 {today_str} 日K线 ===")

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    cursor.execute("""
        SELECT ts_code, price, open, high, low, pre_close,
               change_, pct_change, volume, amount, turnover_rate
        FROM stock_realtime
        WHERE price IS NOT NULL AND price > 0
    """)
    rows = cursor.fetchall()
    total = len(rows)
    print(f"  stock_realtime rows: {total}")

    batch = []
    for row in rows:
        ts_code = row[0]
        price = float(row[1]) if row[1] is not None else 0
        open_ = float(row[2]) if row[2] is not None else 0
        high_ = float(row[3]) if row[3] is not None else 0
        low_ = float(row[4]) if row[4] is not None else 0
        pre_close = float(row[5]) if row[5] is not None else 0
        change_ = float(row[6]) if row[6] is not None else None
        pct_change = float(row[7]) if row[7] is not None else None
        volume = int(row[8]) if row[8] is not None else 0
        amount = float(row[9]) if row[9] is not None else 0.0
        turnover = float(row[10]) if row[10] is not None else None

        if price == 0:
            continue

        batch.append((
            ts_code, today_str,
            open_, high_, low_, price,
            pre_close, change_, pct_change,
            volume, amount, turnover
        ))

        if len(batch) >= BATCH_SIZE:
            try:
                cursor.executemany(INSERT_SQL, batch)
                conn.commit()
            except Exception as e:
                conn.rollback()
            batch = []

    if batch:
        try:
            cursor.executemany(INSERT_SQL, batch)
            conn.commit()
        except Exception as e:
            conn.rollback()

    cursor.close()
    conn.close()
    print(f"  Done: {total} stocks -> stock_kline_daily")


# ================== 模式2：baostock 增量补全 ==================

def get_stocks(cursor):
    cursor.execute("""
        SELECT si.ts_code, si.symbol, si.market,
               MAX(kd.trade_date) AS last_date
        FROM stock_info si
        LEFT JOIN stock_kline_daily kd ON kd.ts_code = si.ts_code
        GROUP BY si.ts_code, si.symbol, si.market
        ORDER BY si.symbol
    """)
    return cursor.fetchall()


def fetch_missing(bs_code, start_date):
    """只拉取 start_date 到今天的日线数据"""
    rs = bs.query_history_k_data_plus(
        bs_code,
        'date,open,high,low,close,preclose,volume,amount,pctChg,turn',
        start_date=start_date,
        end_date=datetime.now().strftime('%Y-%m-%d'),
        frequency='d',
        adjustflag='3'
    )
    if rs.error_code != '0':
        return None
    df = rs.get_data()
    return df if df is not None and not df.empty else None


def mode_baostock():
    """baostock 增量补全（原有逻辑）"""
    print("=== Mode: baostock -> 增量补全日K线 ===")

    lg = bs.login()
    if lg.error_code != '0':
        print(f"Login failed: {lg.error_msg}")
        return
    print("baostock login OK")

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    stocks = get_stocks(cursor)
    total = len(stocks)
    print(f"Stocks: {total}")

    stats = {'ok': 0, 'err': 0, 'records': 0, 'skip': 0}
    t_start = time.time()
    today_str = datetime.now().strftime('%Y-%m-%d')

    for idx, row in enumerate(stocks, 1):
        ts_code = row[0]
        symbol = row[1]
        market = row[2]
        last_date = row[3]

        if last_date and last_date.strftime('%Y-%m-%d') >= today_str:
            stats['skip'] += 1
            if idx % 500 == 0:
                print(f"  [{idx}/{total}] {ts_code} - already up-to-date ({last_date})")
            continue

        if last_date:
            start = (last_date + timedelta(days=1)).strftime('%Y-%m-%d')
        else:
            start = '1990-01-01'

        bs_code = f"{market.lower()}.{symbol}"
        df = fetch_missing(bs_code, start)

        if df is None:
            stats['err'] += 1
            if idx % 500 == 0:
                print(f"  [{idx}/{total}] {ts_code} - no data since {start}")
            time.sleep(SLEEP)
            continue

        batch = []
        for _, row_data in df.iterrows():
            try:
                close_val = float(row_data['close'])
                preclose = float(row_data['preclose']) if row_data['preclose'] else None
                change = round(close_val - preclose, 4) if preclose else None
                batch.append((
                    ts_code, row_data['date'],
                    float(row_data['open']), float(row_data['high']), float(row_data['low']), close_val,
                    preclose, change,
                    float(row_data['pctChg']) if row_data['pctChg'] else None,
                    int(float(row_data['volume'])) if row_data['volume'] else 0,
                    float(row_data['amount']) if row_data['amount'] else 0.0,
                    float(row_data['turn']) if row_data['turn'] else None
                ))
            except (ValueError, TypeError):
                continue

            if len(batch) >= BATCH_SIZE:
                try:
                    cursor.executemany(INSERT_SQL, batch)
                    conn.commit()
                    stats['records'] += len(batch)
                except Exception as e:
                    conn.rollback()
                batch = []

        if batch:
            try:
                cursor.executemany(INSERT_SQL, batch)
                conn.commit()
                stats['records'] += len(batch)
            except Exception as e:
                conn.rollback()

        stats['ok'] += 1

        if idx % 100 == 0:
            elapsed = time.time() - t_start
            rate = idx / elapsed if elapsed > 0 else 0
            eta = (total - idx) / rate if rate > 0 else 0
            print(f"  [{idx}/{total}] {ts_code} -> {len(df)} klines since {start}, "
                  f"total: {stats['records']}, rate: {rate:.1f}/s, ETA: {eta/60:.1f}min")

        time.sleep(SLEEP)

    bs.logout()
    cursor.close()
    conn.close()
    elapsed = time.time() - t_start
    print(f"\n  Done: {stats['ok']} ok, {stats['err']} err, {stats['skip']} up-to-date, {stats['records']} klines")
    print(f"  Time: {elapsed/60:.1f} min, rate: {stats['ok']/elapsed:.1f} stocks/s")


def main():
    parser = argparse.ArgumentParser(description='日K线数据 ETL')
    parser.add_argument('--mode', choices=['baostock', 'realtime'], default='baostock',
                        help='数据源：baostock=增量补全(默认), realtime=从实时行情表构建今日线')
    args = parser.parse_args()

    if args.mode == 'realtime':
        mode_realtime()
    else:
        mode_baostock()


if __name__ == '__main__':
    main()
