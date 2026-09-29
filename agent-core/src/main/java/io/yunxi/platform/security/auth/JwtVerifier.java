package io.yunxi.platform.security.auth;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 验签器（边缘鉴权核心）。
 *
 * <p>支持两种签名算法：
 * <ul>
 *   <li>HS256：对称密钥 {@code jwt.secret}（≥256bit）。</li>
 *   <li>RS256：非对称，从 {@code jwt.jwks-uri} 拉取 JWK 集合并按 {@code kid} 解析公钥（适合对接 IdP）。</li>
 * </ul>
 * 验签成功后提取 {@code sub}（用户标识）、{@code username} 声明、{@code roles}/{@code scope}/{@code authorities}
 * 声明为角色数组，封装为 {@link SecurityContext.UserInfo} 返回。</p>
 *
 * <p>任何验签失败、过期、缺失密钥均返回 {@link Optional#empty()}，由调用方决定拒绝或降级，
 * 不在本类内直接写响应，保持无副作用。</p>
 */
@Slf4j
@Component
public class JwtVerifier {

    /** HS256 对称密钥（可选）。 */
    @Value("${jwt.secret:}")
    private String jwtSecret;

    /** RS256 JWKS 地址（可选，优先于 secret）。 */
    @Value("${jwt.jwks-uri:}")
    private String jwksUri;

    /** RS256 期望 issuer（可选，用于校验 iss 声明）。 */
    @Value("${jwt.issuer:}")
    private String issuer;

    /** JWT 头名（默认 Authorization）。 */
    @Value("${jwt.header:Authorization}")
    private String jwtHeader;

    /** JWT 前缀（默认 "Bearer "）。 */
    @Value("${jwt.prefix:Bearer }")
    private String jwtPrefix;

    /** JWKS 缓存 TTL（默认 10 分钟）。 */
    @Value("${jwt.jwks-cache-seconds:600}")
    private long jwksCacheSeconds;

    private SecretKey hmacKey;
    private volatile CachedJwks cachedJwks;

    @PostConstruct
    void init() {
        if (jwtSecret != null && !jwtSecret.isEmpty()) {
            hmacKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        }
        boolean hs256 = hmacKey != null;
        boolean rs256 = jwksUri != null && !jwksUri.isEmpty();
        if (hs256 && rs256) {
            log.info("JWT 验签已启用：HS256(secret) 与 RS256(jwks-uri) 均配置，优先使用 RS256");
        } else if (rs256) {
            log.info("JWT 验签已启用：RS256(jwks-uri={})", jwksUri);
        } else if (hs256) {
            log.info("JWT 验签已启用：HS256(secret)");
        } else {
            log.warn("JWT 验签未配置（jwt.secret 与 jwt.jwks-uri 均为空）：Bearer 令牌将不被接受，"
                    + "边缘鉴权需依赖内部令牌或降级为 dev-trust 模式");
        }
    }

    /**
     * 从 Authorization 头提取并验证 JWT。
     *
     * @param authHeader 原始请求头值（可能为 null）
     * @return 验证成功后的用户信息；失败/缺失返回 empty
     */
    public Optional<SecurityContext.UserInfo> verify(String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            return Optional.empty();
        }
        String token = authHeader;
        if (jwtPrefix != null && !jwtPrefix.isEmpty() && authHeader.startsWith(jwtPrefix)) {
            token = authHeader.substring(jwtPrefix.length()).trim();
        }
        if (token.isEmpty()) {
            return Optional.empty();
        }

        try {
            Claims claims = parseClaims(token);
            if (claims == null) {
                return Optional.empty();
            }
            String userId = claims.getSubject();
            if (userId == null) {
                userId = claims.get("userId", String.class);
            }
            if (userId == null || userId.isEmpty()) {
                log.debug("JWT 缺少 sub/userId 声明");
                return Optional.empty();
            }
            String username = claims.get("username", String.class);
            if (username == null) {
                username = userId;
            }
            String[] roles = extractRoles(claims);
            return Optional.of(new SecurityContext.UserInfo(userId, username, roles));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 验签失败: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("JWT 验签异常: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Claims parseClaims(String token) {
        if (jwksUri != null && !jwksUri.isEmpty()) {
            PublicKey key = resolveRsaKey(token);
            if (key == null) {
                return null;
            }
            var builder = Jwts.parser().verifyWith(key);
            if (issuer != null && !issuer.isEmpty()) {
                builder.requireIssuer(issuer);
            }
            return builder.build().parseSignedClaims(token).getPayload();
        }
        if (hmacKey != null) {
            var builder = Jwts.parser().verifyWith(hmacKey);
            if (issuer != null && !issuer.isEmpty()) {
                builder.requireIssuer(issuer);
            }
            return builder.build().parseSignedClaims(token).getPayload();
        }
        return null;
    }

    /**
     * 从 JWKS JSON 按 kid 解析 RSA 公钥（版本无关：用 Gson 解析标准 JWK 的 n/e 并重建 RSAPublicKey）。
     *
     * @param token 待验签 JWT（用于取 kid）
     * @return 匹配的公钥；未命中返回 null
     */
    private PublicKey resolveRsaKey(String token) {
        CachedJwks jwks = getJwks();
        if (jwks == null || jwks.json == null) {
            return null;
        }
        try {
            String kid = Jwts.parser().build().parseSignedClaims(token).getHeader().getKeyId();
            JsonObject root = new Gson().fromJson(jwks.json, JsonObject.class);
            JsonArray keys = root.getAsJsonArray("keys");
            if (keys == null) {
                return null;
            }
            for (JsonElement el : keys) {
                JsonObject k = el.getAsJsonObject();
                if (!"RSA".equals(k.get("kty").getAsString())) {
                    continue;
                }
                if (kid != null && !kid.isEmpty()
                        && k.has("kid") && !kid.equals(k.get("kid").getAsString())) {
                    continue;
                }
                byte[] n = Base64.getUrlDecoder().decode(k.get("n").getAsString());
                byte[] e = Base64.getUrlDecoder().decode(k.get("e").getAsString());
                RSAPublicKeySpec spec = new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e));
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return kf.generatePublic(spec);
            }
            log.debug("JWKS 中未找到匹配 kid={} 的 RSA 公钥", kid);
            return null;
        } catch (Exception ex) {
            log.warn("解析 JWKS 公钥失败: {}", ex.getMessage());
            return null;
        }
    }

    private CachedJwks getJwks() {
        CachedJwks current = cachedJwks;
        if (current != null && !current.isExpired()) {
            return current;
        }
        synchronized (this) {
            if (cachedJwks != null && !cachedJwks.isExpired()) {
                return cachedJwks;
            }
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(jwksUri))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null) {
                    cachedJwks = new CachedJwks(resp.body(),
                            System.currentTimeMillis() + jwksCacheSeconds * 1000L);
                    log.debug("JWKS 已刷新（{} 字节）", resp.body().length());
                } else {
                    log.warn("JWKS 拉取失败：HTTP {}", resp.statusCode());
                }
            } catch (Exception e) {
                log.warn("JWKS 拉取异常: {}", e.getMessage());
            }
            return cachedJwks;
        }
    }

    private String[] extractRoles(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) rolesObj;
            return list.toArray(new String[0]);
        }
        if (rolesObj instanceof String) {
            return ((String) rolesObj).split(",");
        }
        Object scope = claims.get("scope");
        if (scope instanceof String && !((String) scope).isEmpty()) {
            return ((String) scope).split("\\s+");
        }
        Object authorities = claims.get("authorities");
        if (authorities instanceof String && !((String) authorities).isEmpty()) {
            return ((String) authorities).split(",");
        }
        return new String[] { "user" };
    }

    /** 是否存在可用的 JWT 验签能力（用于 enforce 模式下的安全兜底判断）。 */
    public boolean isVerificationAvailable() {
        return (jwtSecret != null && !jwtSecret.isEmpty())
                || (jwksUri != null && !jwksUri.isEmpty());
    }

    /** JWKS 缓存载体。 */
    private static final class CachedJwks {
        private final String json;
        private final long expireAt;

        CachedJwks(String json, long expireAt) {
            this.json = json;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
