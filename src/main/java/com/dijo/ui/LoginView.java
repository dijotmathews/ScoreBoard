package com.dijo.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/** Branded sign-in card, shown outside the app layout. */
@Route(value = "login", autoLayout = false)
@PageTitle("Sign in · ScoreBoard")
@AnonymousAllowed
public class LoginView extends VerticalLayout
        implements BeforeEnterObserver {

    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        addClassName("login-page");
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        Icon logo = VaadinIcon.VAADIN_H.create();
        logo.addClassName("login-logo");
        H1 title = new H1("ScoreBoard");
        title.addClassName("login-title");
        Span tagline = new Span("Draws, matches and benefits for your tournament");
        tagline.addClassName("muted");
        Div brand = new Div(logo, title, tagline);
        brand.addClassName("login-brand");

        LoginI18n i18n = LoginI18n.createDefault();
        i18n.getForm().setTitle("Sign in");
        i18n.getForm().setSubmit("Sign in");
        i18n.getErrorMessage().setTitle("Incorrect username or password");
        i18n.getErrorMessage().setMessage("Check your details and try again.");
        loginForm.setI18n(i18n);
        loginForm.setAction("login");
        loginForm.setForgotPasswordButtonVisible(false);

        Span help = new Span("Need an account? Contact the tournament organisers.");
        help.addClassName("muted");
        help.addClassName("login-help");

        Div card = new Div(brand, loginForm, help);
        card.addClassName("login-card");
        add(card);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        boolean loginFailed = event.getLocation()
                .getQueryParameters()
                .getParameters()
                .containsKey("error");

        loginForm.setError(loginFailed);
    }
}
