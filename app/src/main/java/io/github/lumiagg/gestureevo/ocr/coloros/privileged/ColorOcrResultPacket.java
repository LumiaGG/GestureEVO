package io.github.lumiagg.gestureevo.ocr.coloros.privileged;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One OCR result file from the root helper back to the app: status, message and the raw
 * ocr_ar JSON on success. The app pre-creates the file so it stays owned by the app UID.
 */
public final class ColorOcrResultPacket {
    public static final int MAGIC = 0x47435232; // "GCR2"
    public static final int VERSION = 1;
    public static final long MAX_JSON_BYTES = 16L * 1024 * 1024;

    public static final int STATUS_OK = 0;
    /** Engine/SDK/inference failures. */
    public static final int STATUS_ENGINE = 1;
    /** Malformed request packet or arguments. */
    public static final int STATUS_REQUEST = 2;
    /** First-round authorization did not pass. */
    public static final int STATUS_AUTHORIZED = 3;
    /** Scanner SDK missing, not a system app or an unverified version. */
    public static final int STATUS_SCANNER = 4;

    public final int status;
    public final String message;
    public final String json;

    public ColorOcrResultPacket(int status, String message, String json) throws IOException {
        if (status < STATUS_OK || status > STATUS_SCANNER) throw new IOException("Invalid result status");
        if (json != null && json.length() > MAX_JSON_BYTES) throw new IOException("Result JSON exceeds size limit");
        this.status = status;
        this.message = message == null ? "" : message;
        this.json = json;
    }

    public void write(OutputStream stream) throws IOException {
        DataOutputStream output = new DataOutputStream(stream);
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(status);
        output.writeUTF(message);
        output.writeBoolean(json != null);
        if (json != null) {
            byte[] bytes = json.getBytes("UTF-8");
            output.writeInt(bytes.length);
            output.write(bytes);
        }
        output.flush();
    }

    public static ColorOcrResultPacket read(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        if (input.readInt() != MAGIC) throw new IOException("Invalid result header");
        int version = input.readInt();
        if (version != VERSION) throw new IOException("Unsupported result version: " + version);
        int status = input.readInt();
        String message = input.readUTF();
        String json = null;
        if (input.readBoolean()) {
            int size = input.readInt();
            if (size < 0 || size > MAX_JSON_BYTES) throw new IOException("Invalid result JSON size");
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            json = new String(bytes, "UTF-8");
        }
        if (input.read() != -1) throw new IOException("Unexpected result trailing data");
        return new ColorOcrResultPacket(status, message, json);
    }
}
