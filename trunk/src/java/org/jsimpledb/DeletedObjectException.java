
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb;

/**
 * Thrown when a field of a deleted object is accessed.
 */
@SuppressWarnings("serial")
public class DeletedObjectException extends JSimpleDBException {

    private final ObjId id;

    DeletedObjectException(ObjId id) {
        super("object with ID " + id + " not found");
        this.id = id;
    }

    /**
     * Get the ID of the object that could not be accessed.
     */
    public ObjId getId() {
        return this.id;
    }
}

