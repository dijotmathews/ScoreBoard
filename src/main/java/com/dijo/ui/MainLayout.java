package com.dijo.ui;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;

/** App shell for signed-in users: side navigation for the user's role, and log out. */
@Layout
@PermitAll
public class MainLayout extends AppLayout {

    public MainLayout(AuthenticationContext authentication) {
        Icon logo = VaadinIcon.VAADIN_H.create();
        logo.getStyle().set("margin-inline-start", "var(--vaadin-gap-m, 12px)")
                .set("color", "var(--aura-accent-color)");
        H1 title = new H1("ScoreBoard");
        title.getStyle().set("font-size", "1.125rem").set("margin", "0 0 0 8px");
        DrawerToggle toggle = new DrawerToggle();
        toggle.getStyle().set("margin-inline", "auto var(--vaadin-gap-s, 8px)");  // push to the right
        addToNavbar(logo, title, toggle);

        SideNav nav = new SideNav();
        if (authentication.hasAuthority("ROLE_ADMIN")) {
            nav.addItem(new SideNavItem("Admin home", AdminHomeView.class, VaadinIcon.HOME.create()),
                    new SideNavItem("Players and Benefits", AdminPlayersView.class, VaadinIcon.USERS.create()),
                    new SideNavItem("Manage events", AdminEventsView.class, VaadinIcon.TROPHY.create()));
        } else if (authentication.hasAuthority("ROLE_OFFICIAL")) {
            nav.addItem(new SideNavItem("Official home", OfficialHomeView.class, VaadinIcon.HOME.create()));
        } else if (authentication.hasAuthority("ROLE_USER")) {
            nav.addItem(new SideNavItem("Player home", PlayerHomeView.class, VaadinIcon.HOME.create()),
                    new SideNavItem("My Matches", PlayerMatchesView.class, VaadinIcon.FLAG.create()),
                    new SideNavItem("Event Draws", PlayerDrawsView.class, VaadinIcon.SITEMAP.create()),
                    new SideNavItem("My Profile", PlayerProfileView.class, VaadinIcon.USER.create()));
        }

        Span user = new Span("Signed in as " + authentication.getPrincipalName().orElse(""));
        user.getStyle().set("font-size", "0.875rem").set("opacity", "0.75");
        LogoutButton logout = new LogoutButton(authentication);
        logout.setIcon(VaadinIcon.SIGN_OUT.create());

        Scroller scroller = new Scroller(nav);
        VerticalLayout drawer = new VerticalLayout(scroller, user, logout);
        drawer.setSizeFull();
        drawer.setFlexGrow(1, scroller);
        addToDrawer(drawer);
    }
}
