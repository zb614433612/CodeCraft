"""
修复 2026-05-06 存量数据：腾讯API累积成交量/成交额 → 当前分钟实际值
仅对有累积特征（成交量严格递增）的股票做减法修复
"""
import pymysql
import datetime

DB_CONFIG = {
    'host': 'localhost', 'port': 3306,
    'user': 'root', 'password': 'Zb960321',
    'database': 'agent_deepseek', 'charset': 'utf8mb4'
}

TRADE_DATE = '2026-05-06'

conn = pymysql.connect(**DB_CONFIG)
cursor = conn.cursor()

# Step 1: 查出所有股票及其分钟vol，标记是否为累积数据
print(f"正在分析 {TRADE_DATE} 存量分钟K线数据...")
cursor.execute("""
    SELECT ts_code, COUNT(*) as cnt
    FROM stock_kline_min
    WHERE trade_date = %s AND freq = 1
    GROUP BY ts_code
""", (TRADE_DATE,))
stocks = {r[0]: r[1] for r in cursor.fetchall()}
print(f"  共 {len(stocks)} 只股票")

# Step 2: 逐只检查是否有累积特征（vol严格非递减）
cumulative_stocks = []
for ts_code in stocks:
    cursor.execute("""
        SELECT minute, vol, amount FROM stock_kline_min
        WHERE ts_code = %s AND trade_date = %s AND freq = 1
        ORDER BY minute
    """, (ts_code, TRADE_DATE))
    rows = cursor.fetchall()
    if len(rows) < 5:
        continue

    vols = [r[1] for r in rows]
    # 如果成交量严格非递减（允许连续相等），则为累积数据
    is_cumulative = all(vols[i] <= vols[i + 1] for i in range(len(vols) - 1))
    if is_cumulative and vols[-1] > 0:
        cumulative_stocks.append(ts_code)

print(f"  其中 {len(cumulative_stocks)} 只股票为累积数据（需修复）")

if not cumulative_stocks:
    print("  无需修复，退出。")
    cursor.close()
    conn.close()
    exit(0)

# Step 3: 修复累积数据
print("\n开始修复累积成交量/成交额...")
fixed_count = 0
for ts_code in cumulative_stocks:
    cursor.execute("""
        SELECT minute, vol, amount FROM stock_kline_min
        WHERE ts_code = %s AND trade_date = %s AND freq = 1
        ORDER BY minute
    """, (ts_code, TRADE_DATE))
    rows = cursor.fetchall()

    for i, (minute, vol, amount) in enumerate(rows):
        if i == 0:
            # 第一分钟不修改（本身就是当前分钟实际值）
            continue
        prev_vol = rows[i - 1][1]
        prev_amount = rows[i - 1][2]
        new_vol = vol - prev_vol
        new_amount = amount - prev_amount
        if new_vol < 0 or new_amount < 0:
            # 安全防护：如果减出负数则跳过
            continue
        cursor.execute("""
            UPDATE stock_kline_min
            SET vol = %s, amount = %s
            WHERE ts_code = %s AND trade_date = %s AND minute = %s AND freq = 1
        """, (new_vol, new_amount, ts_code, TRADE_DATE, minute))
        fixed_count += cursor.rowcount

    if fixed_count % 1000 == 0:
        conn.commit()

conn.commit()
print(f"  修复完成，共更新 {fixed_count} 条记录")
print(f"  涉及股票：{len(cumulative_stocks)} 只")

# 验证：再检查一下是否还有累积数据
print("\n验证修复结果...")
still_cumulative = 0
for ts_code in cumulative_stocks:
    cursor.execute("""
        SELECT vol FROM stock_kline_min
        WHERE ts_code = %s AND trade_date = %s AND freq = 1
        ORDER BY minute
    """, (ts_code, TRADE_DATE))
    vols = [r[0] for r in cursor.fetchall()]
    if len(vols) >= 5 and all(vols[i] <= vols[i + 1] for i in range(len(vols) - 1)):
        still_cumulative += 1

if still_cumulative == 0:
    print("  全部修复成功，无残留累积数据 ✓")
else:
    print(f"  仍有 {still_cumulative} 只股票未完全修复")

cursor.close()
conn.close()
print(f"\n完成时间: {datetime.datetime.now()}")
