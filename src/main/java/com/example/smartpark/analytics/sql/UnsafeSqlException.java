package com.example.smartpark.analytics.sql;

/**
 * Fail-closed rejection of a candidate analysis query. The safe message never
 * contains vendor errors, connection details or raw rejected SQL fragments.
 */
public class UnsafeSqlException extends Exception {

    private final String errorCode;

    public UnsafeSqlException(String errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
