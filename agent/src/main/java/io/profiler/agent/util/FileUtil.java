package io.profiler.agent.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** File helpers used by the trace writer. */
public final class FileUtil {
    private static final int BUFFER_SIZE = 1 << 16;

    private FileUtil() {
    }

    /** Compresses {@code f} to {@code f.gz} (via a temp file) and deletes the original on success. */
    public static void gzip(File f) {
        File gz = new File(f.getPath() + ".gz");
        File tmp = new File(f.getPath() + ".gz.tmp");
        try (InputStream in = new FileInputStream(f); OutputStream o = new GZIPOutputStream(new FileOutputStream(tmp), BUFFER_SIZE)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (tmp.renameTo(gz)) f.delete();
        else tmp.delete();
    }

    /** Sorts the list in place, oldest file first. */
    public static void sortByLastModified(List<File> files) {
        File[] a = files.toArray(new File[0]);
        Arrays.sort(a, new Comparator<File>() {
            public int compare(File x, File y) {
                return Long.compare(x.lastModified(), y.lastModified());
            }
        });
        files.clear();
        files.addAll(Arrays.asList(a));
    }
}
