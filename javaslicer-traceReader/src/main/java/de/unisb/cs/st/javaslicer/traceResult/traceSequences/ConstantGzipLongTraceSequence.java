/** License information:
 *    Component: javaslicer-traceReader
 *    Package:   de.unisb.cs.st.javaslicer.traceResult.traceSequences
 *    Class:     ConstantGzipLongTraceSequence
 *    Filename:  javaslicer-traceReader/src/main/java/de/unisb/cs/st/javaslicer/traceResult/traceSequences/ConstantGzipLongTraceSequence.java
 *
 * This file is part of the JavaSlicer tool, developed by Clemens Hammacher at Saarland University.
 * See http://www.st.cs.uni-saarland.de/javaslicer/ for more information.
 *
 * This work is licensed under the Creative Commons Attribution-ShareAlike 3.0 Unported License.
 * To view a copy of this license, visit http://creativecommons.org/licenses/by-sa/3.0/ or send a
 * letter to Creative Commons, 171 Second Street, Suite 300, San Francisco, California, 94105, USA.
 */
package de.unisb.cs.st.javaslicer.traceResult.traceSequences;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.zip.GZIPInputStream;

import de.hammacher.util.MultiplexedFileReader;
import de.hammacher.util.MultiplexedFileReader.MultiplexInputStream;
import de.hammacher.util.iterators.EmptyIterator;
import de.hammacher.util.streams.OptimizedDataInputStream;
import de.unisb.cs.st.javaslicer.traceResult.traceSequences.ConstantTraceSequence.ConstantLongTraceSequence;

public class ConstantGzipLongTraceSequence implements ConstantLongTraceSequence {

    protected final MultiplexedFileReader file;
    protected final boolean gzipped;
    protected final int streamIndex;

    public ConstantGzipLongTraceSequence(final MultiplexedFileReader file, final boolean gzipped, final int streamIndex) {
        this.file = file;
        this.gzipped = gzipped;
        this.streamIndex = streamIndex;
    }

    @Override
	public Iterator<Long> backwardIterator() {
        try {
            return this.gzipped ? new GZippedBackwardIterator(this.file, this.streamIndex)
                : new NoGzipBackwardIterator(this.file, this.streamIndex);
        } catch (final IOException e) {
            return EmptyIterator.getInstance();
        }
    }

    @Override
	public ListIterator<Long> iterator() {
        throw new UnsupportedOperationException();
    }

    public static ConstantGzipLongTraceSequence readFrom(final DataInput in, final MultiplexedFileReader file,
            final byte format)
            throws IOException {
        final boolean gzipped = (format & 1) == 1;
        final int streamIndex = in.readInt();
        if (!file.hasStreamId(streamIndex))
            throw new IOException("corrupted data");
        return new ConstantGzipLongTraceSequence(file, gzipped, streamIndex);
    }

    private static class GZippedBackwardIterator implements Iterator<Long> {

        private final MultiplexInputStream multiplexedStream;
        private final OptimizedDataInputStream dataIn;
        private boolean error;
        private final PushbackInputStream pushBackInput;

        public GZippedBackwardIterator(final MultiplexedFileReader file, final int streamIndex) throws IOException {
            this.multiplexedStream = file.getInputStream(streamIndex);
            final InputStream gzipStream = new BufferedInputStream(new GZIPInputStream(this.multiplexedStream, 512), 512);
            this.pushBackInput = new PushbackInputStream(gzipStream, 1);
            this.dataIn = new OptimizedDataInputStream(this.pushBackInput, true);
        }

        @Override
		public boolean hasNext() {
            if (this.error)
                return false;
            int read;
            try {
                if ((read = this.pushBackInput.read()) != -1) {
                    this.pushBackInput.unread(read);
                    return true;
                }
                return false;
            } catch (final IOException e) {
                this.error = true;
                return false;
            }
        }

        @Override
		public Long next() {
            try {
                return this.dataIn.readLong();
            } catch (final IOException e) {
                this.error = true;
                return null;
            }
        }

        @Override
		public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    private static class NoGzipBackwardIterator implements Iterator<Long> {

        private final MultiplexInputStream multiplexedStream;
        private final OptimizedDataInputStream dataIn;
        private boolean error;

        public NoGzipBackwardIterator(final MultiplexedFileReader file, final int streamIndex) throws IOException {
            this.multiplexedStream = file.getInputStream(streamIndex);
            this.dataIn = new OptimizedDataInputStream(this.multiplexedStream, true);
        }

        @Override
		public boolean hasNext() {
            if (this.error)
                return false;
            try {
                return !this.multiplexedStream.isEOF();
            } catch (final IOException e) {
                this.error = true;
                return false;
            }
        }

        @Override
		public Long next() {
            try {
                return this.dataIn.readLong();
            } catch (final IOException e) {
                this.error = true;
                return null;
            }
        }

        @Override
		public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
