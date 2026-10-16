package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.RouteNotFoundError;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

class ViewAccessTest extends UiTest {
    private java.util.List<String> navLabels() {
        return find(SideNavItem.class).all().stream().map(SideNavItem::getLabel).toList();
    }

    @Test
    @WithAnonymousUser
    void anonymousVisitorsAreSentToTheLoginPage() {
        navigate("admin", LoginView.class);
        navigate("player", LoginView.class);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
    void adminsSeeTheAdminPages() {
        navigate(AdminHomeView.class);
        assertThat(find(H1.class).withText("Admin Home").exists()).isTrue();
        assertThat(navLabels()).containsExactly("Admin home", "Players and Benefits", "Manage events", "Officials");

        navigate(AdminPlayersView.class);
        navigate(AdminEventsView.class);
        navigate(AdminOfficialsView.class);
    }

    @Test
    @WithMockUser(username = "ref", roles = "OFFICIAL")
    void officialsSeeOnlyTheirHome() {
        navigate(OfficialHomeView.class);
        assertThat(navLabels()).containsExactly("Official home");
        navigate("admin", RouteNotFoundError.class);
        navigate("player", RouteNotFoundError.class);
    }

    @Test
    @WithMockUser(username = "ada", roles = "USER")
    void playersSeeThePlayerPagesButNotTheAdminOnes() {
        data.account("ada", "USER");
        navigate(PlayerHomeView.class);
        assertThat(navLabels()).containsExactly("Player home", "My Matches", "Event Draws", "My Profile");
        navigate(PlayerMatchesView.class);
        navigate(PlayerDrawsView.class);
        navigate(PlayerProfileView.class);

        navigate("admin", RouteNotFoundError.class);
        navigate("admin/players", RouteNotFoundError.class);
        navigate("official", RouteNotFoundError.class);
    }
}
