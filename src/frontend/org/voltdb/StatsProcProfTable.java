/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public class StatsProcProfTable {

    // One row (procedure) of min/max/avg data aggregated across sites and hosts
    private static class ProcProfRow implements Comparable<ProcProfRow>
    {
        public String procedure;
        public long invocations;
        public long min;
        public long max;
        public long avg;
        public long failures;
        public long aborts;

        public ProcProfRow(String procedure, long invocations, long min, long max,
                long avg, long failures, long aborts)
        {
            this.procedure = procedure;
            this.invocations = invocations;
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.failures = failures;
            this.aborts = aborts;
        }

        // Augment this ProcProfRow with a new input row
        public void updateWith(ProcProfRow in)
        {
            this.invocations += in.invocations;
            this.min = Math.min(this.min, in.min);
            this.max = Math.max(this.max, in.max);
            this.avg = calculateAverage(
                    this.avg, this.invocations, in.avg, in.invocations);
            this.failures += in.failures;
            this.aborts += in.aborts;
        }

        @Override
        public int compareTo(ProcProfRow other)
        {
            return procedure.compareTo(other.procedure);
        }
    }

    /**
     * Given a running average and the running invocation total as well as a new
     * row's average and invocation total, return a new running average
     */
    static long calculateAverage(long currAvg, long currInvoc, long rowAvg, long rowInvoc)
    {
        long currTtl = currAvg * currInvoc;
        long rowTtl = rowAvg * rowInvoc;
        return (currTtl + rowTtl) / (currInvoc + rowInvoc);
    }

    /**
     * Safe division that assumes x/0 = 100%
     */
    static long calculatePercent(long nom, long denom)
    {
        if (denom == 0) {
            return 100;
        }
        return (100 * nom / denom);
    }

    // A table of ProcProfRows: set of unique procedure names
    TreeSet<ProcProfRow> m_table = new TreeSet<ProcProfRow>();

    // Add or update the corresponding row.
    public void updateTable(String procedure, long invocations, long min,
            long max, long avg, long failures, long aborts)
    {
        ProcProfRow in = new ProcProfRow(procedure, invocations, min, max,
                avg, failures, aborts);
        ProcProfRow exists = m_table.ceiling(in);
        if (exists != null && in.equals(exists)) {
            exists.updateWith(in);
        }
        else {
            m_table.add(in);
        }
    }

    // Return table sorted by weighted avg
    public VoltTable sortByAverage(String tableName)
    {
        List<ProcProfRow> sorted = new ArrayList<ProcProfRow>(m_table);
        Collections.sort(sorted, new Comparator<ProcProfRow>() {
            @Override
            public int compare(ProcProfRow lhs, ProcProfRow rhs) {
                return compareByAvg(rhs, lhs);  // sort desc
            }
        });

        long sumOfAverage = 0;
        for (ProcProfRow row : sorted) {
            sumOfAverage += (row.avg * row.invocations);
        }

        VoltTable result = TableShorthand.tableFromShorthand(
                tableName + "(PROCEDURE:VARCHAR, WEIGHTED_PERC:BIGINT, INVOCATIONS:BIGINT," +
                "AVG:BIGINT, MIN:BIGINT, MAX:BIGINT, ABORTS:BIGINT, FAILURES:BIGINT)");
        for (ProcProfRow row : sorted ) {
            result.addRow(row.procedure, calculatePercent(row.avg * row.invocations, sumOfAverage),
                    row.invocations, row.avg, row.min, row.max, row.aborts, row.failures);
        }

        return result;
    }

    public int compareByAvg(ProcProfRow lhs, ProcProfRow rhs)
    {
        if (lhs.avg * lhs.invocations > rhs.avg * rhs.invocations) {
            return 1;
        } else if (lhs.avg * lhs.invocations < rhs.avg * rhs.invocations) {
            return 1;
        } else {
            return 0;
        }
    }
}
































