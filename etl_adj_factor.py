"""
ETL: 获取复权因子数据，写入 stock_adj_factor
数据源：baostock（数据全面，含前后复权因子）
"""
import baostock as bs
import pymysql
import time
import datetime

DB_CONFIG = {
    'host': 'localhost', 'port': 3306,
    'user': 'root', 'password': 'Zb960321',
    'database': 'agent_deepseek', 'charset': 'utf8mb4'
}

def get_stocks(cursor):
    cursor.execute("SELECT ts_code, symbol, market, name FROM stock_info ORDER BY symbol")
    return cursor.fetchall()

def insert_batch(cursor, conn, records):
    if not records:
        return 0
    sql = """INSERT INTO stock_adj_factor
             (ts_code, trade_date, adj_factor, fore_adj_factor, back_adj_factor)
             VALUES (%(ts_code)s, %(trade_date)s, %(adj_factor)s,
                     %(fore_adj_factor)s, %(back_adj_factor)s)
             ON DUPLICATE KEY UPDATE
             adj_factor=VALUES(adj_factor),
             fore_adj_factor=VALUES(fore_adj_factor),
             back_adj_factor=VALUES(back_adj_factor)"""
    count = 0
    for rec in records:
        try:
            cursor.execute(sql, rec)
            count += 1
        except Exception as e:
            print(f"    Skip {rec.get('ts_code')} {rec.get('trade_date')}: {e}")
    conn.commit()
    return count

def main():
    print("=== ETL: Adjustment Factors (baostock) ===")

    # Login baostock
    lg = bs.login()
    if lg.error_code != '0':
        print(f"  baostock login failed: {lg.error_msg}")
        return
    print("  baostock login OK")

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()
    stocks = get_stocks(cursor)
    total = len(stocks)
    print(f"  Stocks: {total}")

    stats = {'ok': 0, 'err': 0, 'rec': 0, 'skip': 0}
    t0 = time.time()

    for idx, (ts_code, symbol, market, name) in enumerate(stocks, 1):
        baostock_code = f"{market.lower()}.{symbol}"

        try:
            rs = bs.query_adjust_factor(baostock_code)
            if rs.error_code != '0':
                stats['err'] += 1
                if idx % 500 == 0:
                    print(f"  [{idx}/{total}] {ts_code} {name} ERROR: {rs.error_msg}")
                continue

            rows = []
            while rs.next():
                row_data = rs.get_row_data()
                # row_data: [code, dividOperateDate, foreAdjustFactor, backAdjustFactor, adjustFactor]
                if len(row_data) >= 5 and row_data[1]:
                    rows.append({
                        'ts_code': ts_code,
                        'trade_date': row_data[1],
                        'adj_factor': float(row_data[4]) if row_data[4] else None,
                        'fore_adj_factor': float(row_data[2]) if row_data[2] else None,
                        'back_adj_factor': float(row_data[3]) if row_data[3] else None,
                    })

            if not rows:
                stats['skip'] += 1
                if idx % 1000 == 0:
                    print(f"  [{idx}/{total}] {ts_code} {name} no data")
                continue

            count = insert_batch(cursor, conn, rows)
            stats['rec'] += count
            stats['ok'] += 1

            if idx % 200 == 0:
                elapsed = time.time() - t0
                print(f"  [{idx}/{total}] {ts_code} {name} {len(rows)} rows, saved {count}, total rec: {stats['rec']}, {elapsed:.0f}s")

        except Exception as e:
            stats['err'] += 1
            if idx % 500 == 0:
                print(f"  [{idx}/{total}] {ts_code} {name} EXCEPTION: {e}")

    bs.logout()
    cursor.close()
    conn.close()

    elapsed = time.time() - t0
    print(f"\n=== Done: {stats['ok']} ok, {stats['err']} err, {stats['skip']} no data, {stats['rec']} records, {elapsed:.0f}s ===")

if __name__ == '__main__':
    main()
