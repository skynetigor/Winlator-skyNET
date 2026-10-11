package com.winlator.cmod.xserver.extensions;

import android.util.SparseArray;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.ShmFence;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadFence;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public class SyncExtension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -104;
    private final SparseArray<SyncFence> fences = new SparseArray<SyncFence>();
    private XServer xserver;

    private static abstract class ClientOpcodes {
        private static final byte CREATE_FENCE = 14;
        private static final byte TRIGGER_FENCE = 15;
        private static final byte RESET_FENCE = 16;
        private static final byte DESTROY_FENCE = 17;
        private static final byte AWAIT_FENCE = 19;
    }
    
    private class SyncFence {
        int fenceId;
        int drawableId;
        boolean triggered;
        long shm;  // the client's shared word for a fence made by DRI3 FenceFromFD, 0 for one made by CreateFence
    }
    
    public SyncExtension(XServer xserver) {
        this.xserver = xserver;
        this.xserver.pixmapManager.addOnResourceLifecycleListener(this);
        this.xserver.windowManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "SYNC";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    public void setTriggered(int id) {
        synchronized (fences) {
            if (fences.indexOfKey(id) >= 0) {
                SyncFence fence = fences.get(id);
                fence.triggered = true;
                if (fence.shm != 0) ShmFence.trigger(fence.shm);
            }
        }
    }

    /** DRI3 FenceFromFD: a fence whose state lives in memory the client shares with the server. */
    public void createShmFence(int fenceId, int drawableId, int fd, boolean initiallyTriggered) throws XRequestError {
        synchronized (fences) {
            if (fences.indexOfKey(fenceId) >= 0) throw new BadIdChoice(fenceId);
            SyncFence fence = new SyncFence();
            fence.drawableId = drawableId;
            fence.fenceId = fenceId;
            fence.shm = ShmFence.map(fd);
            fence.triggered = initiallyTriggered;
            fences.put(fenceId, fence);
            if (initiallyTriggered && fence.shm != 0) ShmFence.trigger(fence.shm);
        }
    }

    private void createFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int drawableId = inputStream.readInt();
            int id = inputStream.readInt();

            if (fences.indexOfKey(id) >= 0) throw new BadIdChoice(id);

            boolean initiallyTriggered = inputStream.readByte() == 1;
            inputStream.skip(3);
            
            SyncFence fence = new SyncFence();
            fence.drawableId = drawableId;
            fence.fenceId = id;
            fence.triggered = initiallyTriggered;
            fences.put(id, fence);
        }
    }

    private void triggerFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            SyncFence fence = fences.get(id);
            fence.triggered = true;
            if (fence.shm != 0) ShmFence.trigger(fence.shm);
        }
    }

    private void resetFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);

            SyncFence fence = fences.get(id);
            if (!fence.triggered) throw new BadMatch();
            
            fence.triggered = false;
        }
    }

    private void destroyFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int id = inputStream.readInt();
            if (fences.indexOfKey(id) < 0) throw new BadFence(id);
            SyncFence fence = fences.get(id);
            if (fence.shm != 0) ShmFence.unmap(fence.shm);
            fences.delete(id);
        }
    }

    private void awaitFence(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        synchronized (fences) {
            int length = client.getRemainingRequestLength();
            int[] ids = new int[length / 4];
            int i = 0;

            while (length != 0) {
                ids[i++] = inputStream.readInt();
                length -= 4;
            }

            boolean anyTriggered = false;
            do {
                for (int id : ids) {
                    if (fences.indexOfKey(id) < 0) throw new BadFence(id);
                    SyncFence fence = fences.get(id);
                    anyTriggered = fence.triggered;
                    if (anyTriggered) break;
                }
            }
            while (!anyTriggered);
        }
    }
    
    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) {
            Pixmap pixmap = (Pixmap) resource;
            for (int i = 0; i < fences.size(); i++) {
                int key = fences.keyAt(i);
                SyncFence fence = fences.get(key);
                if (fence.drawableId == pixmap.id)
                    fences.remove(fence.fenceId);
            }
        } else if (resource instanceof Window) {
            Window window = (Window) resource;
            for (int i = 0; i < fences.size(); i++) {
                int key = fences.keyAt(i);
                SyncFence fence = fences.get(key);
                if (fence.drawableId == window.id) 
                    fences.remove(fence.fenceId);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.CREATE_FENCE :
                createFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.TRIGGER_FENCE:
                triggerFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.RESET_FENCE:
                resetFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_FENCE:
                destroyFence(client, inputStream, outputStream);
                break;
            case ClientOpcodes.AWAIT_FENCE:
                awaitFence(client, inputStream, outputStream);
                break;
            case 0: { // Initialize: libXext / SDL ask for the version before anything else
                client.skipRequest();
                try (com.winlator.cmod.xconnector.XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeByte((byte)3);
                    outputStream.writeByte((byte)1);
                    outputStream.writePad(22);
                }
                break;
            }
            case 1: // ListSystemCounters: none
                client.skipRequest();
                try (com.winlator.cmod.xconnector.XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeInt(0);
                    outputStream.writePad(20);
                }
                break;
            case 5: // QueryCounter: counters are accepted but not tracked, so report zero
                client.skipRequest();
                try (com.winlator.cmod.xconnector.XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeInt(0);
                    outputStream.writeInt(0);
                    outputStream.writePad(16);
                }
                break;
            case 2: case 3: case 4: case 6: case 7: case 8: case 10: case 11: case 12: // counter / alarm / priority requests without reply
                client.skipRequest();
                break;
            default:
                throw new BadImplementation();
        }
    }
}
