package com.syncshare.ui.navigation;

import com.syncshare.model.NavItemId;
import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.FeedbackBanner;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.StatusBadge;
import com.syncshare.ui.design.UiButtons;
import com.syncshare.ui.design.UiCard;
import com.syncshare.ui.design.UiIcons;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.URL;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reusable screen router that loads FXML views on demand, caches view instances
 * across screen switches without recreating application services, and recovers
 * gracefully with an inline fallback screen if any navigation error occurs.
 */
public class ViewNavigator {

    public record LoadedScreen(Parent root, Object controller) {
    }

    @FunctionalInterface
    public interface ScreenLoader {
        LoadedScreen load(NavDestination destination, SyncShareServices services, ViewNavigator navigator)
            throws Exception;
    }

    private final StackPane viewHost;
    private final SyncShareServices services;
    private final Map<NavItemId, NavDestination> destinations = new EnumMap<>(NavItemId.class);
    private final Map<NavItemId, LoadedScreen> cache = new EnumMap<>(NavItemId.class);
    private final ReadOnlyObjectWrapper<NavDestination> currentDestination =
        new ReadOnlyObjectWrapper<>(this, "currentDestination");

    private ScreenLoader screenLoader;
    private Consumer<NavDestination> onNavigateSuccess;
    private BiConsumer<NavItemId, String> onNavigateError;
    private VBox activeFallbackNode;

    public ViewNavigator(
        StackPane viewHost,
        SyncShareServices services,
        List<NavDestination> routeDefinitions
    ) {
        this(viewHost, services, routeDefinitions, ViewNavigator::loadFxmlScreen);
    }

    public ViewNavigator(
        StackPane viewHost,
        SyncShareServices services,
        List<NavDestination> routeDefinitions,
        ScreenLoader screenLoader
    ) {
        this.viewHost = Objects.requireNonNull(viewHost, "viewHost");
        this.services = Objects.requireNonNull(services, "services");
        this.screenLoader = Objects.requireNonNull(screenLoader, "screenLoader");
        Objects.requireNonNull(routeDefinitions, "routeDefinitions");

        for (NavDestination destination : routeDefinitions) {
            destinations.put(destination.id(), destination);
        }
    }

    public void setOnNavigateSuccess(Consumer<NavDestination> onNavigateSuccess) {
        this.onNavigateSuccess = onNavigateSuccess;
    }

    public void setOnNavigateError(BiConsumer<NavItemId, String> onNavigateError) {
        this.onNavigateError = onNavigateError;
    }

    public void setScreenLoader(ScreenLoader screenLoader) {
        this.screenLoader = Objects.requireNonNull(screenLoader, "screenLoader");
    }

    public NavigationResult navigateTo(NavItemId id) {
        if (id == null) {
            return handleNavigationFailure(null, "Navigation target cannot be null.", null);
        }

        NavDestination destination = destinations.get(id);
        if (destination == null) {
            return handleNavigationFailure(id, "Unknown navigation destination: " + id, null);
        }

        try {
            LoadedScreen screen = cache.get(id);
            if (screen == null) {
                screen = screenLoader.load(destination, services, this);
                if (screen == null || screen.root() == null) {
                    throw new IllegalStateException("Screen loader returned an empty view for " + id.routeKey());
                }
                cache.put(id, screen);
                viewHost.getChildren().add(screen.root());
            }

            clearErrorFallback();
            showOnlyNode(screen.root());

            if (screen.controller() instanceof NavigationAware aware) {
                aware.onNavigatedTo();
            }

            currentDestination.set(destination);
            if (onNavigateSuccess != null) {
                onNavigateSuccess.accept(destination);
            }
            return NavigationResult.ok(id);
        } catch (Throwable error) {
            return handleNavigationFailure(id, describeError(destination, error), error);
        }
    }

    public boolean isScreenCached(NavItemId id) {
        return id != null && cache.containsKey(id);
    }

    public LoadedScreen getCachedScreen(NavItemId id) {
        return id != null ? cache.get(id) : null;
    }

    public NavDestination getCurrentDestination() {
        return currentDestination.get();
    }

    public ReadOnlyObjectProperty<NavDestination> currentDestinationProperty() {
        return currentDestination.getReadOnlyProperty();
    }

