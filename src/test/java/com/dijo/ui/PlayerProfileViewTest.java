package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;

/** My Profile: players add, change and remove their own contact number. */
@WithMockUser(username = "ada", roles = "USER")
class PlayerProfileViewTest extends UiTest {
    @BeforeEach
    void setUp() {
        data.account("ada", "USER");
    }

    private TextField number() {
        return find(TextField.class).withLabel("Your number").single();
    }

    private String stored() {
        return jdbc.queryForObject("SELECT phone FROM users WHERE username = 'ada'", String.class);
    }

    private void save(String value) {
        test(number()).setValue(value);
        test(find(Button.class).withText("Save").single()).click();
    }

    @Test
    void addingChangingAndRemovingTheContactNumber() {
        navigate(PlayerProfileView.class);
        assertThat(number().getValue()).isEmpty();
        assertThat(find(Badge.class).withText("Not added").exists()).isTrue();

        save(" 98765   43210 ");
        assertThat(stored()).isEqualTo("98765 43210");
        assertThat(number().getValue()).isEqualTo("98765 43210");
        assertThat(find(Badge.class).withText("Added").exists()).isTrue();
        assertThat(notifications()).contains("Contact number saved");

        save("not a number");
        assertThat(stored()).isEqualTo("98765 43210");
        assertThat(notifications()).anyMatch(n -> n.startsWith("Contact number must have"));

        save("");
        assertThat(stored()).isNull();
        assertThat(find(Badge.class).withText("Not added").exists()).isTrue();

        // Shown again on the next visit.
        save("+91 90000 11111");
        navigate(PlayerProfileView.class);
        assertThat(number().getValue()).isEqualTo("+91 90000 11111");
    }
}
