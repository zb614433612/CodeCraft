package com.example.agentdeepseek.util;

import lombok.extern.slf4j.Slf4j;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件编码检测工具类
 * <p>
 * 用于自动检测文本文件编码，解决中文乱码问题。
 * 检测策略（4层递进）：
 * 第1层：BOM 检测（最可靠，直接命中）
 * 第2层：纯 ASCII 检测（纯英文文件直接判定为 UTF-8）
 * 第3层：juniversalchardet 统计检测（基于 Mozilla universalchardet，支持 30+ 种编码，含置信度验证）
 * 第4层：严格解码试探（UTF-8 → GB18030 → UTF-8 兜底）
 * <p>
 * v2.0 升级：引入 juniversalchardet 2.5.0，编码识别从 4 种扩展到 30+ 种，
 * 包括中文（GB18030/Big5/EUC-CN）、日文（Shift_JIS/EUC-JP）、韩文（EUC-KR）、
 * 西欧（ISO-8859-1/Windows-1252）、东欧（KOI8-R/Windows-1251）等。
 */
@Slf4j
public final class FileEncodingDetector {

    private static final int SAMPLE_SIZE = 4096;
    private static final int MAX_LINE_LENGTH = 100000;

    private FileEncodingDetector() {}

    /**
     * 检测文件的字符编码
     *
     * @param filePath 文件路径
     * @return 检测到的编码，不会返回 null
     */
    public static Charset detectCharset(Path filePath) {
        try {
            long fileSize = Files.size(filePath);
            int sampleSize = (int) Math.min(fileSize, SAMPLE_SIZE);
            if (sampleSize <= 0) return StandardCharsets.UTF_8;

            byte[] sample = new byte[sampleSize];
            try (InputStream is = Files.newInputStream(filePath)) {
                int readLen = is.read(sample);
                if (readLen <= 0) return StandardCharsets.UTF_8;

                return detectCharset(sample, readLen);
            }
        } catch (Exception e) {
            log.debug("编码检测失败，回退 UTF-8: {}", filePath, e);
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 检测字节样本的字符编码
     * <p>
     * 4 层递进检测策略：
     * 第1层：BOM 检测
     * 第2层：纯 ASCII 检测
     * 第3层：juniversalchardet 统计检测（核心升级，覆盖 30+ 编码）
     * 第4层：严格解码试探（UTF-8 → GB18030 → UTF-8 兜底）
     *
     * @param sample  字节样本
     * @param length  有效长度
     * @return 检测到的编码
     */
    public static Charset detectCharset(byte[] sample, int length) {
        // ====== 第1层：BOM 检测 ======
        if (length >= 3 && (sample[0] & 0xFF) == 0xEF && (sample[1] & 0xFF) == 0xBB && (sample[2] & 0xFF) == 0xBF)
            return StandardCharsets.UTF_8;
        if (length >= 2 && (sample[0] & 0xFF) == 0xFE && (sample[1] & 0xFF) == 0xFF)
            return Charset.forName("UTF-16BE");
        if (length >= 2 && (sample[0] & 0xFF) == 0xFF && (sample[1] & 0xFF) == 0xFE)
            return Charset.forName("UTF-16LE");

        // ====== 第2层：纯 ASCII 检测 ======
        boolean isPureAscii = true;
        for (int i = 0; i < length; i++) {
            if ((sample[i] & 0xFF) >= 128) {
                isPureAscii = false;
                break;
            }
        }
        if (isPureAscii) return StandardCharsets.UTF_8;

        // ====== 第3层：juniversalchardet 统计检测（核心升级） ======
        Charset universalResult = detectByUniversalChardet(sample, length);
        if (universalResult != null) {
            return universalResult;
        }

        // ====== 第4层：严格解码试探 ======
        // UTF-8 严格解码检测
        if (canDecode(sample, length, StandardCharsets.UTF_8))
            return StandardCharsets.UTF_8;

        // GB18030（兼容 GBK/GB2312）——解决中文 Windows 系统上的中文乱码问题
        if (canDecode(sample, length, Charset.forName("GB18030")))
            return Charset.forName("GB18030");

        // 兜底
        return StandardCharsets.UTF_8;
    }

    /**
     * 使用 juniversalchardet 进行编码检测
     * <p>
     * 基于 Mozilla universalchardet 统计模型，支持 30+ 种编码。
     * 检测结果会经过 {@link #canDecode} 验证，避免误判。
     *
     * @param sample 字节样本
     * @param length 有效长度
     * @return 检测到的编码，如果无法确定或验证失败则返回 null
     */
    private static Charset detectByUniversalChardet(byte[] sample, int length) {
        try {
            UniversalDetector detector = new UniversalDetector();
            detector.handleData(sample, 0, length);
            detector.dataEnd();
            String detectedName = detector.getDetectedCharset();
            detector.reset();

            if (detectedName == null) {
                log.trace("juniversalchardet 无法确定编码（样本可能过短或编码特征不明显）");
                return null;
            }

            // juniversalchardet 返回的编码名映射为 Java Charset
            Charset charset;
            try {
                charset = Charset.forName(detectedName);
            } catch (Exception e) {
                log.debug("juniversalchardet 返回未知编码名 {}，忽略", detectedName);
                return null;
            }

            // 验证：检测到的编码必须能成功解码样本
            if (canDecode(sample, length, charset)) {
                log.trace("juniversalchardet 检测编码: {}（通过验证）", charset);
                return charset;
            }

            // 验证失败：juniversalchardet 的统计结果可能因短样本不准
            log.trace("juniversalchardet 检测编码 {} 但解码验证失败，回退后续策略", charset);
            return null;

        } catch (Exception e) {
            log.debug("juniversalchardet 检测异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断字节数组能否用指定编码无错误解码
     */
    public static boolean canDecode(byte[] bytes, int length, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bb = ByteBuffer.wrap(bytes, 0, length);
            CharBuffer cb = CharBuffer.allocate(length);
            CoderResult result = decoder.decode(bb, cb, true);
            if (result.isError()) return false;
            result = decoder.flush(cb);
            return !result.isError();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 以自动检测的编码读取文件全部内容
     *
     * @param filePath 文件路径
     * @return 文件内容字符串
     * @throws IOException 读取失败时抛出
     */
    public static String readString(Path filePath) throws IOException {
        Charset charset = detectCharset(filePath);
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            return new String(bytes, charset);
        } catch (Exception e) {
            // 读取失败时尝试其他常见编码降级
            return readStringWithFallback(filePath, charset);
        }
    }

    /**
     * 读取字符串失败时的降级策略
     * 依次尝试 GB18030、UTF-8、ISO-8859-1 逐级降级
     */
    private static String readStringWithFallback(Path filePath, Charset failedCharset) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        // 排除已尝试过的编码
        List<Charset> fallbacks = new ArrayList<>();
        if (!failedCharset.name().equals("GB18030")) {
            fallbacks.add(Charset.forName("GB18030"));
        }
        if (!failedCharset.name().equals("UTF-8")) {
            fallbacks.add(StandardCharsets.UTF_8);
        }
        // 最后兜底：ISO-8859-1（保证不丢字节，但可能显示为乱码）
        fallbacks.add(StandardCharsets.ISO_8859_1);

        for (Charset fallback : fallbacks) {
            try {
                String result = new String(bytes, fallback);
                log.warn("使用 {} 解码失败，降级为 {}: {}",
                        failedCharset, fallback, filePath);
                return result;
            } catch (Exception ignored) {
                // 继续尝试下一个
            }
        }
        throw new IOException("所有编码降级方案均失败: " + filePath);
    }

    /**
     * 以自动检测的编码读取文件所有行
     *
     * @param filePath 文件路径
     * @return 文件的所有行
     * @throws IOException 读取失败时抛出
     */
    public static List<String> readAllLines(Path filePath) throws IOException {
        Charset charset = detectCharset(filePath);
        try {
            return readAllLinesInternal(filePath, charset);
        } catch (MalformedInputException e) {
            log.warn("使用 {} 解码失败，降级尝试 GB18030: {}", charset, filePath);
            return readAllLinesInternal(filePath, Charset.forName("GB18030"));
        }
    }

    private static List<String> readAllLinesInternal(Path filePath, Charset charset) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, charset)) {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch == '\n') {
                    lines.add(sb.toString());
                    sb = new StringBuilder();
                } else if (ch == '\r') {
                    lines.add(sb.toString());
                    sb = new StringBuilder();
                    reader.mark(1);
                    int next = reader.read();
                    if (next != '\n') {
                        reader.reset();
                    }
                } else {
                    if (sb.length() < MAX_LINE_LENGTH) {
                        sb.append((char) ch);
                    }
                }
            }
            if (sb.length() > 0 || lines.isEmpty()) {
                lines.add(sb.toString());
            }
        }
        return lines;
    }
}
