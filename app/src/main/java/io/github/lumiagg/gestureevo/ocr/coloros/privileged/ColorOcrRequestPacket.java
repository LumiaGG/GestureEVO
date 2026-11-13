package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One OCR request from the app to its Shizuku helper: image dimensions plus PNG bytes.
 * Both sides implement the same tiny format (framework-only); tests cover the round trip.
 */
public final class ColorOcrRequestPacket {
    public static final int MAGIC = 0x47435231; // "GCR1"
    public static final int VERSION = 1;
    public static final long MAX_PNG_BYTES = 64L * 1024 * 1024;
    public static final long MAX_PIXELS = 20_000_000L;

    public final int width;
    public final int height;
    public final byte[] png;

    public ColorOcrRequestPacket(int width, int height, byte[] png) throws IOException {
        if (width < 1 || height < 1) throw new IOException("Invalid request dimensions");
        if ((long) width * height > MAX_PIXELS) throw new IOException("Request image exceeds pixel limit");
        if (png == null || png.length == 0 || png.length > MAX_PNG_BYTES) {
            throw new IOException("Invalid request PNG size");
        }
        this.width = width;
        this.height = height;
        this.png = png;
    }

    public void write(OutputStream stream) throws IOException {
        DataOutputStream output = new DataOutputStream(stream);
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(width);
        output.writeInt(height);
        output.writeInt(png.length);
        output.write(png);
        output.flush();
    }

    public static ColorOcrRequestPacket read(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        if (input.readInt() != MAGIC) throw new IOException("Invalid request header");
        int version = input.readInt();
        if (version != VERSION) throw new IOException("Unsupported request version: " + version);
        int width = input.readInt();
        int height = input.readInt();
        int size = input.readInt();
        if (size <= 0 || size > MAX_PNG_BYTES) throw new IOException("Invalid request PNG size");
        byte[] png = new byte[size];
        input.readFully(png);
        if (input.read() != -1) throw new IOException("Unexpected request trailing data");
        return new ColorOcrRequestPacket(width, height, png);
    }
}
