package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;

import java.io.IOException;

public class FocusEvent extends Event {
    public static final byte DETAIL_NONLINEAR = 3;
    public static final byte MODE_NORMAL = 0;
    private final byte detail;
    private final Window window;
    private final byte mode;

    public FocusEvent(boolean focusIn, byte detail, Window window, byte mode) {
        super(focusIn ? 9 : 10);
        this.detail = detail;
        this.window = window;
        this.mode = mode;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(detail);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(window.id);
            outputStream.writeByte(mode);
            outputStream.writePad(23);
        }
    }
}
