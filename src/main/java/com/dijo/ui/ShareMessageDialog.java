package com.dijo.ui;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.AnchorTarget;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Something to send through a messaging app: an editable text message (copy it or
 * open it in WhatsApp) and/or a picture (copy, share or download it as a PNG).
 * Nothing is saved.
 */
class ShareMessageDialog extends Dialog {
    /**
     * Rasterises $0 (an SVG string) to a PNG once, then wires the buttons. Clipboard and
     * share calls run in the browser's own click handlers, since they need the user's
     * gesture, which a server round trip would lose. $4 is this dialog, for feedback; $5 the file name.
     */
    private static final String IMAGE_SCRIPT = """
            const [svg, copy, share, download, dialog] = [$0, $1, $2, $3, $4];
            const name = $5;
            const png = new Promise((resolve, reject) => {
              const img = new Image();
              img.onload = () => {
                const scale = 2, canvas = document.createElement('canvas');
                canvas.width = img.width * scale;
                canvas.height = img.height * scale;
                const g = canvas.getContext('2d');
                g.scale(scale, scale);
                g.drawImage(img, 0, 0);
                canvas.toBlob(b => b ? resolve(b) : reject(new Error('Could not draw the image')), 'image/png');
              };
              img.onerror = () => reject(new Error('Could not draw the image'));
              img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
            });
            let blob = null;
            png.then(b => {
              blob = b;
              download.href = URL.createObjectURL(b);
              download.download = name;
            }).catch(() => {});
            const fileOf = b => new File([b], name, { type: 'image/png' });
            share.hidden = !(navigator.canShare && navigator.canShare({ files: [new File([''], name, { type: 'image/png' })] }));
            const report = (ok, msg) => dialog.$server.imageResult(ok, msg);
            copy.addEventListener('click', () => {
              if (!navigator.clipboard || !window.ClipboardItem) {
                report(false, 'Copying images is not supported here; use Download instead');
                return;
              }
              navigator.clipboard.write([new ClipboardItem({ 'image/png': png })])
                .then(() => report(true, 'Image copied, paste it into the chat'))
                .catch(e => report(false, 'Could not copy the image: ' + e.message));
            });
            share.addEventListener('click', () => {
              if (!blob) { report(false, 'The image is still being prepared, try again'); return; }
              navigator.share({ files: [fileOf(blob)] })
                .catch(e => { if (e.name !== 'AbortError') report(false, 'Could not share: ' + e.message); });
            });
            """;

    private final TextArea message = new TextArea();
    private final Anchor whatsApp = new Anchor();

    /** text and svg are each optional; at least one is given. */
    ShareMessageDialog(String title, String text, String svg, String imageName) {
        setHeaderTitle(title);
        setWidth("min(760px, 100vw)");

        TabSheet tabs = new TabSheet();
        tabs.setWidthFull();
        if (text != null) tabs.add("Message", messageTab(text));
        if (svg != null) tabs.add("Image", imageTab(svg, imageName));
        add(tabs);

        Button close = new Button("Close", e -> close());
        close.addThemeVariants(ButtonVariant.TERTIARY);
        getFooter().add(close);
    }

    private VerticalLayout messageTab(String text) {
        message.setValue(text);
        message.setWidthFull();
        message.setMinHeight("240px");
        message.setAriaLabel("Message");
        message.setValueChangeMode(ValueChangeMode.LAZY);
        message.addValueChangeListener(e -> updateWhatsAppLink());

        Button copy = new Button("Copy", VaadinIcon.COPY.create());
        copy.addThemeVariants(ButtonVariant.PRIMARY);
        copy.getElement().executeJs(
                "this.addEventListener('click', () => navigator.clipboard.writeText($0.value))",
                message.getElement());
        copy.addClickListener(e -> Notification.show("Message copied", 2000, Notification.Position.BOTTOM_CENTER));

        whatsApp.setTarget(AnchorTarget.BLANK);
        whatsApp.add(new Button("WhatsApp", VaadinIcon.COMMENT.create()));
        updateWhatsAppLink();

        return tab(message, actions(whatsApp, copy));
    }

    private VerticalLayout imageTab(String svg, String imageName) {
        Div preview = new Div(new Html(svg));
        preview.addClassNames("bracket-scroll", "share-image-preview");

        Button copy = new Button("Copy image", VaadinIcon.COPY.create());
        copy.addThemeVariants(ButtonVariant.PRIMARY);
        Button share = new Button("Share…", VaadinIcon.SHARE.create());
        Anchor download = new Anchor();
        download.add(new Button("Download", VaadinIcon.DOWNLOAD.create()));

        VerticalLayout tab = tab(preview, actions(download, share, copy));
        // The tab sheet attaches a tab only while it is shown, and the browser elements
        // are rebuilt each time, so wire the buttons on every attach.
        tab.addAttachListener(e -> getElement().executeJs(IMAGE_SCRIPT, svg, copy.getElement(),
                share.getElement(), download.getElement(), getElement(), imageName));
        return tab;
    }

    private static Div actions(com.vaadin.flow.component.Component... buttons) {
        Div row = new Div(buttons);
        row.addClassName("share-actions");
        return row;
    }

    private static VerticalLayout tab(com.vaadin.flow.component.Component... parts) {
        VerticalLayout layout = new VerticalLayout(parts);
        layout.setPadding(false);
        return layout;
    }

    @ClientCallable
    private void imageResult(boolean ok, String text) {
        Notification n = Notification.show(text, ok ? 2500 : 5000, Notification.Position.BOTTOM_CENTER);
        if (!ok) n.addThemeVariants(NotificationVariant.ERROR);
    }

    private void updateWhatsAppLink() {
        whatsApp.setHref("https://wa.me/?text=" + URLEncoder.encode(message.getValue(), StandardCharsets.UTF_8)
                .replace("+", "%20"));
    }
}
