package me.neveux.ccx.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Utility methods for working with lists.
 */
public final class ListUtils {

    private ListUtils() {}

    /**
     * Returns the order-preserving union of two lists with duplicates removed.
     * Null inputs are treated as empty. The result is always a new mutable list.
     */
    public static List<String> orderedUnion(List<String> first, List<String> second) {
        var seen = new LinkedHashSet<String>();
        if (first != null) seen.addAll(first);
        if (second != null) seen.addAll(second);
        return new ArrayList<>(seen);
    }
}
