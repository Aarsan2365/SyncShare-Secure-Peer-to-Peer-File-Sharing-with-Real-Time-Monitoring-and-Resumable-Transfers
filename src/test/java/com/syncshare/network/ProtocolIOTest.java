package com.syncshare.network;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolIOTest {
    @Test
    void acceptsValidProtocolV2Header() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        ProtocolIO.writeHeader(output);
        ProtocolIO.writeMessageType(output, Protocol.FILE_OFFER);

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        assertDoesNotThrow(() -> ProtocolIO.readHeader(input));
        assertDoesNotThrow(() ->
                ProtocolIO.expectMessageType(input, Protocol.FILE_OFFER));
    }

    @Test
    void rejectsInvalidMagic() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x11111111);
        output.writeInt(Protocol.VERSION);
        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        assertThrows(IOException.class, () -> ProtocolIO.readHeader(input));
    }

    @Test
    void rejectsUnsupportedVersion() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(Protocol.MAGIC);
        output.writeInt(999);
        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        assertThrows(IOException.class, () -> ProtocolIO.readHeader(input));
    }

    @Test
    void rejectsUnexpectedMessageType() throws Exception {
        byte[] bytes = {Protocol.REJECT};
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        assertThrows(IOException.class,
                () -> ProtocolIO.expectMessageType(input, Protocol.FILE_CHUNK));
    }
}
