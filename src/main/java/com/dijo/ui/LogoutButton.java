package com.dijo.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.spring.security.AuthenticationContext;

/** Ends the session and returns to the login page. */
class LogoutButton extends Button {
    LogoutButton(AuthenticationContext authentication) {
        super("Log out", e -> authentication.logout());
        addThemeVariants(ButtonVariant.TERTIARY);
    }
}
