package com.jardoapps.pao;

/** Signals a configuration or repository problem that should fail the build. */
public class PaoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PaoException(String message) {
        super(message);
    }

    public PaoException(String message, Throwable cause) {
        super(message, cause);
    }
}
