package com.syncshare.ui.design;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/**
 * Reusable progress indicator combining title, status/percentage readout,
 * semantic ProgressBar, and optional helper detail line.
 */
public class LabeledProgressBar extends VBox {

    public enum Size {
        SM("progress-sm"),
        MD(null),
        LG("progress-lg");

        private final String styleClass;

        Size(String styleClass) {
            this.styleClass = styleClass;
        }

        public String styleClass() {
            return styleClass;
        }
    }

    private static final List<String> ALL_SIZES = List.of("progress-sm", "progress-lg");

    private final Label titleLabel = new Label();
    private final Label valueLabel = new Label();
    private final ProgressBar progressBar = new ProgressBar(0.0);
    private final Label detailLabel = new Label();

    private SemanticTone tone = SemanticTone.TEAL;

    public LabeledProgressBar() {
        this("", 0.0, SemanticTone.TEAL);
    }

    public LabeledProgressBar(String title, double progress, SemanticTone tone) {
        setSpacing(6);

        HBox header = new HBox(DesignSystem.SPACE_XS);
        header.getStyleClass().add("progress-header-row");
        header.setAlignment(Pos.CENTER_LEFT);

        titleLabel.getStyleClass().add("text-body-strong");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        valueLabel.getStyleClass().add("progress-percent");
        header.getChildren().addAll(titleLabel, spacer, valueLabel);

        progressBar.getStyleClass().add("ss-progress");
        progressBar.setMaxWidth(Double.MAX_VALUE);

        detailLabel.getStyleClass().add("text-caption");
        detailLabel.setWrapText(true);
        detailLabel.setManaged(false);
        detailLabel.setVisible(false);

        getChildren().addAll(header, progressBar, detailLabel);

        setTitle(title);
        setProgress(progress);
        setTone(tone);
    }

    public LabeledProgressBar setTitle(String title) {
        titleLabel.setText(title != null ? title : "");
        return this;
    }

    public LabeledProgressBar setProgress(double progress) {
        progressBar.setProgress(progress);
        if (progress < 0) {
            valueLabel.setText("Working...");
        } else {
            int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * 100.0);
            valueLabel.setText(pct + "%");
        }
        return this;
    }

    public LabeledProgressBar setStatusText(String statusText) {
        if (statusText != null) {
            valueLabel.setText(statusText);
        }
        return this;
    }

    public LabeledProgressBar setDetail(String detail) {
        String safe = detail != null ? detail : "";
        detailLabel.setText(safe);
        boolean hasDetail = !safe.isBlank();
        detailLabel.setManaged(hasDetail);
        detailLabel.setVisible(hasDetail);
        return this;
    }

    public LabeledProgressBar setTone(SemanticTone tone) {
        this.tone = Objects.requireNonNullElse(tone, SemanticTone.TEAL);
        String toneClass = this.tone == SemanticTone.TEAL ? null : this.tone.progressStyleClass();
        DesignSystem.swapExclusiveClass(progressBar, SemanticTone.allProgressClasses(), toneClass);
        return this;
    }

    public LabeledProgressBar setSize(Size size) {
        Size resolved = Objects.requireNonNullElse(size, Size.MD);
        DesignSystem.swapExclusiveClass(progressBar, ALL_SIZES, resolved.styleClass());
        return this;
    }

    public ProgressBar getProgressBar() {
        return progressBar;
    }

    public SemanticTone getTone() {
        return tone;
    }
}
