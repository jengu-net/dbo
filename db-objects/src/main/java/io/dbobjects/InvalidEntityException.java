package io.dbobjects;

public class InvalidEntityException extends RuntimeException {
    public InvalidEntityException(Throwable throwable) {
        super(throwable);
    }
}
