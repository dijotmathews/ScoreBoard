package com.dijo.benefits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dijo.DatabaseTest;
import com.dijo.benefits.PlayerBenefitsService.Benefits;
import com.dijo.benefits.PlayerBenefitsService.PlayerBenefitRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

class PlayerBenefitsServiceTest extends DatabaseTest {
    private static final long FEE = 50000;

    @Autowired
    PlayerBenefitsService benefits;

    @BeforeEach
    void setUp() {
        account("admin", "ADMIN", "USER");
        account("ref", "OFFICIAL");
        player("ada", "bob");
        signIn("admin", "ADMIN", "USER");
    }

    private void configure() {
        benefits.updateSettings(FEE, 2, "Refreshment");
    }

    @Nested
    class Settings {
        @Test
        void areEmptyUntilConfigured() {
            assertThat(benefits.settings()).isEmpty();
        }

        @Test
        void canBeSavedAndChanged() {
            configure();
            benefits.updateSettings(60000, 3, "Lunch");

            var settings = benefits.settings().orElseThrow();
            assertThat(settings.feePaise()).isEqualTo(60000);
            assertThat(settings.couponCount()).isEqualTo(3);
            assertThat(settings.couponDescription()).isEqualTo("Lunch");
        }

        @Test
        void rejectInvalidValues() {
            assertThatThrownBy(() -> benefits.updateSettings(0, 2, "Refreshment"))
                    .hasMessage("Invalid fee or coupon count");
            assertThatThrownBy(() -> benefits.updateSettings(FEE, 101, "Refreshment"))
                    .hasMessage("Invalid fee or coupon count");
            assertThatThrownBy(() -> benefits.updateSettings(FEE, 2, " "))
                    .hasMessage("Coupon description is required");
        }

