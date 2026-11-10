package com.syncshare.ui.design;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/**
 * Reusable rounded card container supporting variants, optional header/footer,
 * semantic accents, and keyboard-activatable interactive cards.
 */
public class UiCard extends VBox {

    public enum Variant {
        DEFAULT("card"),
        ELEVATED("card-elevated"),
        MUTED("card-muted"),
        NAVY("card-navy");

        private final String styleClass;

        Variant(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    private static final List<String> BASE_VARIANTS = List.of(
        "card", "card-elevated", "card-muted", "card-navy"
    );

    private final HBox headerBox = new HBox(DesignSystem.SPACE_SM);
    private final VBox titleGroup = new VBox(2);
    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final VBox bodyBox = new VBox(DesignSystem.SPACE_SM);
    private final HBox footerBox = new HBox(DesignSystem.SPACE_XS);

    private StackPane headerIcon;
    private Node trailingHeaderNode;
    private boolean hasHeader;
    private boolean hasFooter;

    public UiCard() {
        this(Variant.DEFAULT);
    }

    public UiCard(Variant variant) {
        setSpacing(0);
        setVariant(variant);

        headerBox.getStyleClass().add("card-header");
        headerBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleGroup, Priority.ALWAYS);
        titleGroup.setMinWidth(0);

        titleLabel.getStyleClass().add("text-card-title");
        titleLabel.setWrapText(true);
        subtitleLabel.getStyleClass().add("text-caption");
        subtitleLabel.setWrapText(true);

        bodyBox.getStyleClass().add("card-body");
        VBox.setVgrow(bodyBox, Priority.ALWAYS);

        footerBox.getStyleClass().add("card-footer");
        footerBox.setAlignment(Pos.CENTER_RIGHT);
    }

    public UiCard setVariant(Variant variant) {
        Variant resolved = Objects.requireNonNullElse(variant, Variant.DEFAULT);
        DesignSystem.swapExclusiveClass(this, BASE_VARIANTS, resolved.styleClass());
        return this;
    }

    public UiCard setCompact(boolean compact) {
        DesignSystem.setStyleClassPresent(this, "card-compact", compact);
        return this;
    }

    public UiCard setTone(SemanticTone tone) {
        String toneClass = tone != null ? tone.cardStyleClass() : null;
        DesignSystem.swapExclusiveClass(this, SemanticTone.allCardToneClasses(), toneClass);
        return this;
    }

    public UiCard setInteractive(Runnable onActivate) {
        DesignSystem.ensureStyleClass(this, "card-interactive");
        if (onActivate != null) {
            setOnMouseClicked(event -> onActivate.run());
            DesignSystem.makeKeyboardActivatable(this, onActivate);
        }
        return this;
    }

    public UiCard setHeader(String title, String subtitle) {
        return setHeader(title, subtitle, null, null);
    }

    public UiCard setHeader(String title, String subtitle, StackPane icon, Node trailing) {
        titleLabel.setText(title != null ? title : "");
        subtitleLabel.setText(subtitle != null ? subtitle : "");
        this.headerIcon = icon;
        this.trailingHeaderNode = trailing;
        this.hasHeader = true;
        rebuildStructure();
        return this;
    }

    public UiCard setBodyContent(Node... nodes) {
        bodyBox.getChildren().setAll(nodes);
        rebuildStructure();
        return this;
    }

    public UiCard addBodyContent(Node... nodes) {
        bodyBox.getChildren().addAll(nodes);
        rebuildStructure();
        return this;
    }

    public UiCard setFooterContent(Node... nodes) {
        footerBox.getChildren().setAll(nodes);
        this.hasFooter = nodes.length > 0;
        rebuildStructure();
        return this;
    }

    public VBox getBodyBox() {
        return bodyBox;
    }

    public HBox getFooterBox() {
        return footerBox;
    }

    private void rebuildStructure() {
        getChildren().clear();

        if (hasHeader) {
            headerBox.getChildren().clear();
            if (headerIcon != null) {
                headerBox.getChildren().add(headerIcon);
            }
            titleGroup.getChildren().setAll(titleLabel);
            if (!subtitleLabel.getText().isBlank()) {
                titleGroup.getChildren().add(subtitleLabel);
            }
            headerBox.getChildren().add(titleGroup);
            if (trailingHeaderNode != null) {
                headerBox.getChildren().add(trailingHeaderNode);
            }
            getChildren().add(headerBox);
            DesignSystem.setStyleClassPresent(bodyBox, "card-body", true);
        } else {
            bodyBox.getStyleClass().remove("card-body");
        }

        if (!bodyBox.getChildren().isEmpty()) {
            getChildren().add(bodyBox);
        }

        if (hasFooter) {
            getChildren().add(footerBox);
        }
    }

    public static UiCard metric(
        String label,
        String value,
        String detail,
        UiIcons.IconType iconType,
        SemanticTone badgeTone,
        String badgeText
    ) {
        UiCard card = new UiCard(Variant.DEFAULT).setCompact(true);
        card.setSpacing(DesignSystem.SPACE_XS);

        HBox topRow = new HBox(DesignSystem.SPACE_XS);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label eyebrow = new Label(label);
        eyebrow.getStyleClass().add("text-eyebrow");
        eyebrow.setMinWidth(0);
        HBox.setHgrow(eyebrow, Priority.ALWAYS);

        if (iconType != null) {
            topRow.getChildren().add(UiIcons.create(iconType, UiIcons.Size.SM, badgeTone));
        }
        topRow.getChildren().add(eyebrow);

        if (badgeText != null && !badgeText.isBlank()) {
            StatusBadge badge = new StatusBadge(badgeText, badgeTone);
            badge.setCompact(true);
            topRow.getChildren().add(badge);
        }

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("text-metric");

        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("text-caption");
        detailLabel.setWrapText(true);

        card.getChildren().setAll(topRow, valueLabel, detailLabel);
        return card;
    }
}
