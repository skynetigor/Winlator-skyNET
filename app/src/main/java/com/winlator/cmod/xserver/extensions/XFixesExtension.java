package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;

import java.io.IOException;

/**
 * XFIXES, answered as "version 5.0, nothing to report". SDL (and so BeamNG.drive) only starts handling Expose, Enter and
 * FocusIn events once the extension is there; what it uses it for (cursor hiding, selection and cursor notifications,
 * regions for window shapes, pointer barriers) either has no effect here or can be ignored. Requests with a reply get an
 * empty answer; the others are dropped.
 */
public class XFixesExtension implements Extension {
    public static final byte MAJOR_OPCODE = -112;
    private static final byte FIRST_EVENT_ID = 100;
    private static final byte FIRST_ERROR_ID = (byte)170;

    private static final int QUERY_VERSION = 0;
    private static final int GET_CURSOR_IMAGE = 4;
    private static final int FETCH_REGION = 19;
    private static final int GET_CURSOR_NAME = 24;
    private static final int GET_CURSOR_IMAGE_AND_NAME = 25;
    private static final int GET_CLIENT_DISCONNECT_MODE = 34;

    @Override
    public String getName() {
        return "XFIXES";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return FIRST_ERROR_ID;
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT_ID;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int minor = client.getRequestData() & 0xff;
        client.skipRequest();

        switch (minor) {
            case QUERY_VERSION:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeInt(5); // major
                    outputStream.writeInt(0); // minor
                    outputStream.writePad(16);
                }
                break;
            case GET_CURSOR_IMAGE: {
                // A 1x1 transparent cursor image.
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(1); // one pixel
                    outputStream.writeShort((short)0); // x
                    outputStream.writeShort((short)0); // y
                    outputStream.writeShort((short)1); // width
                    outputStream.writeShort((short)1); // height
                    outputStream.writeShort((short)0); // xhot
                    outputStream.writeShort((short)0); // yhot
                    outputStream.writeInt(0); // cursor serial
                    outputStream.writePad(8);
                    outputStream.writeInt(0); // the pixel
                }
                break;
            }
            case FETCH_REGION:
            case GET_CURSOR_NAME:
            case GET_CURSOR_IMAGE_AND_NAME:
            case GET_CLIENT_DISCONNECT_MODE:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writePad(24);
                }
                break;
            default:
                // Selection and cursor notifications, regions, window shapes, cursor hiding, pointer barriers: nothing to do.
                break;
        }
    }
}
