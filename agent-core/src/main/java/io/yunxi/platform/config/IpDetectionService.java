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

    private Set<String> parseIpCandidates(String headerValue) {
        Set<String> candidates = new LinkedHashSet<>();
        for (String ip : headerValue.split(",")) {
            String trimmedIp = ip.trim();
            if (isValidIp(trimmedIp) && !isPrivateIp(trimmedIp)) candidates.add(trimmedIp);
        }
        return candidates;
    }

    private String selectBestIp(Set<String> candidates) {
        if (candidates.isEmpty()) return "0.0.0.0";
        for (String candidate : candidates) { if (!isPrivateIp(candidate)) return candidate; }
        return candidates.iterator().next();
    }

    public boolean isValidIp(String ip) {
        if (!StringUtils.hasText(ip)) return false;
        return IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches()
                || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
    }

    public boolean isPrivateIp(String ip) {
        if (!isValidIp(ip)) return false;
        if (IPV4_PATTERN.matcher(ip).matches()) {
            for (String range : PRIVATE_IP_RANGES) { if (ip.startsWith(range)) return true; }
            return false;
        }
        return ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    public String getNetworkType(String ip) {
        if (!isValidIp(ip)) return "INVALID";
        return isPrivateIp(ip) ? "PRIVATE" : "PUBLIC";
    }

    public IpGeoInfo getGeoInfo(String ip) {
        IpGeoInfo geoInfo = new IpGeoInfo();
        geoInfo.setIp(ip);
        if (isValidIp(ip)) {
            if (isPrivateIp(ip)) { geoInfo.setCountry("Internal"); geoInfo.setRegion("Private Network"); geoInfo.setCity("Local"); }
            else { geoInfo.setCountry("Unknown"); geoInfo.setRegion("Unknown"); geoInfo.setCity("Unknown"); }
        }
        return geoInfo;
    }

    public boolean isFromTrustedProxy(String ip, String[] trustedProxyIps) {
        return isValidIp(ip) && trustedProxyIps != null && Arrays.asList(trustedProxyIps).contains(ip);
    }

    public IpStats getIpStats(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        IpStats stats = new IpStats();
        stats.setIp(clientIp); stats.setNetworkType(getNetworkType(clientIp));
        stats.setGeoInfo(getGeoInfo(clientIp)); stats.setRequestTime(System.currentTimeMillis());
        stats.setUserAgent(request.getHeader("User-Agent"));
        return stats;
    }

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
