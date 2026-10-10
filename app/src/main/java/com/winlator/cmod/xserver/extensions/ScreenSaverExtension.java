package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;

import java.io.IOException;

/**
 * MIT-SCREEN-SAVER, answered as "no screen saver, never idle". Programs (libXss, Chromium) only ask whether it is
 * there and how long the user has been idle; Android has no screen saver to report.
 */
public class ScreenSaverExtension implements Extension {
    public static final byte MAJOR_OPCODE = -110;
    private static final int QUERY_VERSION = 0;
    private static final int QUERY_INFO = 1;

    @Override
    public String getName() {
        return "MIT-SCREEN-SAVER";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int minor = client.getRequestData() & 0xff;
        inputStream.skip(client.getRequestLength());

        if (minor == QUERY_VERSION) {
            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                outputStream.writeByte((byte)0);
                outputStream.writeShort(client.getSequenceNumber());
                outputStream.writeInt(0);
                outputStream.writeShort((short)1); // server major
                outputStream.writeShort((short)1); // server minor
                outputStream.writePad(20);
            }
        }
        else if (minor == QUERY_INFO) {
            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                outputStream.writeByte((byte)0); // state: off
                outputStream.writeShort(client.getSequenceNumber());
                outputStream.writeInt(0);
                outputStream.writeInt(0); // saver window
                outputStream.writeInt(0); // time until / since
                outputStream.writeInt(0); // idle
                outputStream.writeInt(0); // event mask
                outputStream.writeByte((byte)0); // kind: blanked
                outputStream.writePad(7);
            }
        }
        // SelectInput, SetAttributes, UnsetAttributes and Suspend need no answer.
    }
}