    public boolean isShowingErrorFallback() {
        return activeFallbackNode != null && activeFallbackNode.isVisible();
    }

    public NavDestination getDestination(NavItemId id) {
        return destinations.get(id);
    }

    private void showOnlyNode(Node target) {
        for (Node child : viewHost.getChildren()) {
            boolean match = child == target;
            child.setVisible(match);
            child.setManaged(match);
        }
    }

    private void clearErrorFallback() {
        if (activeFallbackNode != null) {
            viewHost.getChildren().remove(activeFallbackNode);
            activeFallbackNode = null;
        }
    }

    private NavigationResult handleNavigationFailure(NavItemId targetId, String detail, Throwable error) {
        if (error != null) {
            System.err.println("Navigation error for " + targetId + ": " + detail);
            error.printStackTrace(System.err);
        } else {
            System.err.println("Navigation error: " + detail);
        }

        clearErrorFallback();
        activeFallbackNode = buildFallbackView(targetId, detail);
        viewHost.getChildren().add(activeFallbackNode);
        showOnlyNode(activeFallbackNode);

        if (onNavigateError != null) {
            onNavigateError.accept(targetId, detail);
        }
        return NavigationResult.failure(targetId, detail, error);
    }

    private VBox buildFallbackView(NavItemId targetId, String detail) {
        VBox container = new VBox(DesignSystem.SPACE_MD);
        container.getStyleClass().add("error-fallback-host");

        String targetLabel = targetId != null && destinations.containsKey(targetId)
            ? destinations.get(targetId).label()
            : (targetId != null ? targetId.routeKey() : "Unknown Screen");

        FeedbackBanner banner = new FeedbackBanner(
            SemanticTone.ERROR,
            "Unable to Open " + targetLabel,
            detail
        );

        UiCard recoveryCard = new UiCard(UiCard.Variant.DEFAULT);
        recoveryCard.setHeader(
            "Navigation Recovery",
            "Application services remain active and unaffected",
            UiIcons.create(UiIcons.IconType.ALERT_CIRCLE, UiIcons.Size.LG, SemanticTone.ERROR),
            new StatusBadge("Recovered", SemanticTone.WARNING)
        );

        Label explanation = new Label(
            "SyncShare prevented a screen-loading issue from interrupting your session. "
                + "You can retry opening \"" + targetLabel + "\" or return safely to the Dashboard."
        );
        explanation.getStyleClass().add("text-body");
        explanation.setWrapText(true);

        Button retryBtn = UiButtons.create(
            "Retry Screen",
            UiIcons.create(UiIcons.IconType.REFRESH, UiIcons.Size.SM),
            UiButtons.Variant.OUTLINE_TEAL,
            UiButtons.Size.SM
        );
        retryBtn.setDisable(targetId == null || !destinations.containsKey(targetId));
        retryBtn.setOnAction(event -> navigateTo(targetId));

        Button homeBtn = UiButtons.create(
            "Return to Dashboard",
            UiIcons.create(UiIcons.IconType.DASHBOARD, UiIcons.Size.SM),
            UiButtons.Variant.PRIMARY,
            UiButtons.Size.SM
        );
        homeBtn.setOnAction(event -> navigateTo(NavItemId.DASHBOARD));

        HBox actions = new HBox(DesignSystem.SPACE_XS, retryBtn, homeBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);

        recoveryCard.setBodyContent(explanation);
        recoveryCard.setFooterContent(actions);

        container.getChildren().addAll(banner, recoveryCard);
        return container;
    }

    private static String describeError(NavDestination destination, Throwable error) {
        String base = "Could not load view \"" + destination.label() + "\" (" + destination.fxmlPath() + ").";
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            return base + " Reason: " + error.getMessage();
        }
        return base;
    }

    private static LoadedScreen loadFxmlScreen(
        NavDestination destination,
        SyncShareServices services,
        ViewNavigator navigator
    ) throws IOException {
        URL fxmlUrl = ViewNavigator.class.getResource(destination.fxmlPath());
        if (fxmlUrl == null) {
            throw new IllegalStateException("FXML view resource not found: " + destination.fxmlPath());
        }
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();
        Object controller = loader.getController();
        if (controller instanceof NavigationAware aware) {
            aware.initializeContext(services, navigator);
        }
        return new LoadedScreen(root, controller);
    }
}
