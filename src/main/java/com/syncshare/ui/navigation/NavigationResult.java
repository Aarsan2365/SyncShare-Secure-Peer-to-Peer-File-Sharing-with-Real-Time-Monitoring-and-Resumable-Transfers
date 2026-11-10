package com.syncshare.ui.navigation;

import com.syncshare.model.NavItemId;

/**
 * Outcome of a navigation transition request.
 */
public record NavigationResult(
    boolean success,
    NavItemId destination,
    String errorMessage,
    Throwable error
) {
    public static NavigationResult ok(NavItemId destination) {
        return new NavigationResult(true, destination, null, null);
    }

    public static NavigationResult failure(NavItemId destination, String errorMessage, Throwable error) {
        return new NavigationResult(false, destination, errorMessage, error);
    }
}
