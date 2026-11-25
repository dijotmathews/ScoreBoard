package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.benefits.PlayerBenefitsService;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** Player Home highlights what the player still has to do: pay and add a contact number. */
@WithMockUser(username = "ada", roles = "USER")
class PlayerHomeViewTest extends UiTest {
    @Autowired
    PlayerBenefitsService benefits;

    @BeforeEach
    void setUp() {
        data.account("admin", "ADMIN", "USER");
        asAdmin(() -> benefits.updateSettings(50000, 0, "None"));
    }

    private List<String> toDo() {
        return find(Div.class).all().stream().filter(d -> d.hasClassName("todo-item"))
                .map(d -> d.getElement().getTextRecursively()).toList();
    }

    @Test
    void highlightsPendingPaymentAndMissingContactNumber() {
        data.account("ada", "USER");
        navigate(PlayerHomeView.class);

        assertThat(find(Card.class).all()).anyMatch(c -> c.hasClassName("todo-card"));
        assertThat(toDo()).containsExactly(
                "Payment pendingPay the entry fee of ₹500.00 to the organisers. You are signed up once they record it.",
                "Contact number missingAdd a mobile number so the organisers can reach you about your matches."
                        + "Add contact number");

        click("Add contact number");
        assertThat(find(PlayerProfileView.class).exists()).isTrue();
    }

    @Test
    void showsNothingToDoOnceSignedUpWithANumber() {
        data.signedUpPlayer("ada");
        jdbc.update("UPDATE users SET phone = '98765 43210' WHERE username = 'ada'");
        navigate(PlayerHomeView.class);

        assertThat(toDo()).isEmpty();
        assertThat(find(Card.class).all()).noneMatch(c -> c.hasClassName("todo-card"));
    }
}
