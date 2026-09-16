package com.example.agentdeepseek.tool.desktop;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级"准星校验记录"注册表（M6 预检闸门："先验证后执行"）。
 *
 * <p>记录"某坐标已经过准星校验图目测确认"这一事实，用于 desktop_control 的点击/拖拽预检闸门：</p>
 * <ul>
 *   <li>写入方：screen_capture 的 mark 校验成功（模型主动校验）＋ desktop_control 预检图生成成功（系统预检）；</li>
 *   <li>读取方：desktop_control 预检闸门——点击/拖拽坐标命中记录则直接执行，否则先出校验图再确认。</li>
 * </ul>
 *
 * <p>匹配规则：坐标容差 ±{@link #TOLERANCE} 归一化单位（模型看图后原样重发微调均视为已确认）；
 * 有效期 {@link #DEFAULT_TTL_MS}（默认 3 分钟，覆盖"校验 → 下一轮 LLM → 点击"间隔）。
 * 仅追加不消费（同一坐标的确认在有效期内持续有效）。</p>
 */
@Component
public class DesktopVerifyRegistry {

    /** 默认有效期：3 分钟（毫秒） */
    public static final long DEFAULT_TTL_MS = 3 * 60 * 1000L;

    /** 坐标匹配容差（归一化单位；±5 ≈ 屏宽 0.5%，微调不触发重复预检） */
    public static final int TOLERANCE = 5;

    /** 单会话记录点数上限（超出丢最旧；防极端循环堆积） */
    private static final int MAX_POINTS_PER_CONVERSATION = 50;

    /** 会话数上限（防极端多会话泄漏） */
    private static final int MAX_ENTRIES = 200;

    /** 记录有效期（毫秒） */
    private final long ttlMs;

    private final Map<Long, List<VerifiedPoint>> records = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, List<VerifiedPoint>> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** Spring 注入用（默认有效期） */
    @Autowired
    public DesktopVerifyRegistry() {
        this(DEFAULT_TTL_MS);
    }

    /** 自定义有效期（测试注入短 TTL；业务默认使用无参构造） */
    public DesktopVerifyRegistry(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * 记录一个"已通过准星校验"的坐标（容差内已存在则刷新时间戳与坐标值）。
     */
    public void record(Long conversationId, int x, int y) {
        if (conversationId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (records) {
            List<VerifiedPoint> points = records.computeIfAbsent(conversationId, k -> new ArrayList<>());
            purgeExpired(points, now);
            for (int i = 0; i < points.size(); i++) {
                VerifiedPoint p = points.get(i);
                if (Math.abs(p.x() - x) <= TOLERANCE && Math.abs(p.y() - y) <= TOLERANCE) {
                    points.set(i, new VerifiedPoint(x, y, now));
                    return;
                }
            }
            points.add(new VerifiedPoint(x, y, now));
            while (points.size() > MAX_POINTS_PER_CONVERSATION) {
                points.remove(0);
            }
        }
    }

    /**
     * 判定坐标是否已通过准星校验（容差内 + 未过期）。
     */
    public boolean isVerified(Long conversationId, int x, int y) {
        if (conversationId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (records) {
            List<VerifiedPoint> points = records.get(conversationId);
            if (points == null) {
                return false;
            }
            purgeExpired(points, now);
            for (VerifiedPoint p : points) {
                if (Math.abs(p.x() - x) <= TOLERANCE && Math.abs(p.y() - y) <= TOLERANCE) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 清除会话的全部校验记录（会话重置等场景预留） */
    public void clear(Long conversationId) {
        if (conversationId != null) {
            records.remove(conversationId);
        }
    }

    /** 当前有效记录点数（测试/诊断用） */
    public int size(Long conversationId) {
        synchronized (records) {
            List<VerifiedPoint> points = records.get(conversationId);
            if (points == null) {
                return 0;
            }
            purgeExpired(points, System.currentTimeMillis());
            return points.size();
        }
    }

    /** 惰性清理过期记录 */
    private void purgeExpired(List<VerifiedPoint> points, long now) {
        points.removeIf(p -> now - p.ts() > ttlMs);
    }

    /** 单条校验记录（归一化坐标 + 记录时间） */
    private record VerifiedPoint(int x, int y, long ts) {
    }
}
