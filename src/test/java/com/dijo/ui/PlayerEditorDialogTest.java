package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.dijo.benefits.PlayerBenefitsService;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

/** The admin's player dialog on Players and Benefits: account, sign-up and benefits. */
@WithMockUser(username = "admin", roles = {"ADMIN", "USER"})
class PlayerEditorDialogTest extends UiTest {
    @Autowired
    PlayerBenefitsService benefits;

    @BeforeEach
    void setUp() {
        data.account("admin", "ADMIN", "USER");
        data.account("ada", "USER");
    }

    private void feeOf500WithTwoCoupons() {
        benefits.updateSettings(50000, 2, "Refreshment");
    }

    private void openAda() {
        navigate(AdminPlayersView.class);
        test(find(Grid.class).single()).clickRow(0);
    }

    private boolean badge(String text) {
        return find(Badge.class).withText(text).exists();
    }

    private String passwordOf(String username) {
        return jdbc.queryForObject("SELECT password FROM users WHERE username = ?", String.class, username);
    }

    private void type(String label, String value) {
        test(find(TextField.class).withLabel(label).single()).setValue(value);
    }

    @Test
    void withoutAnEntryFeePaymentsCannotBeRecorded() {
        openAda();
        assertThat(badge("Not paid")).isTrue();
        assertThat(find(Button.class).withTextContaining("Record payment").exists()).isFalse();
    }

    @Test
    void recordingAPaymentSignsThePlayerUp() {
        feeOf500WithTwoCoupons();
        openAda();
        assertThat(badge("Not paid")).isTrue();

        type("Transaction ID", "UPI-1");
        click("Record payment ₹500.00");

        assertThat(notifications()).contains("Payment recorded; player signed up");
        assertThat(badge("Paid")).isTrue();
        assertThat(badge("Ready to collect")).isTrue();
        assertThat(badge("2 of 2 available")).isTrue();
        assertThat(benefits.playerBenefits("ada").paymentReference()).isEqualTo("UPI-1");
    }

    @Test
    void aMissingTransactionIdIsReported() {
        feeOf500WithTwoCoupons();
        openAda();
        click("Record payment ₹500.00");

        assertThat(notifications()).contains("Payment reference is required");
        assertThat(badge("Not paid")).isTrue();
    }

    @Test
    void handingOverTheKitAndRedeemingACoupon() {
        feeOf500WithTwoCoupons();
        benefits.confirmPayment("ada", "UPI-1", 50000);
        openAda();

        click("Hand over kit");
        assertThat(badge("Collected")).isTrue();
        assertThat(find(Button.class).withText("Hand over kit").exists()).isFalse();

        test(find(Button.class).withText("Redeem").all().getFirst()).click();
        assertThat(badge("1 of 2 available")).isTrue();
        assertThat(badge("Redeemed")).isTrue();
        assertThat(find(Button.class).withText("Redeem").all()).hasSize(1);
    }

    @Test
    void correctingAPaymentNeedsANewTransactionId() {
        feeOf500WithTwoCoupons();
        benefits.confirmPayment("ada", "UPI-1", 50000);
        openAda();
        assertThat(find(Button.class).withText("Update payment").exists()).isFalse();

        click("Correct payment");
        test(find(BigDecimalField.class).withLabel("Amount (₹)").single()).setValue(new BigDecimal("450"));
        click("Update payment");
        assertThat(notifications()).contains("Transaction ID is required");

        type("New transaction ID", "UPI-2");
        click("Update payment");

        var paid = benefits.playerBenefits("ada");
        assertThat(paid.paymentReference()).isEqualTo("UPI-2");
        assertThat(paid.amountPaise()).isEqualTo(45000);
    }

    @Test
    void creatingAPlayer() {
        navigate(AdminPlayersView.class);
        click("New player");
        assertThat(find(PasswordField.class).exists()).isFalse();
        type("Full name", "Dijo T Mathews");
        assertThat(find(TextField.class).withLabel("Username").single().getValue()).isEqualTo("dijotmathews");
        click("Create");

        assertThat(notifications()).contains("Player created. Username and initial password: dijotmathews");
        assertThat(test(find(Grid.class).single()).size()).isEqualTo(2);
        assertThat(encoder.matches("dijotmathews", passwordOf("dijotmathews"))).isTrue();
    }

    @Test
    void savingBenefitSettings() {
        navigate(AdminPlayersView.class);
        click("Benefit settings");
        test(find(BigDecimalField.class).withLabel("Entry fee (₹)").single()).setValue(new BigDecimal("750"));
        test(find(IntegerField.class).withLabel("Coupons per player").single()).setValue(3);
        type("Coupon description", "Lunch");
        click("Save");

        var settings = benefits.settings().orElseThrow();
        assertThat(settings.feePaise()).isEqualTo(75000);
        assertThat(settings.couponCount()).isEqualTo(3);
        assertThat(settings.couponDescription()).isEqualTo("Lunch");
    }
}
