package org.neo4j.graphalgo.core.utils;

import org.neo4j.collection.primitive.PrimitiveIntIterable;
import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.graphalgo.api.BatchNodeIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.Exceptions;
import org.neo4j.kernel.api.DataWriteOperations;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.exceptions.KernelException;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.internal.GraphDatabaseAPI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class ParallelUtil {

    public static final int DEFAULT_BATCH_SIZE = 10_000;

    public static int threadSize(int batchSize, int elementCount) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Invalid batch size: " + batchSize);
        }
        if (batchSize >= elementCount) {
            return 1;
        }
        return (int) Math.ceil(elementCount / (double) batchSize);
    }

    /**
     * Executes write operations in parallel, based on the given batch size
     * and executor.
     */
    public static void writeParallel(
            int batchSize,
            BatchNodeIterable idMapping,
            GraphDatabaseAPI db,
            ParallelGraphExporter parallelExporter,
            ExecutorService executor) {

        Collection<PrimitiveIntIterable> iterators =
                idMapping.batchIterables(batchSize);

        int threads = iterators.size();

        if (executor == null || threads == 1) {
            writeSequential(db, parallelExporter, iterators.iterator().next());
        } else {
            List<Runnable> tasks = new ArrayList<>(threads);
            for (PrimitiveIntIterable iterator : iterators) {
                tasks.add(new BatchExportRunnable(
                        db,
                        parallelExporter,
                        iterator
                ));
            }
            run(tasks, executor);
        }
    }

    /**
     * Executes read operations in parallel, based on the given batch size
     * and executor.
     */
    public static <T extends Runnable> Collection<T> readParallel(
            int batchSize,
            BatchNodeIterable idMapping,
            ParallelGraphImporter<T> importer,
            ExecutorService executor) {

        Collection<PrimitiveIntIterable> iterators =
                idMapping.batchIterables(batchSize);

        int threads = iterators.size();

        if (executor == null || threads == 1) {
            T task = importer.newImporter(0, iterators.iterator().next());
            task.run();
            return Collections.singleton(task);
        } else {
            List<T> tasks = new ArrayList<>(threads);
            int nodeOffset = 0;
            for (PrimitiveIntIterable iterator : iterators) {
                tasks.add(importer.newImporter(nodeOffset, iterator));
                nodeOffset += batchSize;
            }
            run(tasks, executor);
            return tasks;
        }
    }

    /**
     * Runs a collection of {@link Runnable}s in parallel for their side-effects.
     * The level of parallelism is defined by the given executor.
     * <p>
     * This is similar to {@link ExecutorService#invokeAll(Collection)},
     * except that all Exceptions thrown by any task are chained together.
     */
    public static void run(
            Collection<? extends Runnable> tasks,
            ExecutorService executor) {
        if (tasks.size() == 1) {
            tasks.iterator().next().run();
            return;
        }
        final List<Future<?>> futures = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            futures.add(executor.submit(task));
        }

        boolean done = false;
        Throwable error = null;
        try {
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException ee) {
                    error = Exceptions.chain(error, ee.getCause());
                } catch (CancellationException ignore) {
                }
            }
            done = true;
        } catch (InterruptedException e) {
            error = Exceptions.chain(e, error);
        } finally {
            if (!done) {
                for (final Future<?> future : futures) {
                    future.cancel(true);
                }
            }
        }
        if (error != null) {
            throw Exceptions.launderedException(error);
        }
    }

    private static void writeSequential(
            GraphDatabaseAPI db,
            ParallelGraphExporter parallelExporter,
            PrimitiveIntIterable iterator) {
        new BatchExportRunnable(
                db,
                parallelExporter,
                iterator
        ).run();
    }


    private static final class BatchExportRunnable implements Runnable {
        private final GraphDatabaseAPI db;
        private final ParallelGraphExporter parallelExporter;
        private final PrimitiveIntIterable iterator;
        private final ThreadToStatementContextBridge ctx;

        private BatchExportRunnable(
                final GraphDatabaseAPI db,
                final ParallelGraphExporter parallelExporter,
                final PrimitiveIntIterable iterator) {
            this.db = db;
            this.parallelExporter = parallelExporter;
            this.iterator = iterator;
            this.ctx = db
                    .getDependencyResolver()
                    .resolveDependency(ThreadToStatementContextBridge.class);
        }

        @Override
        public void run() {
            GraphExporter exporter = parallelExporter.newExporter();
            try (Transaction tx = db.beginTx();
                 Statement statement = ctx.get()) {
                DataWriteOperations ops = statement.dataWriteOperations();
                PrimitiveIntIterator iterator = this.iterator.iterator();
                while (iterator.hasNext()) {
                    exporter.write(ops, iterator.next());
                }
                tx.success();
            } catch (KernelException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