        @Test
        void canOnlyBeChangedByAnAdmin() {
            signIn("ref", "OFFICIAL");
            assertThatThrownBy(() -> benefits.updateSettings(FEE, 2, "Refreshment"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    class ConfirmPayment {
        @Test
        void signsThePlayerUpWithAKitAndTheConfiguredCoupons() {
            configure();

            Benefits signedUp = benefits.confirmPayment("ada", "UPI-1", FEE);

            assertThat(signedUp.playerUsername()).isEqualTo("ada");
            assertThat(signedUp.paymentReference()).isEqualTo("UPI-1");
            assertThat(signedUp.amountPaise()).isEqualTo(FEE);
            assertThat(signedUp.kitHandedOverAt()).isNull();
            assertThat(signedUp.coupons()).hasSize(2)
                    .allSatisfy(c -> {
                        assertThat(c.description()).isEqualTo("Refreshment");
                        assertThat(c.redeemedAt()).isNull();
                    });
            assertThat(count("SELECT count(*) FROM entry_payments WHERE confirmed_by = 'admin'"))
                    .isEqualTo(1);
        }

        @Test
        void canBeDoneByAnOfficial() {
            configure();
            as("ref", "OFFICIAL", () -> benefits.confirmPayment("ada", "UPI-1", FEE));
            assertThat(benefits.playerBenefits("ada").paymentReference()).isEqualTo("UPI-1");
        }

        @Test
        void repeatingTheSameConfirmationReturnsTheOriginalBenefits() {
            configure();
            Benefits first = benefits.confirmPayment("ada", "UPI-1", FEE);
            Benefits again = benefits.confirmPayment("ada", "UPI-1", FEE);

            assertThat(again).isEqualTo(first);
            assertThat(count("SELECT count(*) FROM player_coupons")).isEqualTo(2);
        }

        @Test
        void rejectsASecondPaymentForTheSamePlayer() {
            configure();
            benefits.confirmPayment("ada", "UPI-1", FEE);
            assertThatThrownBy(() -> benefits.confirmPayment("ada", "UPI-2", FEE))
                    .hasMessage("Invalid enabled player, fee, or conflicting payment reference");
        }

        @Test
        void rejectsAReferenceAlreadyUsedByAnotherPlayer() {
            configure();
            benefits.confirmPayment("ada", "UPI-1", FEE);
            assertThatThrownBy(() -> benefits.confirmPayment("bob", "UPI-1", FEE))
                    .hasMessage("Invalid enabled player, fee, or conflicting payment reference");
        }

        @Test
        void rejectsAnAmountOtherThanTheFee() {
            configure();
            assertThatThrownBy(() -> benefits.confirmPayment("ada", "UPI-1", FEE - 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsDisabledPlayersAndStaff() {
            configure();
            jdbc.update("UPDATE users SET enabled = 0 WHERE username = 'bob'");
            assertThatThrownBy(() -> benefits.confirmPayment("bob", "UPI-1", FEE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> benefits.confirmPayment("ref", "UPI-2", FEE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void needsTheEntryFeeToBeSet() {
            assertThatThrownBy(() -> benefits.confirmPayment("ada", "UPI-1", FEE))
                    .hasMessage("Set the entry fee in benefit settings first");
        }

        @Test
        void requiresAReference() {
            configure();
            assertThatThrownBy(() -> benefits.confirmPayment("ada", " ", FEE))
                    .hasMessage("Payment reference is required");
        }

        @Test
        void isNotAllowedForPlayers() {
            configure();
            signIn("ada", "USER");
            assertThatThrownBy(() -> benefits.confirmPayment("ada", "UPI-1", FEE))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void issuesCouponsFromTheSettingsAtTheTime() {
            configure();
            benefits.confirmPayment("ada", "UPI-1", FEE);
            benefits.updateSettings(FEE, 3, "Lunch");
            benefits.confirmPayment("bob", "UPI-2", FEE);

            assertThat(benefits.playerBenefits("ada").coupons()).hasSize(2);
            assertThat(benefits.playerBenefits("bob").coupons()).hasSize(3)
                    .allSatisfy(c -> assertThat(c.description()).isEqualTo("Lunch"));
        }
    }

    @Nested
    class UpdatePayment {
        @BeforeEach
        void paid() {
            configure();
            benefits.confirmPayment("ada", "UPI-1", FEE);
        }

        @Test
        void changesTheAmountAndReference() {
            Benefits updated = benefits.updatePayment("ada", "UPI-9", 45000);
            assertThat(updated.paymentReference()).isEqualTo("UPI-9");
            assertThat(updated.amountPaise()).isEqualTo(45000);
        }

        @Test
        void needsATransactionIdThatIsNotInUse() {
            assertThatThrownBy(() -> benefits.updatePayment("ada", "UPI-1", FEE))
                    .hasMessage("Transaction ID is already in use; enter a new transaction ID");
            assertThatThrownBy(() -> benefits.updatePayment("ada", "", FEE))
                    .hasMessage("Transaction ID is required");
        }

        @Test
        void needsASignedUpPlayer() {
            assertThatThrownBy(() -> benefits.updatePayment("bob", "UPI-9", FEE))
                    .hasMessage("Player has not signed up");
        }

        @Test
        void isAdminOnly() {
            signIn("ref", "OFFICIAL");
            assertThatThrownBy(() -> benefits.updatePayment("ada", "UPI-9", FEE))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    class KitAndCoupons {
        @BeforeEach
        void paid() {
            configure();
            benefits.confirmPayment("ada", "UPI-1", FEE);
        }

        @Test
        void kitIsHandedOverOnce() {
            assertThat(benefits.handOverKit("ada")).isTrue();
            assertThat(benefits.handOverKit("ada")).isFalse();
            assertThat(benefits.playerBenefits("ada").kitHandedOverAt()).isNotNull();
        }

        @Test
        void kitNeedsASignedUpPlayer() {
            assertThatThrownBy(() -> benefits.handOverKit("bob"))
                    .hasMessage("Player has not signed up");
        }

        @Test
        void couponIsRedeemedOnce() {
            String code = benefits.playerBenefits("ada").coupons().getFirst().code();

            assertThat(benefits.redeemCoupon("ada", code)).isTrue();
            assertThat(benefits.redeemCoupon("ada", code)).isFalse();
            assertThat(benefits.playerBenefits("ada").coupons())
                    .filteredOn(c -> c.redeemedAt() != null).hasSize(1);
        }

        @Test
        void couponMustBelongToThePlayer() {
            String code = benefits.playerBenefits("ada").coupons().getFirst().code();
            benefits.confirmPayment("bob", "UPI-2", FEE);

            assertThatThrownBy(() -> benefits.redeemCoupon("bob", code))
                    .hasMessage("Coupon not found for player");
            assertThat(benefits.playerBenefits("ada").coupons())
                    .allSatisfy(c -> assertThat(c.redeemedAt()).isNull());
        }

        @Test
        void playerSeesOnlyTheirOwnBenefits() {
            signIn("ada", "USER");
            assertThat(benefits.myBenefits().playerUsername()).isEqualTo("ada");
            assertThatThrownBy(() -> benefits.playerBenefits("ada"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void listShowsEveryPlayerButNotStaff() {
        configure();
        benefits.confirmPayment("ada", "UPI-1", FEE);
        String code = benefits.playerBenefits("ada").coupons().getFirst().code();
        benefits.redeemCoupon("ada", code);

        var rows = benefits.listPlayerBenefits();

        assertThat(rows).extracting(PlayerBenefitRow::username).containsExactly("ada", "bob");
        PlayerBenefitRow ada = rows.getFirst();
        assertThat(ada.paid()).isTrue();
        assertThat(ada.couponsIssued()).isEqualTo(2);
        assertThat(ada.couponsRemaining()).isEqualTo(1);
        assertThat(rows.get(1).paid()).isFalse();
    }
}
