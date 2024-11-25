package io.dbobjects.db.postgres;

import io.dbobjects.db.Database;
import io.dbobjects.db.DatabaseException;
import io.dbobjects.db.postgres.tx.TxContext;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.NoStackTraceThrowable;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.PgException;
import io.vertx.pgclient.PgNotice;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.StreamSupport;

@Slf4j(topic = Database.LOGGER_NAME)
public class QueryRunner implements AutoCloseable {

    public <T> QueryResult<Optional<T>> select1(Pool sqlClient, Function<Row, T> mapper, String sql,
                                                Object... params) throws DatabaseException {
        var sqlString = sql.replaceAll("\\s{2,}", " ").trim();
        log.debug("DML: {} with params: {}", sql, Arrays.asList(params));
        return runBlocking(sqlClient, rows -> StreamSupport.stream(rows.spliterator(), false)
            .findFirst()
            .map(mapper), sqlString, false, fixParams(params));
    }

    private Object[] fixParams(Object[] params) {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof byte[]) {
                    params[i] = Buffer.buffer((byte[]) params[i]);
                }
            }
        }
        return params;
    }


    public <T> Collection<T> select(Pool sqlClient, Function<Row, T> mapper, String sql,
                                    Object... params) throws DatabaseException {
        var sqlString = sql.replaceAll("\\s{2,}", " ").trim();
        log.debug("DML: {} with params: {}", sql, Arrays.asList(params));
        return runBlocking(sqlClient, rows -> {
            var result = new ArrayList<T>();
            if (rows != null) {
                for (var row : rows) {
                    result.add(mapper.apply(row));
                }
            }
            return result;
        }, sqlString, false, params).getResult();
    }

    public TxContext createTransaction(Pool sqlClient) throws DatabaseException {
        return TxContext.getInstance(sqlClient);
    }

    private void handleThrowable(QueryResult<?> queryResult, Throwable t, String sql,
                                 Object... params) throws DatabaseException {
        if (t instanceof PgException) {
            var pgEx = (PgException) t;
            queryResult.setErrorCode(pgEx.getSqlState());
        }
        queryResult.setException(t);
    }

    private Future<RowSet<Row>> createExecution(boolean script, PgConnection connection, String sql,
                                                Object... parameters) {
        if (script) {
            if (true) {
                throw new IllegalStateException("called script");
            }
            return connection
                .noticeHandler(this::log)
                .query(sql)
                .execute();
        } else {
            return connection
                .noticeHandler(this::log)
                .preparedQuery(sql)
                .execute(Tuple.from(parameters));
        }
    }

    private <T> QueryResult<T> runBlocking(Pool connectionPool, Function<RowSet<Row>, T> rowMapper,
                                           String sql,
                                           boolean script, Object... params)
        throws DatabaseException {
        var parameters = (params == null) ? new Object[]{} : params;
        var queryResult = new QueryResult<T>();
        try {
            return connectionPool.getConnection().compose(
                    connection ->
                        createExecution(script, ((PgConnection) connection), sql, parameters)
                            .onFailure(queryResult::setFailed)
                            .onSuccess(e -> e.forEach(row -> log.debug("row: {}", row.toJson())))
                            .map(rowMapper)
                            .map(queryResult::setResult)
                            .eventually(v -> connection.close())
                )
                .onFailure(e -> log.warn("sql: {}\nparams: {}\nex:", sql, parameters, e))
                .toCompletionStage().toCompletableFuture().get();
        } catch (InterruptedException | ExecutionException e) {
            handleThrowable(queryResult, e.getCause(), sql, params);
        }
        if (!queryResult.isSucceeded()) {
            this.logException(queryResult.getException(), sql, params);
        }
        return queryResult;
    }

    private void logException(Throwable t, String sql, Object... params) {
        if (t instanceof PgException) {
            var pgEx = (PgException) t;
            log.info("PG EX: \nsql: {} \nparams: {} \nPG RESULT: {}: {}", sql, params, pgEx.getSqlState(),
                pgEx.getMessage());
        } else if (t instanceof NoStackTraceThrowable
            && t.getMessage() != null
            && t.getMessage().contains("parameters to execute should be consistent")) {
            log.error("DB EX: {}\nSQL: {} \nPARAMS: {}", t.getMessage(), sql, params);
        } else {
            log.debug("DB EX:", t);
        }
    }

    private void log(PgNotice notice) {
        switch (notice.getSeverity()) {
            case "PANIC", "FATAL", "ERROR", "WARNING": {
                log.warn(notice.getMessage());
                break;
            }
            case "NOTICE", "INFO", "LOG": {
                log.debug(notice.getMessage());
                break;
            }
            default: {
                // do nothing
            }
        }
    }

    @Override
    public void close() {
    }

    @Getter
    @Setter
    @Slf4j
    @ToString
    public static class QueryResult<R> {
        private R result;
        private String errorCode;
        private Throwable exception;

        public boolean isSucceeded() {
            return exception == null;
        }

        QueryResult<R> setFailed(Throwable t) {
            exception = t;
            return this;
        }

    }

}
