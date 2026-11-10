package com.syncshare.ui.design;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Reusable empty-state component for summary panels, queues, and data tables.
 */
public class EmptyStatePane extends VBox {

    private final StackPane iconWrapper = new StackPane();
    private final Label titleLabel = new Label();
    private final Label descriptionLabel = new Label();
    private final FlowPane actionsPane = new FlowPane(DesignSystem.SPACE_XS, DesignSystem.SPACE_XS);

    public EmptyStatePane() {
        this(UiIcons.IconType.INFO_CIRCLE, SemanticTone.TEAL, "", "");
    }

    public EmptyStatePane(
        UiIcons.IconType iconType,
        SemanticTone tone,
        String title,
        String description
    ) {
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);

        iconWrapper.getStyleClass().add("empty-state-icon-wrap");

        titleLabel.getStyleClass().add("empty-state-title");
        titleLabel.setWrapText(true);

        descriptionLabel.getStyleClass().add("empty-state-description");
        descriptionLabel.setWrapText(true);

        actionsPane.getStyleClass().add("empty-state-actions");
        actionsPane.setAlignment(Pos.CENTER);
        actionsPane.setManaged(false);
        actionsPane.setVisible(false);

        getChildren().addAll(iconWrapper, titleLabel, descriptionLabel, actionsPane);

        setIcon(iconType, tone);
        setTitle(title);
        setDescription(description);
    }

    public EmptyStatePane setIcon(UiIcons.IconType iconType, SemanticTone tone) {
        UiIcons.IconType resolvedIcon = Objects.requireNonNullElse(iconType, UiIcons.IconType.INFO_CIRCLE);
        SemanticTone resolvedTone = Objects.requireNonNullElse(tone, SemanticTone.TEAL);
        iconWrapper.getChildren().setAll(UiIcons.create(resolvedIcon, UiIcons.Size.XL, resolvedTone));
        return this;
    }

    public EmptyStatePane setTitle(String title) {
        String safe = title != null ? title : "";
        titleLabel.setText(safe);
        titleLabel.setManaged(!safe.isBlank());
        titleLabel.setVisible(!safe.isBlank());
        return this;
    }

    public EmptyStatePane setDescription(String description) {
        String safe = description != null ? description : "";
        descriptionLabel.setText(safe);
        descriptionLabel.setManaged(!safe.isBlank());
        descriptionLabel.setVisible(!safe.isBlank());
        return this;
    }

    public EmptyStatePane setCompact(boolean compact) {
        DesignSystem.setStyleClassPresent(this, "empty-state-compact", compact);
        return this;
    }

    public EmptyStatePane setPrimaryAction(String label, UiIcons.IconType icon, Runnable onAction) {
        Button button = UiButtons.create(
            label,
            icon != null ? UiIcons.create(icon, UiIcons.Size.SM) : null,
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.SM
        );
        button.setOnAction(event -> {
            if (onAction != null) {
                onAction.run();
            }
        });
        actionsPane.getChildren().setAll(button);
        actionsPane.setManaged(true);
        actionsPane.setVisible(true);
        return this;
    }

    public EmptyStatePane addAction(Button button) {
        Objects.requireNonNull(button, "button");
        actionsPane.getChildren().add(button);
        actionsPane.setManaged(true);
        actionsPane.setVisible(true);
        return this;
    }

    public EmptyStatePane clearActions() {
        actionsPane.getChildren().clear();
        actionsPane.setManaged(false);
        actionsPane.setVisible(false);
        return this;
    }

    public String getTitle() {
        return titleLabel.getText();
    }

    public String getDescription() {
        return descriptionLabel.getText();
    }
}
