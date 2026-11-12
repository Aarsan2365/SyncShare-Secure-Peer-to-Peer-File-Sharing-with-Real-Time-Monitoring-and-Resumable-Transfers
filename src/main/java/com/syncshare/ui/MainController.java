package com.syncshare.ui;

import com.syncshare.model.ConnectionStatus;
import com.syncshare.model.IncomingRequestItem;
import com.syncshare.model.NavItemId;
import com.syncshare.service.SyncShareServices;
import com.syncshare.ui.design.ConnectionStatusPill;
import com.syncshare.ui.design.DesignSystem;
import com.syncshare.ui.design.SemanticTone;
import com.syncshare.ui.design.UiIcons;
import com.syncshare.ui.navigation.NavDestination;
import com.syncshare.ui.navigation.NavigationResult;
import com.syncshare.ui.navigation.SidebarNavigation;
import com.syncshare.ui.navigation.ViewNavigator;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.Objects;

/**
 * Primary desktop shell controller coordinating the application header,
 * reusable sidebar navigation, live connection status indicator, and screen routing.
 */
public class MainController {

    @FXML
    private StackPane brandMarkContainer;

    @FXML
    private SidebarNavigation sidebarNavigation;

    @FXML
    private Region sidebarConnectionDot;

    @FXML
    private Label sidebarFooterTitle;

    @FXML
    private Label sidebarFooterCaption;

    @FXML
    private Label pageTitleLabel;

    @FXML
    private Label pageSubtitleLabel;

    @FXML
    private HBox topbarRightSlot;

    @FXML
    private ConnectionStatusPill connectionStatusPill;

    @FXML
    private Button quickSendHeaderButton;

    @FXML
    private ScrollPane contentScrollPane;

    @FXML
    private StackPane viewHost;

    @FXML
    private Region statusDot;

    @FXML
    private Label statusLabel;

    @FXML
    private Label runtimeInfoLabel;

    private final SyncShareServices services;
    private ViewNavigator viewNavigator;
    private volatile boolean initialized;

    public MainController() {
        this(SyncShareServices.createDefault());
    }

