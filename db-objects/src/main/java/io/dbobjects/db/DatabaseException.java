package io.dbobjects.db;

public class DatabaseException extends RuntimeException {

    public DatabaseException(Throwable throwable) {
        super(throwable);
    }

    public DatabaseException() {
        super();
    }

    public DatabaseException(String code) {
        super("PG_ERROR_CODE: " + code);
    }
}
