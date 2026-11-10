package com.syncshare.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ProtocolIO {

    private ProtocolIO() {
    }

    public static void writeHeader(
            DataOutputStream output) throws IOException {

        output.writeInt(Protocol.MAGIC);
        output.writeInt(Protocol.VERSION);
    }

    public static void readHeader(
            DataInputStream input) throws IOException {

        int magic = input.readInt();
        int version = input.readInt();

        if (magic != Protocol.MAGIC) {
            throw new IOException("Invalid SyncShare protocol");
        }

        if (version != Protocol.VERSION) {
            throw new IOException(
                "Unsupported protocol version: " + version
            );
        }
    }

    public static void writeMessageType(
            DataOutputStream output,
            byte type) throws IOException {

        output.writeByte(type);
    }

    public static void expectMessageType(
            DataInputStream input,
            byte expectedType) throws IOException {

        byte actualType = input.readByte();

        if (actualType != expectedType) {
            throw new IOException(
                "Unexpected message type: " + actualType
            );
        }
    }
}

