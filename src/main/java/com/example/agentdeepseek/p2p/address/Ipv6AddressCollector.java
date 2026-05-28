package com.example.agentdeepseek.p2p.address;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IPv6 地址采集器
 * <p>
 * 遍历本机所有网卡，筛选出可用于 P2P 直连的 IPv6 全局单播地址。
 * 优先级：公网 GUA（2000::/3）> 唯一本地地址 ULA（fc00::/7）
 * </p>
 */
public class Ipv6AddressCollector {

    private static final Logger log = LoggerFactory.getLogger(Ipv6AddressCollector.class);

    /** 默认 P2P 端口 */
    private final int port;

    /** 本机 Peer ID（SHA-256 哈希，64 字符十六进制） */
    private final String peerId;

    public Ipv6AddressCollector(int port) {
        this.port = port;
        this.peerId = generatePeerId();
    }

    // ==================== 地址采集 ====================

    /**
     * 获取本机所有可用于 P2P 的 IPv6 地址（按优先级排序）
     */
    public List<Inet6Address> collectAddresses() {
        List<Inet6Address> result = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // 跳过回环和未启用的接口
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet6Address ipv6 && isUsableAddress(ipv6)) {
                        result.add(ipv6);
                    }
                }
            }
        } catch (SocketException e) {
            log.error("[P2P] Failed to enumerate network interfaces", e);
        }

        // 按优先级排序：公网 GUA 优先
        result.sort(Comparator.comparingInt(this::addressPriority));
        return result;
    }

    /**
     * 获取最优的 IPv6 地址（优先级最高的那个）
     */
    public Optional<Inet6Address> getBestAddress() {
        List<Inet6Address> addresses = collectAddresses();
        return addresses.isEmpty() ? Optional.empty() : Optional.of(addresses.get(0));
    }

    /**
     * 获取所有地址的字符串表示（含端口）
     */
    public List<String> getAddressStrings() {
        return collectAddresses().stream()
                .map(addr -> "[" + addr.getHostAddress() + "]:" + port)
                .collect(Collectors.toList());
    }

    /**
     * 获取最优地址的连接字符串
     */
    public Optional<String> getBestAddressString() {
        return getBestAddress().map(addr -> "[" + addr.getHostAddress() + "]:" + port);
    }

    // ==================== 地址过滤 ====================

    /**
     * 判断 IPv6 地址是否可用于 P2P 直连
     */
    private boolean isUsableAddress(Inet6Address addr) {
        // 排除回环地址 (::1)
        if (addr.isLoopbackAddress()) return false;
        // 排除链路本地地址 (fe80::/10)
        if (addr.isLinkLocalAddress()) return false;
        // 排除站点本地地址 (fec0::/10，已废弃)
        if (addr.isSiteLocalAddress()) return false;
        // 排除多播地址 (ff00::/8)
        if (addr.isMulticastAddress()) return false;
        // 排除未指定地址 (::)
        if (addr.isAnyLocalAddress()) return false;
        // 排除临时地址（隐私扩展，会定期变化）
        // 注意：Java 标准库无法直接检测临时地址，此处保留所有全局地址

        return true;
    }

    /**
     * 地址优先级：数字越小优先级越高
     * 0 = 公网 GUA（2000::/3）
     * 1 = 唯一本地 ULA（fc00::/7）
     * 99 = 其他
     */
    private int addressPriority(Inet6Address addr) {
        byte[] bytes = addr.getAddress();
        int firstByte = bytes[0] & 0xFF;

        // 公网全球单播地址 2000::/3 (0x20-0x3F)
        if ((firstByte & 0xE0) == 0x20) {
            return 0;
        }
        // 唯一本地地址 fc00::/7 (0xFC-0xFD)
        if ((firstByte & 0xFE) == 0xFC) {
            return 1;
        }
        return 99;
    }

    // ==================== 可达性检测 ====================

    /**
     * 快速检测指定端口是否可达（尝试绑定）
     */
    public static boolean isPortReachable(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测本机是否有可用的 IPv6 全局地址
     */
    public boolean hasIpv6Connectivity() {
        return getBestAddress().isPresent();
    }

    // ==================== Peer ID ====================

    /**
     * 生成本机 Peer ID
     * 基于主机名 + MAC 地址的 SHA-256 哈希
     */
    private String generatePeerId() {
        try {
            String seed = getHostName() + getPrimaryMac();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 必须存在，不处理
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private String getPrimaryMac() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isLoopback() && ni.isUp()) {
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length > 0) {
                        return bytesToHex(mac);
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("[P2P] Failed to get MAC address", e);
        }
        return "00:00:00:00:00:00";
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== Getter ====================

    public int getPort() {
        return port;
    }

    public String getPeerId() {
        return peerId;
    }
}
