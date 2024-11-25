package io.dbobjects.db.postgres.tx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

@Setter(AccessLevel.PRIVATE)
@Getter
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class TxContext implements AutoCloseable {

    private final Pool pool;

    public static TxContext getInstance(Pool sqlClient) {
        return new TxContext(sqlClient);
    }

    @Override
    public void close() {
    }

    public interface TxMarker {
        int getRowNum();

        void commit();

        void rollback();

        <T> Collection<T> execute(Function<Row, T> mapper, String sql, Object... parameters);

    }

    @RequiredArgsConstructor
    @Getter
    public static class TransactionContext {
        final SqlConnection connection;
        final Promise<Void> processPromise;
    }

    private TxMarker createMarker(int rowNum, SqlConnection conn) {
        return new TxMarker() {
            @Override
            public int getRowNum() {
                return rowNum;
            }

            @Override
            public void commit() {

            }

            @Override
            public void rollback() {

            }

            @Override
            public <T> Collection<T> execute(Function<Row, T> mapper, String sql, Object... params) {
                var sqlString = sql.replaceAll("\\s{2,}", " ").trim();
                var parameters = (params == null) ? new Object[]{} : params;
                log.debug("DML: {} with params: {}", sql, parameters);
                var result = new ArrayList<T>();
                conn.preparedQuery(sqlString)
                        .execute(Tuple.from(parameters))
                        .compose(selectedResult -> {
                            log.debug("execution row count: {}", selectedResult.rowCount());
                            selectedResult.forEach(row -> result.add(mapper.apply(row)));
                            return Future.succeededFuture();
                        })
                        .onSuccess(v -> {
                            log.debug("Row processing succeeded");
                        })
                        .onFailure(err -> {
                            rollback();
                            log.debug("Row processing failed: {}", err.getMessage());
                        });
                return result;
            }
        };
    }

    private <T> T toSync(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get();
        } catch (ExecutionException e) {
            log.info("sync error: {}", e.getMessage());
            e.printStackTrace();
        } catch (InterruptedException e) {
            log.info("interrupted..");
        }
        return null;
    }

    public <T> Collection<T> selectForApply(BiFunction<TransactionContext, Row, T> rowProcessor,
                                            String sql,
                                            Object... params) {
        var sqlString = sql.replaceAll("\\s{2,}", " ").replaceAll("\n", " ").trim();
        var parameters = (params == null) ? new Object[]{} : params;
        log.debug("SELECT: {} with params: {}", sqlString, parameters);
        Collection<T> result = new ArrayList<>();

        toSync(pool.withTransaction(sqlConnection -> {
            Future<RowSet<Row>> future1 = sqlConnection
                    .preparedQuery(sqlString)
                    .execute(Tuple.from(parameters));

            return future1.compose(rowSet -> {
                // A promise for the overall process
                Promise<Void> processPromise = Promise.promise();

                var rowNum = new AtomicInteger();
                var txContext = new TransactionContext(sqlConnection, processPromise);
                // Iterate over each row
                rowSet.forEach(row -> {
                    // Process each row
                    rowProcessor.apply(txContext, row);
                });

                processPromise.complete();
                return processPromise.future();
            });
        }).onComplete(ar -> {
            if (ar.succeeded()) {
                log.debug("transaction completed");
            } else {
                log.debug("transaction failed: {}", ar.cause().getMessage());
            }
        }));

        return result;
    }

}