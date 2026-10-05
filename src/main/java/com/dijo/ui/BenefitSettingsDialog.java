package com.dijo.ui;

import com.dijo.benefits.PlayerBenefitsService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import java.math.BigDecimal;

/** Entry fee and the benefits every signed-up player receives. */
class BenefitSettingsDialog extends Dialog {
    private final PlayerBenefitsService service;
    private final Runnable onChange;

    private final BigDecimalField fee = new BigDecimalField("Entry fee (₹)");
    private final IntegerField couponCount = new IntegerField("Coupons per player");
    private final TextField couponDescription = new TextField("Coupon description");

    BenefitSettingsDialog(PlayerBenefitsService service, Runnable onChange) {
        this.service = service;
        this.onChange = onChange;
        setHeaderTitle("Benefit settings");
        setWidth("min(480px, 100vw)");

        couponCount.setMin(0);
        couponCount.setMax(100);
        couponCount.setStepButtonsVisible(true);
        couponCount.setValue(0);
        service.settings().ifPresent(s -> {
            fee.setValue(BigDecimal.valueOf(s.feePaise(), 2));
            couponCount.setValue(s.couponCount());
            couponDescription.setValue(s.couponDescription());
            fee.setHelperText("Changes apply to sign-ups recorded from now on");
            couponCount.setHelperText("Signed-up players keep the coupons they have");
        });

        FormLayout form = new FormLayout(fee, couponCount, couponDescription);
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("360px", 2));
        form.setColspan(couponDescription, 2);
        add(form);

        Button save = new Button("Save", e -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        Button cancel = new Button("Cancel", e -> close());
        cancel.addThemeVariants(ButtonVariant.TERTIARY);
        getFooter().add(cancel, save);
    }

    private void save() {
        try {
            if (couponCount.getValue() == null) {
                throw new IllegalArgumentException("Enter the number of coupons");
            }
            service.updateSettings(PlayerEditorDialog.paise(fee.getValue()),
                    couponCount.getValue(), couponDescription.getValue().strip());
        } catch (IllegalArgumentException ex) {
            Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.ERROR);
            return;
        }
        Notification.show("Benefit settings saved", 3000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.SUCCESS);
        onChange.run();
        close();
    }
}
