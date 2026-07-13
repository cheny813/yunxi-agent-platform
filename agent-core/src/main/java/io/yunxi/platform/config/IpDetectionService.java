package io.yunxi.platform.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * IP检测服务 - 增强版本
 * 提供准确的客户端IP检测功能，支持复杂网络环境
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ip-detection.enabled", havingValue = "true", matchIfMissing = true)
public class IpDetectionService {

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR", "HTTP_X_REAL_IP", "HTTP_CLIENT_IP", "HTTP_X_CLUSTER_CLIENT_IP"
    };

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    private static final Pattern IPV6_PATTERN = Pattern.compile("^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^::1$");

    private static final String[] PRIVATE_IP_RANGES = {
            "10.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.", "172.21.", "172.22.", "172.23.",
            "172.24.", "172.25.", "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
            "192.168.", "127.", "169.254."
    };

    /**
     * 从 HTTP 请求中提取客户端真实 IP。
     * <p>依次读取常见代理请求头，解析并过滤私有地址，若无候选则回退到远程地址。</p>
     *
     * @param request 当前 HTTP 请求
     * @return 解析得到的最佳客户端 IP，无法解析时返回 {@code 0.0.0.0}
     */
    public String getClientIp(HttpServletRequest request) {
        Set<String> ipCandidates = new LinkedHashSet<>();
        for (String header : IP_HEADERS) {
            String headerValue = request.getHeader(header);
            if (StringUtils.hasText(headerValue)) {
                ipCandidates.addAll(parseIpCandidates(headerValue));
            }
        }
        if (ipCandidates.isEmpty()) {
            String remoteAddr = request.getRemoteAddr();
            if (isValidIp(remoteAddr)) ipCandidates.add(remoteAddr);
        }
        return selectBestIp(ipCandidates);
    }

    /**
     * 解析代理请求头中的 IP 候选列表，过滤非法与私有地址。
     *
     * @param headerValue 单个请求头的原始值（可能含多个逗号分隔的 IP）
     * @return 合法且非私有的 IP 候选集合（保持出现顺序）
     */
    private Set<String> parseIpCandidates(String headerValue) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String ip : headerValue.split(",")) {
            String trimmedIp = ip.trim();
            if (isValidIp(trimmedIp) && !isPrivateIp(trimmedIp)) candidates.add(trimmedIp);
        }
        return candidates;
    }

    /**
     * 从候选集合中选择最佳（非私有）IP。
     *
     * @param candidates IP 候选集合
     * @return 首个非私有 IP；若均为私有则返回首个候选；空集合返回 {@code 0.0.0.0}
     */
    private String selectBestIp(Set<String> candidates) {
        if (candidates.isEmpty()) return "0.0.0.0";
        for (String candidate : candidates) { if (!isPrivateIp(candidate)) return candidate; }
        return candidates.iterator().next();
    }

    /**
     * 判断字符串是否为合法 IPv4/IPv6 地址（含本地回环）。
     *
     * @param ip 待校验的 IP 字符串
     * @return 合法时返回 true，空或格式不符时返回 false
     */
    public boolean isValidIp(String ip) {
        if (!StringUtils.hasText(ip)) return false;
        return IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches()
                || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }

    /**
     * 判断 IP 是否属于私有/内网地址段。
     *
     * @param ip 待判断的 IP 字符串
     * @return 私有地址返回 true；非法 IP 或公网地址返回 false
     */
    public boolean isPrivateIp(String ip) {
        if (!isValidIp(ip)) return false;
        if (IPV4_PATTERN.matcher(ip).matches()) {
            for (String range : PRIVATE_IP_RANGES) { if (ip.startsWith(range)) return true; }
            return false;
        }
        return ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    /**
     * 返回 IP 的网络类型分类。
     *
     * @param ip 待分类的 IP 字符串
     * @return {@code PRIVATE}/{@code PUBLIC}/{@code INVALID}
     */
    public String getNetworkType(String ip) {
        if (!isValidIp(ip)) return "INVALID";
        return isPrivateIp(ip) ? "PRIVATE" : "PUBLIC";
    }

    /**
     * 根据 IP 构造地理信息（私有地址归类为内网，公网地址标记为未知）。
     *
     * @param ip 客户端 IP
     * @return 填充了国家/地区/城市等字段的地理信息对象
     */
    public IpGeoInfo getGeoInfo(String ip) {
        IpGeoInfo geoInfo = new IpGeoInfo();
        geoInfo.setIp(ip);
        if (isValidIp(ip)) {
            if (isPrivateIp(ip)) { geoInfo.setCountry("Internal"); geoInfo.setRegion("Private Network"); geoInfo.setCity("Local"); }
            else { geoInfo.setCountry("Unknown"); geoInfo.setRegion("Unknown"); geoInfo.setCity("Unknown"); }
        }
        return geoInfo;
    }

    /**
     * 判断 IP 是否来自受信任的代理地址列表。
     *
     * @param ip               待校验的 IP
     * @param trustedProxyIps  受信任代理 IP 数组
     * @return 合法且命中信任列表时返回 true
     */
    public boolean isFromTrustedProxy(String ip, String[] trustedProxyIps) {
        return isValidIp(ip) && trustedProxyIps != null && Arrays.asList(trustedProxyIps).contains(ip);
    }

    /**
     * 汇总单次请求的 IP 统计信息（IP、网络类型、地理信息、UA、时间戳）。
     *
     * @param request 当前 HTTP 请求
     * @return IP 统计信息对象
     */
    public IpStats getIpStats(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        IpStats stats = new IpStats();
        stats.setIp(clientIp); stats.setNetworkType(getNetworkType(clientIp));
        stats.setGeoInfo(getGeoInfo(clientIp)); stats.setRequestTime(System.currentTimeMillis());
        stats.setUserAgent(request.getHeader("User-Agent"));
        return stats;
    }

    /**
     * IP 地理信息，承载国家、地区、城市及经纬度等位置字段。
     */
    public static class IpGeoInfo {
        private String ip; private String country; private String region; private String city;
        private double latitude; private double longitude;
        public String getIp() { return ip; } public void setIp(String ip) { this.ip = ip; }
        public String getCountry() { return country; } public void setCountry(String country) { this.country = country; }
        public String getRegion() { return region; } public void setRegion(String region) { this.region = region; }
        public String getCity() { return city; } public void setCity(String city) { this.city = city; }
        public double getLatitude() { return latitude; } public void setLatitude(double latitude) { this.latitude = latitude; }
        public double getLongitude() { return longitude; } public void setLongitude(double longitude) { this.longitude = longitude; }
    }

    /**
     * IP 统计信息，记录请求来源 IP、网络类型、地理信息、UA 及请求时间等维度。
     */
    public static class IpStats {
        private String ip; private String networkType; private IpGeoInfo geoInfo;
        private long requestTime; private String userAgent; private int requestCount;
        public String getIp() { return ip; } public void setIp(String ip) { this.ip = ip; }
        public String getNetworkType() { return networkType; } public void setNetworkType(String networkType) { this.networkType = networkType; }
        public IpGeoInfo getGeoInfo() { return geoInfo; } public void setGeoInfo(IpGeoInfo geoInfo) { this.geoInfo = geoInfo; }
        public long getRequestTime() { return requestTime; } public void setRequestTime(long requestTime) { this.requestTime = requestTime; }
        public String getUserAgent() { return userAgent; } public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public int getRequestCount() { return requestCount; } public void setRequestCount(int requestCount) { this.requestCount = requestCount; }
    }
}
