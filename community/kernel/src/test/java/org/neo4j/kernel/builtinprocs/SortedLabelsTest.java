/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.neo4j.kernel.builtinprocs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortedLabelsTest
{
    @Test
    void testEquals()
    {
        long[] longsA = new long[]{1L, 2L, 3L};
        long[] longsB = new long[]{3L, 2L, 1L};
        long[] longsC = new long[]{1L, 2L, 3L, 4L};
        SortedLabels a = SortedLabels.from( longsA );
        SortedLabels b = SortedLabels.from( longsB );
        SortedLabels c = SortedLabels.from( longsC );

        // self
        //noinspection EqualsWithItself
        assertTrue( a.equals( a ) );

        // unordered self
        assertTrue( a.equals( b ) );
        assertTrue( b.equals( a ) );

        // other
        assertFalse( a.equals( c ) );
        assertFalse( c.equals( a ) );
    }

    @Test
    void testHashCodeOfLabelSet()
    {
        long[] longsA = new long[]{1L, 2L, 3L};
        long[] longsB = new long[]{3L, 2L, 1L};
        long[] longsC = new long[]{1L, 2L, 3L, 4L};
        SortedLabels a = SortedLabels.from( longsA );
        SortedLabels b = SortedLabels.from( longsB );
        SortedLabels c = SortedLabels.from( longsC );

        assertEquals( a.hashCode(), b.hashCode() );
        assertNotEquals( a.hashCode(), c.hashCode() );
    }
}
