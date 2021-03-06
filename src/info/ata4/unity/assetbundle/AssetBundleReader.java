/*
 ** 2014 September 25
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.unity.assetbundle;

import info.ata4.io.DataReader;
import info.ata4.io.socket.Sockets;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import static java.nio.file.StandardOpenOption.READ;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.contrapunctus.lzma.LzmaInputStream;
import org.apache.commons.io.input.BoundedInputStream;

/**
 * Streaming reader for Unity asset bundles.
 * 
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class AssetBundleReader implements Closeable, Iterable<AssetBundleEntry> {
    
    private final DataReader in;
    private final AssetBundleHeader header = new AssetBundleHeader();
    private final List<AssetBundleEntryInfo> entries = new ArrayList<>();
    
    private DataReader inData;

    public AssetBundleReader(Path file) throws AssetBundleException, IOException {
        in = new DataReader(Sockets.forFile(file, READ));
        in.readStruct(header);

        // check signature
        if (!header.hasValidSignature()) {
            throw new AssetBundleException("Invalid signature");
        }
        
        createDataReader();
        
        int files = inData.readInt();
        for (int i = 0; i < files; i++) {
            AssetBundleEntryInfo entry = new AssetBundleEntryInfo();
            entry.read(inData);
            entries.add(entry);
        }
        
        // sort entries by offset, which is the order in which they appear in
        // the file, which is required for streaming
        Collections.sort(entries, new EntryComparator());
    }
    
    private void createDataReader() throws IOException {
        // close old reader
        if (inData != null) {
            inData.close();
        }
        
        in.position(header.getHeaderSize());
        
        InputStream is = in.getSocket().getInputStream();
        
        // wrap around LZMA stream if the bundle is compressed
        if (header.isCompressed()) {
            is = new LzmaInputStream(new BufferedInputStream(is));
        }
        
        inData = new DataReader(Sockets.forInputStream(is));
    }

    public AssetBundleHeader getHeader() {
        return header;
    }
    
    public List<AssetBundleEntryInfo> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public void close() throws IOException {
        inData.close();
        in.close();
    }

    @Override
    public Iterator<AssetBundleEntry> iterator() {
        return new EntryIterator();
    }
    
    private class EntryComparator implements Comparator<AssetBundleEntryInfo> {

        @Override
        public int compare(AssetBundleEntryInfo o1, AssetBundleEntryInfo o2) {
            long ofs1 = o1.getOffset();
            long ofs2 = o2.getOffset();

            if (ofs1 > ofs2) {
                return 1;
            } else if (ofs1 < ofs2) {
                return -1;
            } else {
                return 0;
            }
        }
    }
    
    private class EntryIterator implements Iterator<AssetBundleEntry> {
        
        private final Iterator<AssetBundleEntryInfo> iterator = entries.iterator();

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public AssetBundleEntry next() {
            AssetBundleEntryInfo info = iterator.next();
            
            try {                
                // recreate data reader if the offset is behind
                if (inData.position() > info.getOffset()) {
                    createDataReader();
                }
                
                // skip to next entry
                inData.position(info.getOffset());
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            
            InputStream is = new BoundedInputStream(inData.getSocket().getInputStream(), info.getSize());
            
            return new AssetBundleEntry(info.getName(), info.getSize(), is);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
        
    }
}
