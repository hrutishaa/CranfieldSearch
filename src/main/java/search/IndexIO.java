package search;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Binary index format. Doc ids and positions are stored as gaps from the previous value and
 * written as variable-length ints (7 bits per byte), so small gaps take one byte instead of four.
 */
public final class IndexIO {
    private static final int MAGIC = 0x43524E31; // "CRN1"

    private IndexIO() {}

    public static void write(InvertedIndex idx, Path file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            write(idx, out);
        }
    }

    public static void write(InvertedIndex idx, OutputStream os) throws IOException {
        DataOutputStream out = os instanceof DataOutputStream d ? d : new DataOutputStream(os);
        out.writeInt(MAGIC);
        out.writeBoolean(idx.stemmed);
        out.writeInt(idx.docCount());
        for (int d = 0; d < idx.docCount(); d++) {
            writeVInt(out, idx.externalIds[d]);
            writeVInt(out, idx.docLengths[d]);
            out.writeUTF(idx.titles[d]);
        }
        out.writeInt(idx.termCount());
        for (int t = 0; t < idx.termCount(); t++) {
            out.writeUTF(idx.terms[t]);
            int[] docs = idx.postingDocs[t];
            writeVInt(out, docs.length);
            int prevDoc = 0;
            for (int i = 0; i < docs.length; i++) {
                writeVInt(out, docs[i] - prevDoc);
                prevDoc = docs[i];
                int[] pos = idx.postingPositions[t][i];
                writeVInt(out, pos.length); // tf == number of positions
                int prevPos = 0;
                for (int p : pos) {
                    writeVInt(out, p - prevPos);
                    prevPos = p;
                }
            }
        }
        out.flush();
    }

    public static InvertedIndex read(Path file) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return read(in);
        }
    }

    public static InvertedIndex read(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(is);
        if (in.readInt() != MAGIC) throw new IOException("not a CranfieldSearch index");
        boolean stemmed = in.readBoolean();
        int n = in.readInt();
        int[] ext = new int[n], len = new int[n];
        String[] titles = new String[n];
        for (int d = 0; d < n; d++) {
            ext[d] = readVInt(in);
            len[d] = readVInt(in);
            titles[d] = in.readUTF();
        }
        int tc = in.readInt();
        String[] terms = new String[tc];
        int[][] pd = new int[tc][], pt = new int[tc][];
        int[][][] pp = new int[tc][][];
        for (int t = 0; t < tc; t++) {
            terms[t] = in.readUTF();
            int df = readVInt(in);
            pd[t] = new int[df];
            pt[t] = new int[df];
            pp[t] = new int[df][];
            int doc = 0;
            for (int i = 0; i < df; i++) {
                doc += readVInt(in);
                pd[t][i] = doc;
                int tf = readVInt(in);
                pt[t][i] = tf;
                int[] pos = new int[tf];
                int p = 0;
                for (int j = 0; j < tf; j++) {
                    p += readVInt(in);
                    pos[j] = p;
                }
                pp[t][i] = pos;
            }
        }
        return new InvertedIndex(terms, pd, pt, pp, len, ext, titles, stemmed);
    }

    static void writeVInt(DataOutputStream out, int v) throws IOException {
        if (v < 0) throw new IllegalArgumentException("negative gap " + v);
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVInt(DataInputStream in) throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 28) throw new IOException("malformed varint");
        }
    }
}
