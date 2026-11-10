package com.syncshare.ui.navigation;

import com.syncshare.service.SyncShareServices;

/**
 * Contract implemented by screen controllers that receive shared application
 * services and navigation routing without recreating services on view switches.
 */
public interface NavigationAware {

    void initializeContext(SyncShareServices services, ViewNavigator navigator);

    default void onNavigatedTo() {
    }
}
