"""
ETL: 获取A股全部股票基础信息，写入 stock_info 表
数据源：新浪财经 stock list API
"""
import requests
import pymysql
import time

DB_CONFIG = {
    'host': 'localhost',
    'port': 3306,
    'user': 'root',
    'password': 'Zb960321',
    'database': 'agent_deepseek',
    'charset': 'utf8mb4'
}

def get_market(symbol):
    """从symbol (如 sz000001) 判断市场"""
    if symbol.startswith('sh'):
        return 'SH'
    elif symbol.startswith('sz'):
        return 'SZ'
    return 'SZ'

def fetch_stock_list():
    """从新浪获取全量A股列表"""
    all_stocks = []
    session = requests.Session()
    session.trust_env = False
    headers = {'User-Agent': 'Mozilla/5.0', 'Referer': 'https://finance.sina.com.cn/'}
    url = 'http://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/Market_Center.getHQNodeData'
    page = 1

    while True:
        params = {'page': page, 'num': 100, 'sort': 'code', 'asc': 1, 'node': 'hs_a', '_s_r_a': 'init'}
        try:
            r = session.get(url, params=params, headers=headers, timeout=10)
            stocks = r.json()
            if not stocks:
                break
            for s in stocks:
                code = s.get('code', '')
                name = s.get('name', '')
                symbol = s.get('symbol', '')
                all_stocks.append({
                    'ts_code': f"{code}.{get_market(symbol)}",
                    'symbol': code,
                    'market': get_market(symbol),
                    'name': name,
                    'list_date': None,
                    'status': 1
                })
            print(f"  Page {page}: {len(stocks)} stocks (total: {len(all_stocks)})")
            page += 1
            if len(stocks) < 100:
                break
            time.sleep(1)
        except Exception as e:
            print(f"  Error on page {page}: {e}")
            time.sleep(2)
            page += 1
            if page > 60:
                break

    print(f"  Total: {len(all_stocks)} stocks")
    return all_stocks

def main():
    print("=== ETL: Stock Info ===")
    stocks = fetch_stock_list()
    if not stocks:
        print("  ERROR: No stocks fetched")
        return

    conn = pymysql.connect(**DB_CONFIG)
    cursor = conn.cursor()

    cursor.execute("TRUNCATE TABLE stock_info")
    print("  Truncated stock_info")

    sql = """INSERT INTO stock_info (ts_code, symbol, market, name, list_date, status)
             VALUES (%(ts_code)s, %(symbol)s, %(market)s, %(name)s, %(list_date)s, %(status)s)"""

    batch_size = 500
    total = len(stocks)
    inserted = 0

    for i in range(0, total, batch_size):
        batch = stocks[i:i+batch_size]
        try:
            cursor.executemany(sql, batch)
            conn.commit()
            inserted += len(batch)
        except Exception as e:
            print(f"  Insert error: {e}")
            conn.rollback()
            for s in batch:
                try:
                    cursor.execute(sql, s)
                    conn.commit()
                    inserted += 1
                except Exception as e2:
                    print(f"  Skip {s['ts_code']}: {e2}")
                    conn.rollback()
        print(f"  Progress: {inserted}/{total}")

    cursor.close()
    conn.close()
    print(f"  Done: stock_info has {inserted} records")

if __name__ == '__main__':
    main()
