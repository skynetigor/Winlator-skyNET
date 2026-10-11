package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;

import java.io.IOException;

/** The state of all keys, sent after an EnterNotify or FocusIn to windows that selected KeymapStateMask. No key is ever held here. */
public class KeymapNotify extends Event {
    public KeymapNotify() {
        super(11);
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writePad(31);
        }
    }
}
