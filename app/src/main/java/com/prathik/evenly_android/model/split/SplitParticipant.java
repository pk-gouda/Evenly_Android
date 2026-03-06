package com.prathik.evenly_android.model.split;

import java.io.Serializable;

/**
 * A person participating in splitting a receipt.
 */
public class SplitParticipant implements Serializable {

    public final String id;    // unique identifier
    public final String name;  // display name

    public SplitParticipant(String id, String name) {
        this.id   = id;
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SplitParticipant)) return false;
        return id.equals(((SplitParticipant) o).id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }

    @Override
    public String toString() { return name; }
}