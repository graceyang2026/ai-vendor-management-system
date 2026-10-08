package com.srm.core.security;

import com.srm.core.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private JwtTokenProvider tokenProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        tokenProvider = mock(JwtTokenProvider.class);
        filter = new JwtAuthenticationFilter(tokenProvider);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithHeader(String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/suppliers");
        if (headerValue != null) {
            request.addHeader("Authorization", headerValue);
        }
        return request;
    }

    @Test
    void missingAuthorizationHeaderPassesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = requestWithHeader(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerHeaderPassesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = requestWithHeader("Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenPassesThroughWithoutAuthenticationAndDoesNotThrow() throws Exception {
        MockHttpServletRequest request = requestWithHeader("Bearer invalid.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(tokenProvider.validateToken("invalid.token.here")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validTokenSetsAuthenticationWithUserPrincipal() throws Exception {
        MockHttpServletRequest request = requestWithHeader("Bearer valid.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(tokenProvider.validateToken("valid.token.here")).thenReturn(true);
        when(tokenProvider.getUsername("valid.token.here")).thenReturn("staff01");
        when(tokenProvider.getRole("valid.token.here")).thenReturn(Role.STAFF);
        when(tokenProvider.getUserId("valid.token.here")).thenReturn(2L);

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(UserPrincipal.class);
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        assertThat(principal.getUsername()).isEqualTo("staff01");
        assertThat(principal.getId()).isEqualTo(2L);
        assertThat(principal.getRole()).isEqualTo(Role.STAFF);
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_STAFF");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void emptyBearerTokenFallsThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = requestWithHeader("Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(tokenProvider.validateToken("")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }
}
