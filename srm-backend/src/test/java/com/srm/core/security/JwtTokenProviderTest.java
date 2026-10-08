package com.srm.core.security;

import com.srm.core.common.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "srm-test-jwt-secret-key-0123456789abcdefghijklmnopqrstuvwxyz";

    private JwtTokenProvider provider;
    private JwtTokenProvider providerWithDifferentSecret;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, 3_600_000L);
        providerWithDifferentSecret = new JwtTokenProvider("another-test-secret-key-9876543210abcdefghijklmnopqrstuvwxyz", 3_600_000L);
    }

    private UserPrincipal principal() {
        return new UserPrincipal(2L, "staff01", "encoded-password", "张三", Role.STAFF, true);
    }

    @Test
    void generateTokenReturnsJwtWithThreeSegments() {
        String token = provider.generateToken(principal());

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void validateTokenAcceptsFreshToken() {
        String token = provider.generateToken(principal());

        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void validateTokenRejectsExpiredToken() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1_000L);
        String token = expiredProvider.generateToken(principal());

        assertThat(provider.validateToken(token)).isFalse();
    }

    @Test
    void validateTokenRejectsTokenSignedWithDifferentSecret() {
        String token = providerWithDifferentSecret.generateToken(principal());

        assertThat(provider.validateToken(token)).isFalse();
    }

    @Test
    void validateTokenRejectsTamperedToken() {
        String token = provider.generateToken(principal());
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateTokenRejectsGarbageAndNull() {
        assertThat(provider.validateToken("not-a-jwt")).isFalse();
        assertThat(provider.validateToken("")).isFalse();
        assertThat(provider.validateToken(null)).isFalse();
    }

    @Test
    void getUsernameReturnsSubject() {
        String token = provider.generateToken(principal());

        assertThat(provider.getUsername(token)).isEqualTo("staff01");
    }

    @Test
    void getRoleReturnsEmbeddedRole() {
        String token = provider.generateToken(principal());

        assertThat(provider.getRole(token)).isEqualTo(Role.STAFF);
    }

    @Test
    void getUserIdReturnsEmbeddedId() {
        String token = provider.generateToken(principal());

        assertThat(provider.getUserId(token)).isEqualTo(2L);
    }

    @Test
    void tokenCarriesRoleOfEachRoleType() {
        for (Role role : Role.values()) {
            UserPrincipal p = new UserPrincipal(1L, "u", "pw", "名", role, true);
            String token = provider.generateToken(p);

            assertThat(provider.validateToken(token)).isTrue();
            assertThat(provider.getRole(token)).isEqualTo(role);
        }
    }
}
