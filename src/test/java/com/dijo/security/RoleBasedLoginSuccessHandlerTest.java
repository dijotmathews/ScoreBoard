package com.dijo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;

class RoleBasedLoginSuccessHandlerTest {
    private final RoleBasedLoginSuccessHandler handler = new RoleBasedLoginSuccessHandler();

    private String targetFor(String... authorities) {
        return handler.determineTargetUrl(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new TestingAuthenticationToken("someone", null, authorities));
    }

    @Test
    void sendsEachRoleToItsHome() {
        assertThat(targetFor("ROLE_ADMIN", "ROLE_USER")).isEqualTo("/admin");
        assertThat(targetFor("ROLE_OFFICIAL")).isEqualTo("/official");
        assertThat(targetFor("ROLE_USER")).isEqualTo("/player");
    }

    @Test
    void refusesAccountsWithoutASupportedRole() {
        assertThatThrownBy(() -> targetFor("ROLE_GUEST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("someone");
    }
}
