package com.syncshare.ui.design;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.Objects;

/**
 * Reusable pill status badge with optional leading status dot or vector icon.
 */
public class StatusBadge extends HBox {

    public enum IndicatorMode {
        DOT,
        ICON,
        NONE
    }

    private final Region dot = new Region();
    private final Label label = new Label();
    private StackPane iconNode;

    private final StringProperty text = new SimpleStringProperty(this, "text", "");
    private final ObjectProperty<SemanticTone> tone = new SimpleObjectProperty<>(this, "tone", SemanticTone.NEUTRAL);
    private final ObjectProperty<IndicatorMode> indicatorMode = new SimpleObjectProperty<>(this, "indicatorMode", IndicatorMode.DOT);
    private final BooleanProperty compact = new SimpleBooleanProperty(this, "compact", false);

    public StatusBadge() {
        this("", SemanticTone.NEUTRAL, IndicatorMode.DOT);
    }

    public StatusBadge(String text, SemanticTone tone) {
        this(text, tone, IndicatorMode.DOT);
    }

    public StatusBadge(String text, SemanticTone tone, IndicatorMode mode) {
        getStyleClass().add("badge");
        setAlignment(Pos.CENTER);
        setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setAccessibleRole(AccessibleRole.TEXT);

        dot.getStyleClass().add("status-dot");
        label.getStyleClass().add("badge-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.textProperty().bind(this.text);

        this.text.addListener((obs, oldVal, newVal) -> setAccessibleText(newVal));
        this.tone.addListener((obs, oldVal, newVal) -> updateAppearance());
        this.indicatorMode.addListener((obs, oldVal, newVal) -> updateAppearance());
        this.compact.addListener((obs, oldVal, newVal) -> DesignSystem.setStyleClassPresent(this, "badge-sm", newVal));

        setText(text);
        setTone(tone != null ? tone : SemanticTone.NEUTRAL);
        setIndicatorMode(mode != null ? mode : IndicatorMode.DOT);
        updateAppearance();
    }

    private void updateAppearance() {
        SemanticTone activeTone = getTone() != null ? getTone() : SemanticTone.NEUTRAL;
        IndicatorMode mode = getIndicatorMode() != null ? getIndicatorMode() : IndicatorMode.DOT;

        DesignSystem.swapExclusiveClass(this, SemanticTone.allBadgeClasses(), activeTone.badgeStyleClass());
        DesignSystem.swapExclusiveClass(dot, SemanticTone.allDotClasses(), activeTone.dotStyleClass());

        getChildren().clear();
        switch (mode) {
            case DOT -> getChildren().addAll(dot, label);
            case ICON -> {
                iconNode = UiIcons.create(UiIcons.iconForTone(activeTone), UiIcons.Size.SM, activeTone);
                getChildren().addAll(iconNode, label);
            }
            case NONE -> getChildren().add(label);
        }
    }

    public String getText() {
        return text.get();
    }

    public void setText(String value) {
        text.set(value != null ? value : "");
    }

    public StringProperty textProperty() {
        return text;
    }

    public SemanticTone getTone() {
        return tone.get();
    }

    public void setTone(SemanticTone value) {
        tone.set(Objects.requireNonNullElse(value, SemanticTone.NEUTRAL));
    }

    public ObjectProperty<SemanticTone> toneProperty() {
        return tone;
    }

    public IndicatorMode getIndicatorMode() {
        return indicatorMode.get();
    }

    public void setIndicatorMode(IndicatorMode mode) {
        indicatorMode.set(Objects.requireNonNullElse(mode, IndicatorMode.DOT));
    }

    public ObjectProperty<IndicatorMode> indicatorModeProperty() {
        return indicatorMode;
    }

    public boolean isCompact() {
        return compact.get();
    }

    public void setCompact(boolean value) {
        compact.set(value);
    }

    public BooleanProperty compactProperty() {
        return compact;
    }
}
