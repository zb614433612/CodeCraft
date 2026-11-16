package com.example.agentdeepseek.util;

import java.security.SecureRandom;

/**
 * 随机码生成工具类。
 *
 * <p><b>⚠️ 必须用 SecureRandom（2026-11-16 修复）</b>：</p>
 * <p>此前用 {@code java.util.Random} 共享单例，两个问题：</p>
 * <ol>
 *   <li><b>可预测</b>：{@code Random} 是线性同余伪随机，连续少量输出即可反推内部状态。
 *       本类生成的随机码用于登录挑战（{@code MD5(MD5(密码) + 随机码)}），
 *       可预测意味着攻击者能预先算好摘要；</li>
 *   <li><b>多线程竞态</b>：{@code Random} 内部 seed 是普通 long，并发 {@code nextInt}
 *       存在更新丢失（JDK 虽用 CAS 兜底保证不坏，但并发下序列相关性变高）。</li>
 * </ol>
 * <p>{@code SecureRandom} 线程安全且不可预测，成本可忽略（登录验证码是低频调用）。
 * 注意 {@code SecureRandom} 首次初始化会读系统熵源，故仍保持静态单例复用，
 * 不要每次调用 new（那才是真正的性能问题）。</p>
 */
public class RandomCodeUtil {

    private static final String CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** 线程安全 + 密码学安全；静态复用避免反复初始化熵源的开销 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成指定长度的随机码（字母数字）
     * @param length 长度
     * @return 随机码
     */
    public static String generate(int length) {
        if (length <= 0) return "";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = RANDOM.nextInt(CHAR_POOL.length());
            sb.append(CHAR_POOL.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 生成4位随机码
     * @return 4位随机码
     */
    public static String generate4() {
        return generate(4);
    }
}