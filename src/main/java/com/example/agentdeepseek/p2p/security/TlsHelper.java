package com.example.agentdeepseek.p2p.security;

import com.example.agentdeepseek.p2p.protocol.P2pConstants;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * TLS 自签名证书管理
 * <p>
 * 为 P2P 连接提供 TLS 加密，使用自签名证书 + 指纹校验（TOFU 信任模型）。
 * </p>
 */
public class TlsHelper {

    private static final Logger log = LoggerFactory.getLogger(TlsHelper.class);

    private static final String KEY_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final Path certDir;
    private final Path keyStorePath;
    private final Path certPath;

    private KeyStore keyStore;
    private String certFingerprint;
    private SslContext serverSslContext;
    private X509Certificate certificate;
    private PrivateKey privateKey;

    public TlsHelper() {
        this.certDir = Paths.get(P2pConstants.CERT_DIR);
        this.keyStorePath = certDir.resolve("p2p-keystore.jks");
        this.certPath = certDir.resolve("p2p-cert.der");
    }

    /**
     * 初始化：加载已有证书或生成新的自签名证书
     */
    public void init() throws Exception {
        Files.createDirectories(certDir);

        if (Files.exists(keyStorePath)) {
            loadKeyStore();
        } else {
            generateSelfSignedCert();
        }

        initSslContext();
        log.info("[P2P] TLS initialized, cert fingerprint: {}", certFingerprint);
    }

    /**
     * 生成自签名证书
     */
    private void generateSelfSignedCert() throws Exception {
        log.info("[P2P] Generating self-signed certificate...");

        // 生成密钥对
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        keyPairGen.initialize(KEY_SIZE);
        KeyPair keyPair = keyPairGen.generateKeyPair();

        // 证书信息
        X500Name subject = new X500Name("CN=CodeCraft P2P, O=CodeCraft, C=CN");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + P2pConstants.CERT_VALIDITY_DAYS * 86400000L);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider("BC")
                .build(keyPair.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(signer);
        certificate = new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certHolder);
        privateKey = keyPair.getPrivate();

        // 保存到 KeyStore
        keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("p2p", keyPair.getPrivate(), "codecraft".toCharArray(),
                new X509Certificate[]{certificate});

        try (FileOutputStream fos = new FileOutputStream(keyStorePath.toFile())) {
            keyStore.store(fos, "codecraft".toCharArray());
        }

        // 计算证书指纹
        certFingerprint = computeFingerprint(certificate);
        log.info("[P2P] Self-signed certificate generated: {}", certFingerprint);
    }

    /**
     * 加载已有 KeyStore
     */
    private void loadKeyStore() throws Exception {
        keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keyStorePath.toFile())) {
            keyStore.load(fis, "codecraft".toCharArray());
        }
        certificate = (X509Certificate) keyStore.getCertificate("p2p");
        privateKey = (PrivateKey) keyStore.getKey("p2p", "codecraft".toCharArray());
        certFingerprint = computeFingerprint(certificate);
        log.info("[P2P] Loaded existing certificate: {}", certFingerprint);
    }

    /**
     * 初始化 Netty Server SslContext（用于 P2pServer 监听端）
     */
    private void initSslContext() throws Exception {
        serverSslContext = SslContextBuilder.forServer(privateKey, certificate)
                .protocols("TLSv1.3")
                .build();
    }

    /**
     * 计算证书 SHA-256 指纹（用于 TOFU 校验）
     */
    public static String computeFingerprint(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] der = cert.getEncoded();
            byte[] digest = md.digest(der);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0 && i % 2 == 0) sb.append(':');
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute cert fingerprint", e);
        }
    }

    /**
     * 获取 Server 端 Netty SslContext（用于 P2pServer pipeline）
     */
    public SslContext getServerSslContext() {
        return serverSslContext;
    }

    /**
     * 创建针对特定指纹的 Netty SslContext（客户端用，TOFU 模型校验服务端证书指纹）
     */
    public static SslContext createTrustByFingerprintContext(String expectedFingerprint) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        // 使用自定义 TrustManager 校验指纹
        tmf.init((KeyStore) null);

        return SslContextBuilder.forClient()
                .trustManager(new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                        // 客户端不验证
                    }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                        if (certs == null || certs.length == 0) {
                            throw new CertificateException("No certificate provided");
                        }
                        String actualFingerprint = computeFingerprint(certs[0]).replace(":", "");
                        String expectedClean = expectedFingerprint.replace(":", "");
                        if (!actualFingerprint.equalsIgnoreCase(expectedClean)) {
                            throw new CertificateException(
                                    "Certificate fingerprint mismatch. Expected: " + expectedFingerprint
                                    + ", Actual: " + actualFingerprint);
                        }
                    }
                })
                .protocols("TLSv1.3")
                .build();
    }

    public String getCertFingerprint() {
        return certFingerprint;
    }
}
