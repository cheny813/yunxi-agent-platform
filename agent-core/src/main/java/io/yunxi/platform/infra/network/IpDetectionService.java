package io.yunxi.platform.infra.network;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * IP检测服务 - 增强版本
 * 提供准确的客户端IP检测功能，支持复杂网络环境
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ip-detection.enabled", havingValue = "true", matchIfMissing = true)
public class IpDetectionService {

    // IP头字段优先级（从最可信到最不可信）
    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", // 标准代理头字段
            "X-Real-IP", // Nginx/LB专用头字段
            "Proxy-Client-IP", // Apache代理
            "WL-Proxy-Client-IP", // WebLogic代理
            "HTTP_X_FORWARDED_FOR", // 其他格式
            "HTTP_X_REAL_IP", // 其他格式
            "HTTP_CLIENT_IP", // 客户端IP头
            "HTTP_X_CLUSTER_CLIENT_IP" // 集群客户端IP
    };

    // IP验证正则表达式
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");

    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::1$");

    // 私有网络和保留IP段
    private static final String[] PRIVATE_IP_RANGES = {
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127.", "169.254."
    };

    /**
     * 获取客户端真实IP地址
     * 支持多级代理、云环境、容器网络等各种复杂场景
     */
    public String getClientIp(HttpServletRequest request) {
        Set<String> ipCandidates = new LinkedHashSet<>();

        // 1. 按优先级检查所有可能的IP头字段
        for (String header : IP_HEADERS) {
            String headerValue = request.getHeader(header);
            if (StringUtils.hasText(headerValue)) {
                ipCandidates.addAll(parseIpCandidates(headerValue));
                log.debug("在头字段[{}]中发现IP候选: {}", header, headerValue);
            }
        }

        // 2. 如果没有找到有效IP，回退到远程地址
        if (ipCandidates.isEmpty()) {
            String remoteAddr = request.getRemoteAddr();
            if (isValidIp(remoteAddr)) {
                ipCandidates.add(remoteAddr);
                log.debug("使用远程地址作为候选IP: {}", remoteAddr);
            }
        }

        // 3. 根据优先级策略选择最合适的IP
        String selectedIp = selectBestIp(ipCandidates);

        if (log.isDebugEnabled()) {
            log.debug("IP检测结果: 候选IP列表={}, 最终选择={}, 网络类型={}",
                    ipCandidates, selectedIp, getNetworkType(selectedIp));
        }

        return selectedIp;
    }

    /**
     * 解析IP候选列表
     */
    private Set<String> parseIpCandidates(String headerValue) {
        Set<String> candidates = new LinkedHashSet<>();

        // 处理逗号分隔的IP列表（常见于多级代理）
        String[] ips = headerValue.split(",");

        for (String ip : ips) {
            String trimmedIp = ip.trim();
            if (isValidIp(trimmedIp) && !isPrivateIp(trimmedIp)) {
                candidates.add(trimmedIp);
            }
        }

        return candidates;
    }

    /**
     * 根据策略选择最佳的IP地址
     */
    private String selectBestIp(Set<String> candidates) {
        if (candidates.isEmpty()) {
            return "0.0.0.0"; // 默认回退地址
        }

        // 策略1: 优先选择非私有IP（更可能是真实客户端IP）
        for (String candidate : candidates) {
            if (!isPrivateIp(candidate)) {
                return candidate;
            }
        }

        // 策略2: 如果都是私有IP，选择第一个有效的
        return candidates.iterator().next();
    }

    /**
     * 验证IP地址格式是否正确
     */
    public boolean isValidIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }

        // 支持IPv4和IPv6
        return IPV4_PATTERN.matcher(ip).matches() ||
                IPV6_PATTERN.matcher(ip).matches() ||
                "0:0:0:0:0:0:0:1".equals(ip) || // IPv6 localhost
                "::1".equals(ip);
    }

    /**
     * 检查是否为私有IP地址
     */
    public boolean isPrivateIp(String ip) {
        if (!isValidIp(ip)) {
            return false;
        }

        // 检查IPv4私有地址段
        if (IPV4_PATTERN.matcher(ip).matches()) {
            for (String range : PRIVATE_IP_RANGES) {
                if (ip.startsWith(range)) {
                    return true;
                }
            }
            return false;
        }

        // IPv6本地地址
        return ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    /**
     * 获取网络类型
     */
    public String getNetworkType(String ip) {
        if (!isValidIp(ip)) {
            return "INVALID";
        }

        if (isPrivateIp(ip)) {
            return "PRIVATE";
        }

        return "PUBLIC";
    }

    /**
     * 获取IP地址的地理信息（简化版本）
     * 实际项目中可以集成第三方IP地理信息服务
     */
    public IpGeoInfo getGeoInfo(String ip) {
        IpGeoInfo geoInfo = new IpGeoInfo();
        geoInfo.setIp(ip);

        if (isValidIp(ip)) {
            if (isPrivateIp(ip)) {
                geoInfo.setCountry("Internal");
                geoInfo.setRegion("Private Network");
                geoInfo.setCity("Local");
            } else {
                // 简化逻辑：根据IP特征猜测地理信息
                // 实际项目中应该使用专业的IP地理数据库
                geoInfo.setCountry("Unknown");
                geoInfo.setRegion("Unknown");
                geoInfo.setCity("Unknown");
            }
        }

        return geoInfo;
    }

    /**
     * 验证IP地址是否来自可信的代理服务器
     */
    public boolean isFromTrustedProxy(String ip, String[] trustedProxyIps) {
        if (!isValidIp(ip) || trustedProxyIps == null) {
            return false;
        }

        return Arrays.asList(trustedProxyIps).contains(ip);
    }

    /**
     * 统计IP相关信息（用于限流和监控）
     */
    public IpStats getIpStats(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        IpStats stats = new IpStats();
        stats.setIp(clientIp);
        stats.setNetworkType(getNetworkType(clientIp));
        stats.setGeoInfo(getGeoInfo(clientIp));
        stats.setRequestTime(System.currentTimeMillis());
        stats.setUserAgent(request.getHeader("User-Agent"));

        return stats;
    }

    /**
     * IP地理信息类
     */
    public static class IpGeoInfo {
        private String ip;
        private String country;
        private String region;
        private String city;
        private double latitude;
        private double longitude;

        // getter and setter methods
        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public double getLatitude() {
            return latitude;
        }

        public void setLatitude(double latitude) {
            this.latitude = latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public void setLongitude(double longitude) {
            this.longitude = longitude;
        }
    }

    /**
     * IP统计信息类
     */
    public static class IpStats {
        private String ip;
        private String networkType;
        private IpGeoInfo geoInfo;
        private long requestTime;
        private String userAgent;
        private int requestCount;

        // getter and setter methods
        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getNetworkType() {
            return networkType;
        }

        public void setNetworkType(String networkType) {
            this.networkType = networkType;
        }

        public IpGeoInfo getGeoInfo() {
            return geoInfo;
        }

        public void setGeoInfo(IpGeoInfo geoInfo) {
            this.geoInfo = geoInfo;
        }

        public long getRequestTime() {
            return requestTime;
        }

        public void setRequestTime(long requestTime) {
            this.requestTime = requestTime;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public int getRequestCount() {
            return requestCount;
        }

        public void setRequestCount(int requestCount) {
            this.requestCount = requestCount;
        }
    }
}