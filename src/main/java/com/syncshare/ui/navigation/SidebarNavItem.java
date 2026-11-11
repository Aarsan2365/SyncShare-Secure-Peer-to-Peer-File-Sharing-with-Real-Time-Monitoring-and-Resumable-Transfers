package com.syncshare.ui.navigation;

import com.syncshare.model.NavItemId;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.UiIcons;
import javafx.scene.control.Button;

import java.util.Objects;

/**
 * Reusable sidebar navigation button with consistent icon rendering,
 * keyboard accessibility, and active state styling.
 */
public class SidebarNavItem extends Button {

    private final NavDestination destination;
    private boolean active;

    public SidebarNavItem(NavDestination destination) {
        this.destination = Objects.requireNonNull(destination, "destination");

        setText(destination.label());
        setMnemonicParsing(false);
        setMaxWidth(Double.MAX_VALUE);
        setGraphic(UiIcons.create(destination.iconType(), UiIcons.Size.MD));
        getStyleClass().add("nav-item");
        setAccessibleText(destination.label());
    }

    public NavItemId getDestinationId() {
        return destination.id();
    }

    public NavDestination getDestination() {
        return destination;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        DesignSystem.setStyleClassPresent(this, "nav-item-active", active);
        setAccessibleHelp(active ? "Selected navigation item" : null);
    }
}
