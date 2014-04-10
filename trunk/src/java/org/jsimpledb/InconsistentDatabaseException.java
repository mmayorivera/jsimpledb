
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb;

/**
 * Thrown when inconsistent content is detected in a {@link JSimpleDB} indicating a corrupted or invalid database,
 * or a buggy underlying key-value store.
 */
@SuppressWarnings("serial")
public class InconsistentDatabaseException extends JSimpleDBException {

    InconsistentDatabaseException() {
    }

    public InconsistentDatabaseException(String message) {
        super(message);
    }

    public InconsistentDatabaseException(Throwable cause) {
        super(cause);
    }

    public InconsistentDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}

