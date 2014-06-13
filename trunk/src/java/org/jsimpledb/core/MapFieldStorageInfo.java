
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb.core;

import java.util.Arrays;
import java.util.List;

import org.jsimpledb.kv.KVPairIterator;
import org.jsimpledb.util.ByteReader;

class MapFieldStorageInfo extends ComplexFieldStorageInfo {

    SimpleFieldStorageInfo keyField;
    SimpleFieldStorageInfo valueField;

    MapFieldStorageInfo(MapField<?, ?> field) {
        super(field);
    }

    @Override
    public List<SimpleFieldStorageInfo> getSubFields() {
        return Arrays.asList(this.keyField, this.valueField);
    }

    @Override
    void initializeSubFields(List<SimpleFieldStorageInfo> subFieldInfos) {
        if (subFieldInfos.size() != 2)
            throw new IllegalArgumentException();
        this.keyField = subFieldInfos.get(0);
        this.valueField = subFieldInfos.get(1);
    }

    @Override
    void unreference(Transaction tx, int storageId, ObjId target, ObjId referrer, byte[] prefix) {
        final FieldTypeMap<?, ?> fieldMap = (FieldTypeMap<?, ?>)tx.readMapField(referrer, this.storageId, false);
        if (storageId == this.keyField.storageId)
            fieldMap.remove(target);
        else {
            assert storageId == this.valueField.storageId;
            for (KVPairIterator i = new KVPairIterator(tx.kvt, prefix); i.hasNext(); ) {
                final ByteReader reader = new ByteReader(i.next().getKey());
                reader.skip(prefix.length);
                fieldMap.remove(fieldMap.keyFieldType.read(reader));
            }
        }
    }

    public SimpleFieldStorageInfo getKeyField() {
        return this.keyField;
    }

    public SimpleFieldStorageInfo getValueField() {
        return this.valueField;
    }

    @Override
    public String toString() {
        return "map field with key " + this.keyField + " and value " + this.valueField;
    }
}

