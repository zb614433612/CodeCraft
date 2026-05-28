package com.example.agentdeepseek.p2p.signaling;

import com.example.agentdeepseek.p2p.protocol.P2pConstants;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 二维码信令
 * <p>
 * 生成和解析二维码，用于面对面交换 P2P 连接信息。
 * </p>
 */
public class QrCodeSignaling {

    private static final Logger log = LoggerFactory.getLogger(QrCodeSignaling.class);

    /** 二维码格式 */
    private static final String IMAGE_FORMAT = "PNG";

    /** QR Code 编码配置 */
    private static final Map<EncodeHintType, Object> ENCODE_HINTS = new HashMap<>();
    static {
        ENCODE_HINTS.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        ENCODE_HINTS.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        ENCODE_HINTS.put(EncodeHintType.MARGIN, 2);
    }

    /** QR Code 解码配置 */
    private static final Map<DecodeHintType, Object> DECODE_HINTS = new HashMap<>();
    static {
        DECODE_HINTS.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        DECODE_HINTS.put(DecodeHintType.TRY_HARDER, true);
    }

    /**
     * 生成二维码图片（Base64 编码的 PNG）
     *
     * @param data 信令数据
     * @return Base64 编码的 PNG 图片
     */
    public static String generateQrCodeBase64(SignalingData data) {
        try {
            // 使用紧凑格式生成二维码（减少数据量，提高识别率）
            String content = ConnectionStringHelper.generateCompact(data);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE,
                    P2pConstants.QR_CODE_SIZE, P2pConstants.QR_CODE_SIZE, ENCODE_HINTS);

            MatrixToImageConfig config = new MatrixToImageConfig(0xFF000000, 0xFFFFFFFF);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix, config);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, IMAGE_FORMAT, baos);
            byte[] pngBytes = baos.toByteArray();

            return "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
        } catch (Exception e) {
            log.error("[P2P] Failed to generate QR code", e);
            throw new RuntimeException("QR code generation failed", e);
        }
    }

    /**
     * 生成二维码 BufferedImage 对象
     */
    public static BufferedImage generateQrCodeImage(SignalingData data) throws WriterException {
        String content = ConnectionStringHelper.generateCompact(data);

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE,
                P2pConstants.QR_CODE_SIZE, P2pConstants.QR_CODE_SIZE, ENCODE_HINTS);

        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    /**
     * 从 Base64 编码的图片解析信令数据
     *
     * @param base64Image Base64 编码的图片（可以带 data:image/png;base64, 前缀）
     * @return 解析出的信令数据
     */
    public static SignalingData parseQrCode(String base64Image) {
        try {
            // 去掉可能的 data:image/xxx;base64, 前缀
            String pureBase64 = base64Image;
            if (base64Image.contains(",")) {
                pureBase64 = base64Image.substring(base64Image.indexOf(",") + 1);
            }

            byte[] imageBytes = Base64.getDecoder().decode(pureBase64);
            return parseQrCode(imageBytes);
        } catch (Exception e) {
            log.error("[P2P] Failed to parse QR code from base64", e);
            throw new RuntimeException("QR code parsing failed", e);
        }
    }

    /**
     * 从图片字节数组解析信令数据
     */
    public static SignalingData parseQrCode(byte[] imageBytes) throws IOException, NotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
        BufferedImage image = ImageIO.read(bais);

        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        Result result = new MultiFormatReader().decode(bitmap, DECODE_HINTS);
        String content = result.getText();

        // 尝试紧凑格式解析
        return ConnectionStringHelper.parseCompact(content);
    }

    /**
     * 从 BufferedImage 解析信令数据
     */
    public static SignalingData parseQrCode(BufferedImage image) throws NotFoundException {
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        Result result = new MultiFormatReader().decode(bitmap, DECODE_HINTS);
        String content = result.getText();

        return ConnectionStringHelper.parseCompact(content);
    }
}
