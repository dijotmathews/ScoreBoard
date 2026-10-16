package com.dijo.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rupee amounts typed by the admin, stored as integer paise. */
class MoneyTest {
    @Test
    void convertsRupeesToPaise() {
        assertThat(PlayerEditorDialog.paise(new BigDecimal("500"))).isEqualTo(50000);
        assertThat(PlayerEditorDialog.paise(new BigDecimal("499.5"))).isEqualTo(49950);
        assertThat(PlayerEditorDialog.paise(new BigDecimal("0.01"))).isEqualTo(1);
        assertThat(PlayerEditorDialog.paise(new BigDecimal("12.300"))).isEqualTo(1230);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "1.005"})
    void rejectsAmountsThatAreNotPositiveWholePaise(String rupees) {
        assertThatThrownBy(() -> PlayerEditorDialog.paise(new BigDecimal(rupees)))
                .hasMessage("Enter a positive amount with at most 2 decimals");
    }

    @Test
    void rejectsAMissingAmount() {
        assertThatThrownBy(() -> PlayerEditorDialog.paise(null))
                .hasMessage("Enter a positive amount with at most 2 decimals");
    }

    @Test
    void formatsPaiseAsRupees() {
        assertThat(PlayerEditorDialog.rupees(50000)).isEqualTo("₹500.00");
        assertThat(PlayerEditorDialog.rupees(5)).isEqualTo("₹0.05");
    }
}
