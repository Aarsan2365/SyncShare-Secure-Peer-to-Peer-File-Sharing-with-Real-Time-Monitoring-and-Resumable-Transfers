package com.syncshare.ui.design;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Reusable accessible form field pairing a label, styled TextField,
 * and semantic validation feedback line.
 */
public class ValidatedTextField extends VBox {

    private final Label fieldLabel = new Label();
    private final TextField textField = new TextField();
    private final Label feedbackLabel = new Label();

    private String defaultHint = "";
    private SemanticTone validationTone = SemanticTone.NEUTRAL;

    public ValidatedTextField() {
        this("", "", "");
    }

    public ValidatedTextField(String labelText, String promptText, String hintText) {
        getStyleClass().add("field-group");

        fieldLabel.getStyleClass().add("field-label");
        fieldLabel.setLabelFor(textField);
        fieldLabel.setMnemonicParsing(false);

        textField.getStyleClass().add("input-field");
        textField.setMaxWidth(Double.MAX_VALUE);

        feedbackLabel.getStyleClass().add("field-hint");
        feedbackLabel.setWrapText(true);

        getChildren().addAll(fieldLabel, textField, feedbackLabel);

        setLabelText(labelText);
        setPromptText(promptText);
        setDefaultHint(hintText);
    }

    public ValidatedTextField setLabelText(String text) {
        String safe = text != null ? text : "";
        fieldLabel.setText(safe);
        fieldLabel.setManaged(!safe.isBlank());
        fieldLabel.setVisible(!safe.isBlank());
        textField.setAccessibleText(safe);
        return this;
    }

    public ValidatedTextField setPromptText(String prompt) {
        textField.setPromptText(prompt != null ? prompt : "");
        return this;
    }

    public ValidatedTextField setDefaultHint(String hint) {
        this.defaultHint = hint != null ? hint : "";
        if (validationTone == SemanticTone.NEUTRAL) {
            updateFeedback(SemanticTone.NEUTRAL, this.defaultHint);
        }
        return this;
    }

    public void setValidation(SemanticTone tone, String message) {
        this.validationTone = Objects.requireNonNullElse(tone, SemanticTone.NEUTRAL);
        DesignSystem.swapExclusiveClass(
            textField,
            SemanticTone.allInputClasses(),
            this.validationTone.inputStyleClass()
        );
        updateFeedback(this.validationTone, message);
    }

    public void clearValidation() {
        setValidation(SemanticTone.NEUTRAL, defaultHint);
    }

    private void updateFeedback(SemanticTone tone, String message) {
        String resolved = message != null ? message : "";
        DesignSystem.swapExclusiveClass(
            feedbackLabel,
            SemanticTone.allFieldFeedbackClasses(),
            tone.fieldFeedbackStyleClass()
        );
        feedbackLabel.setText(resolved);
        boolean visible = !resolved.isBlank();
        feedbackLabel.setManaged(visible);
        feedbackLabel.setVisible(visible);
    }

    public TextField getTextField() {
        return textField;
    }

    public String getText() {
        return textField.getText();
    }

    public void setText(String value) {
        textField.setText(value);
    }

    public SemanticTone getValidationTone() {
        return validationTone;
    }
}
