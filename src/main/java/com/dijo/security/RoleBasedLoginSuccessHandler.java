package com.dijo.security;

import com.vaadin.flow.spring.security.VaadinSavedRequestAwareAuthenticationSuccessHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

public class RoleBasedLoginSuccessHandler
        extends VaadinSavedRequestAwareAuthenticationSuccessHandler {

    public RoleBasedLoginSuccessHandler() {
        // Always use the role-based destination after login.
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    protected String determineTargetUrl(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {

        if (hasRole(authentication, "ROLE_ADMIN")) {
            return "/admin";
        }

        if (hasRole(authentication, "ROLE_OFFICIAL")) {
            return "/official";
        }

        if (hasRole(authentication, "ROLE_USER")) {
            return "/player";
        }

        // An authenticated account without a supported role has no home view.
        throw new IllegalStateException(
                "User has no supported role: " + authentication.getName()
        );
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> role.equals(authority.getAuthority()));
    }
}