package com.syncshare.network;

public final class Protocol {

    private Protocol() {
        // Prevent object creation
    }

    public static final int MAGIC = 0x53595348; // SYSH
    public static final int VERSION = 2;

    public static final byte FILE_OFFER = 1;
    public static final byte ACCEPT = 2;
    public static final byte REJECT = 3;
    public static final byte FILE_CHUNK = 4;
    public static final byte CHUNK_ACK = 5;
    public static final byte RESUME_REQUEST = 6;
    public static final byte COMPLETE = 7;
    public static final byte VERIFIED = 8;
    public static final byte ERROR = 9;

    public static final int BUFFER_SIZE = 64 * 1024;
    public static final long MAX_FILE_SIZE =
            1024L * 1024 * 1024;
}