    public MainController(SyncShareServices services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @FXML
    public void initialize() {
        initialized = true;

        if (statusLabel != null) {
            statusLabel.setText("Status: Ready");
        }

        if (runtimeInfoLabel != null) {
            String javaVersion = System.getProperty("java.version", "unknown");
            String javafxVersion = System.getProperty("javafx.version", "21");
            runtimeInfoLabel.setText("Runtime: Java " + javaVersion + " | JavaFX " + javafxVersion);
        }

        configureBrandAndHeader();
        configureNavigation();
        bindGlobalIndicators();

        navigateTo(NavItemId.DASHBOARD);
    }

    private void configureBrandAndHeader() {
        if (brandMarkContainer != null) {
            brandMarkContainer.getChildren().setAll(
                UiIcons.createInverse(UiIcons.IconType.SHIELD_CHECK, UiIcons.Size.LG)
            );
        }

        if (quickSendHeaderButton != null) {
            quickSendHeaderButton.setGraphic(UiIcons.createInverse(UiIcons.IconType.SEND, UiIcons.Size.SM));
            quickSendHeaderButton.setMnemonicParsing(false);
        }
    }

    private void configureNavigation() {
        List<NavDestination> destinations = NavDestination.defaultDestinations();

        if (viewHost != null) {
            viewNavigator = new ViewNavigator(viewHost, services, destinations);
            viewNavigator.setOnNavigateSuccess(destination -> {
                if (sidebarNavigation != null) {
                    sidebarNavigation.setActive(destination.id());
                }
                if (pageTitleLabel != null) {
                    pageTitleLabel.setText(destination.headerTitle());
                }
                if (pageSubtitleLabel != null) {
                    pageSubtitleLabel.setText(destination.headerSubtitle());
                }
                if (contentScrollPane != null) {
                    contentScrollPane.setVvalue(0);
                }
                setStatus("Status: Ready", SemanticTone.SUCCESS);
            });
            viewNavigator.setOnNavigateError((targetId, detail) -> {
                if (sidebarNavigation != null) {
                    sidebarNavigation.setActive(targetId);
                }
                if (pageTitleLabel != null) {
                    pageTitleLabel.setText("Navigation Error");
                }
                if (pageSubtitleLabel != null) {
                    pageSubtitleLabel.setText(detail != null ? detail : "Unable to load requested screen.");
                }
                if (contentScrollPane != null) {
                    contentScrollPane.setVvalue(0);
                }
                setStatus("Status: Navigation Error", SemanticTone.ERROR);
            });
        }

        if (sidebarNavigation != null) {
            sidebarNavigation.configure(destinations, this::navigateTo);
        }
    }

    private void bindGlobalIndicators() {
        services.connectionService().statusProperty().addListener((obs, oldVal, newVal) ->
            updateConnectionIndicators(newVal)
        );
        updateConnectionIndicators(services.connectionService().getStatus());

        services.transferMonitoringService().activeCountProperty().addListener((obs, oldVal, count) -> {
            if (sidebarNavigation != null) {
                sidebarNavigation.setBadgeCount(NavItemId.ACTIVE_TRANSFERS, count.intValue());
            }
        });
        if (sidebarNavigation != null) {
            sidebarNavigation.setBadgeCount(
                NavItemId.ACTIVE_TRANSFERS,
                services.transferMonitoringService().activeCountProperty().get()
            );
        }

        services.connectionService().getPendingRequests().addListener(
            (ListChangeListener<IncomingRequestItem>) change -> updatePendingReceiveBadge()
        );
        updatePendingReceiveBadge();
    }

    private void updatePendingReceiveBadge() {
        if (sidebarNavigation != null) {
            sidebarNavigation.setBadgeCount(
                NavItemId.RECEIVE_FILES,
                services.connectionService().getPendingRequests().size()
            );
        }
    }

    private void updateConnectionIndicators(ConnectionStatus status) {
        if (status == null) {
            return;
        }
        if (connectionStatusPill != null) {
            connectionStatusPill.setStatus(status);
        }
        if (sidebarConnectionDot != null) {
            DesignSystem.swapExclusiveClass(
                sidebarConnectionDot,
                SemanticTone.allDotClasses(),
                status.state().tone().dotStyleClass()
            );
        }
        if (sidebarFooterTitle != null) {
            sidebarFooterTitle.setText(status.headline());
        }
        if (sidebarFooterCaption != null) {
            sidebarFooterCaption.setText(
                status.tlsConfigured()
                    ? "Mutual TLS 1.3 • SHA-256 Verified"
                    : "Configure TLS certs for peer listening"
            );
        }
    }

    @FXML
    private void handleQuickSend() {
        navigateTo(NavItemId.SEND_FILES);
    }

    public NavigationResult navigateTo(NavItemId id) {
        if (viewNavigator == null) {
            return NavigationResult.failure(id, "Navigation router is not initialized.", null);
        }
        return viewNavigator.navigateTo(id);
    }

    public NavDestination getActiveDestination() {
        return viewNavigator != null ? viewNavigator.getCurrentDestination() : null;
    }

    public SidebarNavigation getSidebarNavigation() {
        return sidebarNavigation;
    }

    public ViewNavigator getViewNavigator() {
        return viewNavigator;
    }

    public SyncShareServices getServices() {
        return services;
    }

    public ConnectionStatusPill getConnectionStatusPill() {
        return connectionStatusPill;
    }

    public List<Button> getNavigationButtons() {
        return sidebarNavigation != null ? sidebarNavigation.getNavigationButtons() : List.of();
    }

    public String getPageTitle() {
        return pageTitleLabel != null ? pageTitleLabel.getText() : "";
    }

    public String getPageSubtitle() {
        return pageSubtitleLabel != null ? pageSubtitleLabel.getText() : "";
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getStatusText() {
        return statusLabel != null ? statusLabel.getText() : "";
    }

    private void setStatus(String text, SemanticTone tone) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
        if (statusDot != null && tone != null) {
            DesignSystem.swapExclusiveClass(statusDot, SemanticTone.allDotClasses(), tone.dotStyleClass());
        }
    }

    public void shutdown() {
        setStatus("Status: Shutting down...", SemanticTone.WARNING);
        if (viewNavigator != null) {
            viewNavigator.dispose();
        }
        services.shutdown();
    }
}
