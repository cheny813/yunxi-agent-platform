package io.yunxi.platform.security.auth;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtVerifier HS256 验签与角色提取单元测试。
 * 通过反射注入 jwtSecret（不依赖 Spring 上下文），覆盖：有效令牌解析、无效令牌拒绝、无密钥拒绝。
 */
class JwtVerifierTest {

    private static final String SECRET = "test-secret-key-at-least-256-bits-long-enough-1234567890";
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new JwtVerifier();
        setField("jwtSecret", SECRET);
        setField("jwtHeader", "Authorization");
        setField("jwtPrefix", "Bearer ");
        setField("jwksUri", "");
        setField("issuer", "");
        invokeInit(verifier);
    }

    private void setField(String name, String value) throws Exception {
        Field f = JwtVerifier.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(verifier, value);
    }

    private void invokeInit(JwtVerifier v) throws Exception {
        var m = JwtVerifier.class.getDeclaredMethod("init");
        m.setAccessible(true);
        m.invoke(v);
    }

    private String signToken(String sub, String username, String roles) {
        return Jwts.builder()
                .subject(sub)
                .claim("username", username)
                .claim("roles", roles)
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void verifyValidTokenExtractsUserInfo() {
        String token = signToken("user-123", "alice", "ADMIN,user");
        Optional<SecurityContext.UserInfo> result = verifier.verify("Bearer " + token);
        assertTrue(result.isPresent());
        assertEquals("user-123", result.get().getUserId());
        assertEquals("alice", result.get().getUsername());
        assertArrayEquals(new String[] { "ADMIN", "user" }, result.get().getRoles());
    }

    @Test
    void verifyInvalidSignatureRejected() {
        String token = Jwts.builder()
                .subject("x")
                .signWith(Keys.hmacShaKeyFor(
                        "another-secret-key-at-least-256-bits-long-enough-0000000000".getBytes(StandardCharsets.UTF_8)))
                .compact();
        Optional<SecurityContext.UserInfo> result = verifier.verify("Bearer " + token);
        assertFalse(result.isPresent());
    }

    @Test
    void verifyMissingTokenRejected() {
        assertFalse(verifier.verify(null).isPresent());
        assertFalse(verifier.verify("").isPresent());
        assertFalse(verifier.verify("Bearer ").isPresent());
    }

    @Test
    void verificationUnavailableWithoutSecret() throws Exception {
        JwtVerifier noSecret = new JwtVerifier();
        setFieldOn(noSecret, "jwtSecret", "");
        setFieldOn(noSecret, "jwksUri", "");
        invokeInit(noSecret);
        assertFalse(noSecret.isVerificationAvailable());
        assertFalse(noSecret.verify("Bearer " + signToken("u", "u", "user")).isPresent());
    }

    private void setFieldOn(JwtVerifier v, String name, String value) throws Exception {
        Field f = JwtVerifier.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(v, value);
    }
}
