package emissary.core.blob;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import emissary.config.ConfigUtil;
import emissary.config.Configurator;
import emissary.core.blob.WrappedSeekableByteChannel.TriggeredAction;

/**
 * {@link IDataContainer} proxy for another {@link IDataContainer} appropriate for the size of data being handled.
 *
 * @author adyoun2
 *
 */
public class SelectingDataContainer implements IDataContainer, IFileProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SelectingDataContainer.class);

    /**
     * Configuration store of implementations and their configured maximum sizes.
     *
     * @author adyoun2
     *
     */
    private static final class ContainerMaximum implements Comparable<ContainerMaximum> {
        private final Class<? extends IDataContainer> impl;
        private final long max;

        public ContainerMaximum(Class<? extends IDataContainer> impl, long max) {
            this.impl = impl;
            this.max = max;
        }

        @Override
        public int compareTo(ContainerMaximum o) {
            return Long.compare(max, o.max);
        }

        @Override
        public String toString() {
            return impl.getName() + " < " + max;
        }
    }

    /**
     *
     */
    private static final long serialVersionUID = 6341132735027189150L;
    private static SortedSet<ContainerMaximum> containers;
    private static int maxArrayLength = Integer.MAX_VALUE;

    /**
     * Load the config.
     */
    private static void initConfig() {
        containers = new TreeSet<>();
        try {
            Configurator config = ConfigUtil.getConfigInfo(SelectingDataContainer.class);
            int count = config.findIntEntry("payload.containerCount", 1);
            for (int i = 0; i < count; i++) {
                @SuppressWarnings("unchecked")
                Class<IDataContainer> c = (Class<IDataContainer>) Class
                        .forName(config.findStringEntry("payload.container." + i + ".class", MemoryDataContainer.class.getName()));
                long maxMemoryLength = config.findLongEntry("payload.container." + i + ".maxSize", Integer.MAX_VALUE);
                containers.add(new ContainerMaximum(c, maxMemoryLength));
            }
            maxArrayLength = config.findIntEntry("payload.maxArrayLength", Integer.MAX_VALUE);
        } catch (IOException | ClassNotFoundException e) {
            LOG.warn("Failed to load config, using default.", e);
            if (containers.isEmpty()) {
                containers.add(new ContainerMaximum(MemoryDataContainer.class, Integer.MAX_VALUE));
            }
        }
        LOG.info("Config loaded:\n{}", containers);
    }

    /**
     * The currently used container, initialised to an empty on-heap memory container.
     */
    private IDataContainer actualContainer = new MemoryDataContainer();

    @Override
    public byte[] data() {
        if (length() > maxArrayLength) {
            throw new DataException("Data exceeds the maximum size configured for array usage, size=" + length() + " max=" + maxArrayLength);
        }
        return actualContainer.data();
    }

    @Override
    public void setData(byte[] newData) {
        if (newData == null) {
            switchToAppropriateImpl(0);
            actualContainer.setData(newData);
            return;
        }
        int newSize = newData.length;
        switchToAppropriateImpl(newSize);
        actualContainer.setData(newData);
    }

    @Override
    public void setData(byte[] newData, int offset, int length) {
        if (newData == null) {
            switchToAppropriateImpl(0);
            actualContainer.setData(newData);
            return;
        }
        switchToAppropriateImpl(length);
        actualContainer.setData(newData, offset, length);

    }

    @Override
    public long length() {
        return actualContainer.length();
    }

    @Override
    public ByteBuffer dataBuffer() {
        return actualContainer.dataBuffer();
    }

    @Override
    public SelectingDataContainer clone() throws CloneNotSupportedException {
        SelectingDataContainer clone = (SelectingDataContainer) super.clone();
        clone.actualContainer = actualContainer.clone();
        return clone;
    }

    @Override
    public SeekableByteChannel channel() throws IOException {
        WrappedSeekableByteChannel<SeekableByteChannel> channel = new WrappedSeekableByteChannel<>(actualContainer.channel());
        TriggeredAction<SeekableByteChannel> changeImplOnSize = getImplSwitchTrigger(channel);
        channel.setWriteAction(changeImplOnSize);
        return channel;
    }

    @Override
    public SeekableByteChannel newChannel(long estimatedSize) throws IOException {
        switchToAppropriateImpl(estimatedSize);
        WrappedSeekableByteChannel<SeekableByteChannel> channel = new WrappedSeekableByteChannel<>(actualContainer.newChannel(estimatedSize));
        TriggeredAction<SeekableByteChannel> changeImplOnSize = getImplSwitchTrigger(channel);
        channel.setWriteAction(changeImplOnSize);
        return channel;
    }

    /**
     * Trigger on channel growing to the point where the appropriate container changes, with some threshold. When it
     * does copy the data to the appropriate impl and replace the backing channel.
     *
     * @param channel The wrapped channel to detect growth on.
     * @return A trigger to apply to the channel.
     */
    private TriggeredAction<SeekableByteChannel> getImplSwitchTrigger(WrappedSeekableByteChannel<SeekableByteChannel> channel) {
        return sourceChannel -> {
            long size = sourceChannel.size();
            Class<? extends IDataContainer> newClass = determineClassForSize((long) (size * 0.8));
            if (newClass != actualContainer.getClass()) {
                LOG.debug("Channel has grown too large for {} with padding, swiitching impl", actualContainer.getClass());
                switchToAppropriateImpl(size);
                long endPos = sourceChannel.position();
                sourceChannel.position(0);
                SeekableByteChannel newChannel = actualContainer.newChannel(size);
                IOUtils.copyLarge(Channels.newInputStream(sourceChannel), Channels.newOutputStream(newChannel));
                sourceChannel.close();
                newChannel.position(endPos);
                channel.replaceBaseChannel(newChannel);
            }
        };
    }

    @Override
    public File getFile() {
        if (actualContainer instanceof IFileProvider) {
            return ((IFileProvider) actualContainer).getFile();
        }
        return null;
    }

    /**
     * Switch the implementation to the appropriate class.
     *
     * @param newSize The expected size of the data.
     */
    private void switchToAppropriateImpl(long newSize) {
        Class<? extends IDataContainer> appropriateClass = determineClassForSize(newSize);
        // Reference equality is appropriate
        if (actualContainer == null || actualContainer.getClass() != appropriateClass) {
            try {
                LOG.debug("Switching container from {} to {}", actualContainer.getClass().getName(), appropriateClass.getName());
                actualContainer = appropriateClass.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                LOG.error("Unable to instantiate container", e);
            }
        }
    }

    /**
     * Lookup the appropriate implementation.
     *
     * @param length The expected size of the data.
     * @return The appropriate implementation.
     */
    private Class<? extends IDataContainer> determineClassForSize(long length) {
        if (containers == null) {
            initConfig();
        }
        Class<? extends IDataContainer> result = null;
        for (ContainerMaximum cont : containers) {
            result = cont.impl;
            if (cont.max >= length) {
                return result;
            }
        }
        throw new DataException("No available IDataContainers for size " + length + ". Containers: " + containers);
    }

    /**
     * For use in testing only
     *
     * @return
     */
    IDataContainer getActualContainer() {
        return this.actualContainer;
    }

}