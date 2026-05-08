package com.example.agentdeepseek.util;

import org.apache.commons.codec.digest.DigestUtils;

/**
 * MD5加密工具类
 */
public class Md5Util {

    /**
     * MD5加密
     * @param text 明文
     * @return 密文（32位小写）
     */
    public static String md5(String text) {
        return DigestUtils.md5Hex(text);
    }

    /**
     * 验证MD5
     * @param text 明文
     * @param md5 MD5密文
     * @return 是否匹配
     */
    public static boolean verify(String text, String md5) {
        String md5Text = md5(text);
        return md5Text.equalsIgnoreCase(md5);
    }
}