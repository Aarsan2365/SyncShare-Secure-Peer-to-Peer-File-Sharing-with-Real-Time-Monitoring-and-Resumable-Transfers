
package com.syncshare.approval;

import java.io.IOException;
import java.net.InetAddress;

@FunctionalInterface
public interface ApprovalHandler {

    boolean approve(
        String fileName,
        long fileSize,
        InetAddress sender
    ) throws IOException;
}


