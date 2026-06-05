package dev.nuclr.plugin.core.quick.viewer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public class CancelableInputStream extends FilterInputStream {

    private final AtomicBoolean cancelled;

    public CancelableInputStream(InputStream in, AtomicBoolean cancelled) {
        super(in);
        this.cancelled = cancelled;
    }

    private void checkCancelled() {
        if (cancelled != null && cancelled.get()) {
            throw new CancellationException("Image loading cancelled");
        }
    }

    @Override
    public int read() throws IOException {
        checkCancelled();
        int result = super.read();
        checkCancelled();
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        checkCancelled();
        int result = super.read(b, off, len);
        checkCancelled();
        return result;
    }

    @Override
    public long skip(long n) throws IOException {
        checkCancelled();
        long result = super.skip(n);
        checkCancelled();
        return result;
    }
}