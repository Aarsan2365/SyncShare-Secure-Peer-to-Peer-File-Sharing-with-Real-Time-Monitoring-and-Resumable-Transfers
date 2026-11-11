package com.syncshare.ui.navigation;

import com.syncshare.model.NavItemId;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reusable sidebar navigation rail managing SyncShare destinations,
 * active item highlighting, live badge counts, and arrow-key traversal.
 */
public class SidebarNavigation extends VBox {

    private final Map<NavItemId, SidebarNavItem> itemsById = new EnumMap<>(NavItemId.class);
    private final Map<NavItemId, Label> badgesById = new EnumMap<>(NavItemId.class);
    private final List<SidebarNavItem> orderedItems = new ArrayList<>();
    private final ReadOnlyObjectWrapper<NavItemId> activeId =
        new ReadOnlyObjectWrapper<>(this, "activeId");

    private Consumer<NavItemId> onSelect;

    public SidebarNavigation() {
        setSpacing(4);
        installKeyboardNavigation();
    }

    public void configure(List<NavDestination> destinations, Consumer<NavItemId> onSelect) {
        Objects.requireNonNull(destinations, "destinations");
        this.onSelect = onSelect;

        getChildren().clear();
        itemsById.clear();
        badgesById.clear();
        orderedItems.clear();

        for (NavDestination destination : destinations) {
            SidebarNavItem button = new SidebarNavItem(destination);
            button.setOnAction(event -> {
                if (this.onSelect != null) {
                    this.onSelect.accept(destination.id());
                }
            });

            Label countBadge = new Label();
            countBadge.getStyleClass().add("nav-badge");
            countBadge.setMouseTransparent(true);
            countBadge.setManaged(false);
            countBadge.setVisible(false);

            HBox badgeHolder = new HBox(countBadge);
            badgeHolder.getStyleClass().add("nav-badge-holder");
            badgeHolder.setAlignment(Pos.CENTER_RIGHT);
            badgeHolder.setMouseTransparent(true);

            StackPane row = new StackPane(button, badgeHolder);
            row.getStyleClass().add("nav-row");

            itemsById.put(destination.id(), button);
            badgesById.put(destination.id(), countBadge);
            orderedItems.add(button);
            getChildren().add(row);
        }
    }

    public void setActive(NavItemId id) {
        activeId.set(id);
        for (SidebarNavItem item : orderedItems) {
            item.setActive(item.getDestinationId() == id);
        }
    }

    public void setBadgeCount(NavItemId id, int count) {
        Label badge = badgesById.get(id);
        if (badge == null) {
            return;
        }
        boolean show = count > 0;
        badge.setText(count > 99 ? "99+" : Integer.toString(count));
        badge.setManaged(show);
        badge.setVisible(show);
    }

    public NavItemId getActiveId() {
        return activeId.get();
    }

    public ReadOnlyObjectProperty<NavItemId> activeIdProperty() {
        return activeId.getReadOnlyProperty();
    }

    public SidebarNavItem getNavItem(NavItemId id) {
        return itemsById.get(id);
    }

    public List<Button> getNavigationButtons() {
        return List.copyOf(orderedItems);
    }

    private void installKeyboardNavigation() {
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (orderedItems.isEmpty()) {
                return;
            }
            KeyCode code = event.getCode();
            if (code != KeyCode.DOWN && code != KeyCode.UP && code != KeyCode.HOME && code != KeyCode.END) {
                return;
            }

            int currentIndex = -1;
            for (int i = 0; i < orderedItems.size(); i++) {
                if (orderedItems.get(i).isFocused()) {
                    currentIndex = i;
                    break;
                }
            }

            int nextIndex;
            if (code == KeyCode.HOME) {
                nextIndex = 0;
            } else if (code == KeyCode.END) {
                nextIndex = orderedItems.size() - 1;
            } else if (currentIndex < 0) {
                nextIndex = 0;
            } else if (code == KeyCode.DOWN) {
                nextIndex = (currentIndex + 1) % orderedItems.size();
            } else {
                nextIndex = (currentIndex - 1 + orderedItems.size()) % orderedItems.size();
            }

            orderedItems.get(nextIndex).requestFocus();
            event.consume();
        });
    }
}
