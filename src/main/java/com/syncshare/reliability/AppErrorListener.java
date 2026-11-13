package com.syncshare.reliability;

/**
 * Listener receiving categorized application errors dispatched from any thread.
 */
@FunctionalInterface
public interface AppErrorListener {
    void onError(AppError error);
}
