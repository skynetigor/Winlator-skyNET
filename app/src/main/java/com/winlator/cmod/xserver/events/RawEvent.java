package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;

import java.io.IOException;

public class RawEvent extends Event {
    private final byte[] data;

    public RawEvent(byte[] data) {
        super(data[0]);
        this.data = data;
    }

    /**
     * An event delivered for a SendEvent request is the sender's 32 bytes with two fields the server fills in: the
     * "sent" flag in the type byte, and the sequence number of the client it is delivered to. The sender's own
     * sequence field is zero, and a client whose Xlib sits on xcb widens a zero that is behind its count to a
     * number from the far future and aborts ("Unknown sequence number while processing queue"): BeamNG died that
     * way a few seconds into a map, when its browser woke its own event loop with a ClientMessage.
     */
    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        byte[] out = data.clone();
        int type = out[0] & 0x7f;
        if (type != 11 /* KeymapNotify has no sequence number */) {
            out[2] = (byte)sequenceNumber;
            out[3] = (byte)(sequenceNumber >> 8);
        }
        out[0] |= (byte)0x80;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.write(out);
        }
    }
}
