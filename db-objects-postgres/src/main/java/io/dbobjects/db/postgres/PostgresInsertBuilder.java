package io.dbobjects.db.postgres;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class PostgresInsertBuilder {
    private final String tableName;
    private final String[] colNames;

    public PostgresInsertBuilder(String tableName, String... colNames) {
        this.tableName = tableName;
        this.colNames = colNames;
    }

    private Collection<Object[]> rows = new ArrayList<>();

    @Getter
    private Object[] preparedParameters;

    @Getter
    private String preparedQueryString;

    public PostgresInsertBuilder withNewRow(Object... items) {
        if (items == null || items.length != colNames.length) {
            throw new IllegalArgumentException("number of values in col must be " + colNames.length);
        }
        rows.add(items);
        return this;
    }

    public boolean prepare() {
        if (rows == null || rows.isEmpty()) {
            return false;
        } else {
            var paramCounterRef = new AtomicInteger(0);
            var queryAppender = new StringBuffer()
                    .append("insert into ")
                    .append(tableName)
                    .append(" (")
                    .append(String.join(",", colNames))
                    .append(") values ");
            Collection<Object> parameters = new ArrayList<>();

            var insertIdentifiersSuffix = ";";
            var queryJoiner = new StringJoiner(" ");
            var values = new ArrayList<Object>(colNames.length * rows.size());
            final var maybeComma = new AtomicBoolean(false);
            final var parNum = new AtomicInteger(0);
            rows.forEach(items -> {
                if (!maybeComma.get()) {
                    maybeComma.set(true); // only first row
                } else {
                    queryJoiner.add(","); // all other rows
                }
                queryJoiner.add("(");
                queryJoiner.add(nextRecordParams(parNum, items.length));
                queryJoiner.add(")");
                values.addAll(Arrays.asList(items));
            });
            queryAppender.append(queryJoiner).append(";");
            preparedParameters = values.toArray();
            preparedQueryString = queryAppender.toString();
            return true;
        }
    }

    private String nextRecordParams(AtomicInteger parNum, int cnt) {
        var result = new StringJoiner(",");
        for (int i = 0; i < cnt; i++) {
            result.add("$" + parNum.incrementAndGet());
        }
        return result.toString();
    }

}
