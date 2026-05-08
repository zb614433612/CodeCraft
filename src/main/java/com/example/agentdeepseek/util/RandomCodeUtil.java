package com.example.agentdeepseek.util;

import java.util.Random;

/**
 * 随机码生成工具类
 */
public class RandomCodeUtil {

    private static final String CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Random RANDOM = new Random();

    /**
     * 生成指定长度的随机码（字母数字）
     * @param length 长度
     * @return 随机码
     */
    public static String generate(int length) {
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