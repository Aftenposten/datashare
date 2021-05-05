package org.icij.datashare;

import com.google.inject.Injector;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

import java.io.Closeable;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.google.inject.Guice.createInjector;
import static org.icij.datashare.user.User.nullUser;

public class BatchSearchApp {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final BatchSearchRunner batchSearchRunner;
    final BlockingQueue<String> batchSearchQueue;
    public static final String POISON = "poison";
    private volatile String currentBatchId = null;

    public BatchSearchApp(BatchSearchRunner batchSearchRunner, BlockingQueue<String> batchSearchQueue) {
        this.batchSearchRunner = batchSearchRunner;
        this.batchSearchQueue = batchSearchQueue;
        Signal.handle(new Signal("TERM"), signal -> {
            batchSearchQueue.add(POISON);
            batchSearchRunner.cancel();
        });
    }

    public static BatchSearchApp create(Properties properties) {
        Injector injector = createInjector(CommonMode.create(properties));
        return new BatchSearchApp(
                new BatchSearchRunner(injector.getInstance(Indexer.class),
                        injector.getInstance(BatchSearchRepository.class),
                        injector.getInstance(PropertiesProvider.class), nullUser()),
                injector.getInstance(BlockingQueue.class));
    }

    public static void start(Properties properties) throws Exception {
        BatchSearchApp batchSearchApp = create(properties);
        batchSearchApp.run();
        batchSearchApp.close();
    }

    public void run() {
        logger.info("Datashare running in batch mode. Waiting batch from ds:batchsearch.queue ({})", batchSearchQueue.getClass());
        while (! POISON.equals(currentBatchId)) {
            try {
                currentBatchId = null;
                currentBatchId = batchSearchQueue.poll(60, TimeUnit.SECONDS);
                if (currentBatchId != null && !POISON.equals(currentBatchId)) {
                    batchSearchRunner.run(currentBatchId);
                }
            } catch (JooqBatchSearchRepository.BatchNotFoundException notFound) {
                logger.warn("batch was not executed : {}", notFound.toString());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (RuntimeException rex) {
                logger.error("error during main loop", rex);
            }
        }
        logger.info("exiting main loop");
    }

    private void close() throws IOException {
        batchSearchRunner.close();
        if (batchSearchQueue instanceof Closeable) {
            ((Closeable) batchSearchQueue).close();
        }
    }
}
