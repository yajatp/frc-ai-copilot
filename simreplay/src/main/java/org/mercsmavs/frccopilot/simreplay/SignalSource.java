package org.mercsmavs.frccopilot.simreplay;

import java.util.List;
import org.mercsmavs.frccopilot.ingest.WpilogReader;

/**
 * Supplies decoded samples for a named signal. A {@link WpilogReader} is one implementation
 * ({@code reader::read}); tests provide an in-memory source. Keeping assertions defined against
 * this interface lets the whole verification framework be tested without a real log or natives.
 */
@FunctionalInterface
public interface SignalSource {
    List<WpilogReader.Sample> read(String name);
}
