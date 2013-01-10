/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package genqa;

import genqa.procedures.SampleRecord;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import org.spearce_voltpatches.jgit.transport.OpenSshConfig;
import org.voltcore.utils.Pair;
import org.voltdb.VoltDB;
import org.voltdb.iv2.TxnEgo;
import org.voltdb.types.TimestampType;

import com.google.common.base.Throwables;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

public class ExportOnServerVerifier {

    public static long FILE_TIMEOUT_MS = 5 * 60 * 1000; // 5 mins

    public static long VALIDATION_REPORT_INTERVAL = 10000;

    private final static String TRACKER_FILENAME = "__active_tracker";
    private final static Pattern EXPORT_FILENAME_REGEXP =
            Pattern.compile("\\A[^-]+-(\\d+)-[^-]+-(\\d+)\\.csv\\z");

    private final JSch m_jsch = new JSch();
    private final List<RemoteHost> m_hosts = new ArrayList<RemoteHost>();

    private static class RemoteHost {
        @SuppressWarnings("unused")
        Session session;
        ChannelSftp channel;
        String path;
        boolean activeSeen = false;
    }

    public static class ValidationErr extends Exception {
        private static final long serialVersionUID = 1L;
        final String msg;
        final Object value;
        final Object expected;

        ValidationErr(String msg, Object value, Object expected) {
            this.msg = msg;
            this.value = value;
            this.expected = expected;
        }

        public ValidationErr(String string) {
            this.msg = string;
            this.value = "[not provided]";
            this.expected = "[not provided]";
        }
        @Override
        public String toString() {
            return msg + " Value: " + value + " Expected: " + expected;
        }
    }

    ExportOnServerVerifier()
    {
    }

    int splitClientTrace(String trace, long [] splat)
    {
        if (trace == null || splat == null || splat.length == 0) return 0;

        int columnCount = 0;
        int cursor = 0;
        int columnPos = trace.indexOf(':', cursor);

        try {
            while (columnPos >= 0 && splat.length > columnCount+1) {
                splat[columnCount] = Long.parseLong(trace.substring(cursor, columnPos));
                cursor = columnPos + 1;
                columnCount = columnCount + 1;
                columnPos = trace.indexOf(':', cursor);
            }
            if (cursor < trace.length()) {
                columnPos = columnPos < 0 ? trace.length() : columnPos;
                splat[columnCount] = Long.parseLong(trace.substring(cursor, columnPos));
            } else {
                columnCount = columnCount - 1;
            }
        } catch (NumberFormatException nfex) {
            return 0;
        } catch (IndexOutOfBoundsException ioobex) {
            return -1;
        }

        return columnCount+1;
    }

    private long unquoteLong(String quoted)
    {
        StringBuilder sb = new StringBuilder(quoted);
        int i = 0;
        while (i < sb.length()) {
            if (sb.charAt(i) == '"') sb.deleteCharAt(i);
            else ++i;
        }
        return Long.parseLong(sb.toString().trim());
    }

    int splitCSV(String csv, long [] splat)
    {
        if (csv == null || splat == null || splat.length == 0) return 0;

        int columnCount = 0;
        int cursor = 0;
        int columnPos = csv.indexOf(',', cursor);

        try {
            while (columnPos >= 0 && splat.length > columnCount+1) {
                splat[columnCount] = unquoteLong(csv.substring(cursor, columnPos));
                cursor = columnPos + 1;
                columnCount = columnCount + 1;
                columnPos = csv.indexOf(',', cursor);
            }
            if (cursor < csv.length()) {
                columnPos = columnPos < 0 ? csv.length() : columnPos;
                splat[columnCount] = unquoteLong(csv.substring(cursor, columnPos));
            } else {
                columnCount = columnCount - 1;
            }
        } catch (NumberFormatException nfex) {
            return 0;
        } catch (IndexOutOfBoundsException ioobex) {
            return -1;
        }

        return columnCount+1;
    }

    boolean verifySetup( String [] args) throws Exception
    {
        String remoteHosts[] = args[0].split(",");
        final String homeDir = System.getProperty("user.home");
        final String sshDir = homeDir + File.separator + ".ssh";
        final String sshConfigPath = sshDir + File.separator + "config";

        //Oh yes...
        loadAllPrivateKeys( new File(sshDir));

        OpenSshConfig sshConfig = null;
        if (new File(sshConfigPath).exists()) {
            sshConfig = new OpenSshConfig(new File(sshConfigPath));
        }

        final String defaultKnownHosts = sshDir + "/known_hosts";
        if (new File(defaultKnownHosts).exists()) {
            m_jsch.setKnownHosts(defaultKnownHosts);
        }

        for (String hostString : remoteHosts) {
            String split[] = hostString.split(":");
            String host = split[0];

            RemoteHost rh = new RemoteHost();
            rh.path = split[1];

            String user = System.getProperty("user.name") ;
            int port = 22;
            File identityFile = null;
            String configHost = host;
            if (sshConfig != null) {
                OpenSshConfig.Host hostConfig = sshConfig.lookup(host);
                if (hostConfig.getUser() != null) {
                    user = hostConfig.getUser();
                }
                if (hostConfig.getPort() != -1) {
                    port = hostConfig.getPort();
                }
                if (hostConfig.getIdentityFile() != null) {
                    identityFile = hostConfig.getIdentityFile();
                }
                if (hostConfig.getHostName() != null) {
                    configHost = hostConfig.getHostName();
                }
            }

            Session session = null;
            if (identityFile != null) {
                JSch jsch = new JSch();
                jsch.addIdentity(identityFile.getAbsolutePath());
                session = jsch.getSession(user, configHost, port);
            } else {
                session = m_jsch.getSession( user, configHost, port);
            }

            rh.session = session;
            session.setConfig("StrictHostKeyChecking", "no");
            session.setDaemonThread(true);
            session.connect();
            final ChannelSftp channel = (ChannelSftp)session.openChannel("sftp");
            rh.channel = channel;
            channel.connect();
            touchActiveTracker(rh);

            m_hosts.add(rh);
        }

        m_partitions = Integer.parseInt(args[1]);

        for (int i = 0; i < m_partitions; i++)
        {
            m_rowTxnIds.put(i, new TreeMap<Long,Long>());
            m_maxPartTxId.put(i, Long.MIN_VALUE);
            m_checkedUpTo.put(i,0);
            m_readUpTo.put(i, new AtomicLong(0));
        }

        m_clientPath = new File(args[2]);
        if (!m_clientPath.exists() || !m_clientPath.isDirectory())
        {
            if (!m_clientPath.mkdir()) {
                throw new IOException("Issue with transaction ID path");
            }
        }

        for (RemoteHost rh : m_hosts) {
            boolean existsOrIsDir = true;
            try {
                SftpATTRS stat = rh.channel.stat(rh.path);
                if (!stat.isDir()) {
                    existsOrIsDir = false;
                }
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    existsOrIsDir = false;
                } else {
                    Throwables.propagate(e);
                }
            }
            if (!existsOrIsDir) {
                rh.channel.mkdir(rh.path);
            }
        }

        boolean skinny = false;
        if (args.length > 3 && args[3] != null && !args[3].trim().isEmpty()) {
            skinny = Boolean.parseBoolean(args[3].trim().toLowerCase());
        }

        return skinny;
    }

    void verifyFat() throws Exception
    {

        long ttlVerified = 0;

        //checkForMoreExportFiles();
        //checkForMoreExportFiles();
        Pair<BufferedReader, Runnable> csvPair = openNextExportFile();
        BufferedReader csv = csvPair.getFirst();

        //checkForMoreClientFiles();
        BufferedReader txnIdReader = openNextClientFile();
        String exportLine;
        String[] row;
        boolean quit = false;
        boolean more_rows = true;
        boolean more_txnids = true;
        boolean expect_export_eof = false;
        int emptyRemovalCycles = 0;

        PrintWriter txout = gzipWriterTo("read-exported-transactions.gz");
        PrintWriter vfout = gzipWriterTo("verified-exported-transactions.gz");

        while (!quit)
        {
            markCheckedUpTo();
            int dcount = 0;
            while ((dcount < 10000 || !more_txnids) && more_rows)
            {
                exportLine = csv.readLine();
                if (exportLine == null)
                {
                    expect_export_eof = false;
                    csvPair.getSecond().run();
                    csvPair = openNextExportFile();
                    if (csvPair == null)
                    {
                        System.out.println("No more export rows");
                        more_rows = false;
                        break;
                    }
                    else
                    {
                        csv = csvPair.getFirst();
                        exportLine = csv.readLine();
                    }
                }
                else if (expect_export_eof)
                {
                    throw new ValidationErr("previously logged row had unexpected number of columns");
                }

                row = RoughCSVTokenizer.tokenize(exportLine);

                verifyRow(row);
                expect_export_eof = row.length < 29;
                dcount++;
                /*
                 * client dude has only confirmed tx id, on asynch writer exceptions we
                 * writer row id for which we don't have confirmed commit, and thus use
                 * rows' own tx id for verification
                 */
                if (++ttlVerified % VALIDATION_REPORT_INTERVAL == 0) {
                    System.out.println("Verified " + ttlVerified + " rows.");
                }

                Integer partition = Integer.parseInt(row[3].trim());
                Long rowTxnId = Long.parseLong(row[6].trim());
                Long rowId = Long.parseLong(row[7].trim());

                txout.printf("%d:%d\n", rowTxnId, rowId);

                Long previous = m_rowTxnIds.get(partition).put(rowTxnId,rowId);
                if (previous != null)
                {
                    System.out.println("WARN Duplicate TXN ID in export stream: " + rowTxnId);
                }
                else
                {
                    //System.out.println("Added txnId: " + rowTxnId + " to outstanding export");
                }
            }

            System.out.println("\n!_!_! DEBUG !_!_! read " + dcount + " exported records");

            determineReadUpToCounters();
            dcount = m_clientOverFlow.size();
            processClientIdOverFlow();

            System.out.println("!_!_! DEBUG !_!_! processed " + (dcount - m_clientOverFlow.size()) + " client overflow txid records");
            System.out.println("!_!_! DEBUG !_!_! overflow size is now " + m_clientOverFlow.size());

            // If we've pulled in rows for every partition, or there are
            // no more export rows, and there are still unchecked client txnids,
            // attempt to validate as many client txnids as possible
            dcount = 0;
            long [] rec = new long [3];
            while ((!reachedReadUpTo() || !more_rows) && more_txnids)
            {
                String trace = txnIdReader.readLine();
                if (trace == null)
                {
                    txnIdReader = openNextClientFile();

                    if (txnIdReader == null)
                    {
                        System.out.println("No more client txn IDs");
                        more_txnids = false;
                    }
                    else
                    {
                        trace = txnIdReader.readLine();
                    }
                }
                int recColumns = splitClientTrace(trace, rec);
                if (recColumns == rec.length)
                {
                    long rowid = rec[0];
                    long txid = rec[1];
                    long ts = rec[2];

                    if (txid >= 0) {
                        m_clientTxnIds.put(txid,ts);
                        countDownReadUpTo(txid);
                    } else {
                        m_clientTxnIdOrphans.add(rowid);
                    }
                    dcount++;
                }
                else if (trace != null)
                {
                    System.out.println("WARN read malformed trace " + trace);
                }
            }

            System.out.println("!_!_! DEBUG !_!_! read " + dcount + " client txid records");

            if (matchClientTxnIds(vfout))
            {
                emptyRemovalCycles = 0;
            }
            else if (++emptyRemovalCycles >= 10)
            {
                System.err.println("ERROR: 10 check cycles failed to match client tx ids with exported tx id -- bailing out");
                System.exit(1);
            }

            printTxCountByPartition();

            if (!more_rows || !more_txnids)
            {
                if (more_rows && ! m_clientTxnIds.isEmpty())
                {
                    quit = false;
                }
                else
                {
                    quit = true;
                }
            }
        }

        if (more_rows || more_txnids)
        {
            System.out.println("Something wasn't drained");
            System.out.println("client txns remaining: " + m_clientTxnIds.size());
            System.out.println("Export rows remaining: ");
            int total = 0;
            for (int i = 0; i < m_partitions; i++)
            {
                total += m_rowTxnIds.get(i).size();
                System.out.println("\tpartition: " + i + ", size: " + m_rowTxnIds.get(i).size());
            }
            if (total != 0 && m_clientTxnIds.size() != 0)
            {
                System.out.println("THIS IS A REAL ERROR?!");
            }
        }
    }

    private PrintWriter gzipWriterTo( String fileName) throws IOException
    {
        File fh = new File(fileName);
        if (fh.exists() && fh.isFile() && fh.canRead() && fh.canWrite())
        {
            fh.delete();
        }
        PrintWriter out =
                new PrintWriter(
                        new OutputStreamWriter(
                                new GZIPOutputStream(
                                        new FileOutputStream(fh), 16384)));
        return out;
    }

    void verifySkinny() throws Exception
    {

        long ttlVerified = 0;

        //checkForMoreExportFiles();
        Pair<BufferedReader, Runnable> csvPair = openNextExportFile();
        BufferedReader csv = csvPair.getFirst();

        //checkForMoreClientFiles();
        BufferedReader txnIdReader = openNextClientFile();
        String row;
        long [] rowValues = new long [8]; // [6] txnId [7] rowId
        boolean quit = false;
        boolean more_rows = true;
        boolean more_txnids = true;
        boolean expect_export_eof = false;
        int emptyRemovalCycles = 0;

        PrintWriter txout = gzipWriterTo("read-exported-transactions.gz");
        PrintWriter vfout = gzipWriterTo("verified-exported-transactions.gz");

        while (!quit)
        {
            markCheckedUpTo();
            int dcount = 0;
            while ((dcount < 10000 || !more_txnids) && more_rows)
            {
                row = csv.readLine();
                if (row == null)
                {
                    expect_export_eof = false;
                    csvPair.getSecond().run();
                    csvPair = openNextExportFile();
                    if (csvPair == null)
                    {
                        System.out.println("No more export rows");
                        try
                        {
                            txout.close();
                        }
                        catch (Exception ex)
                        {
                            System.err.println("ERROR: closing read transaction id files");
                            ex.printStackTrace();
                        }
                        more_rows = false;
                        break;
                    }
                    else
                    {
                        csv = csvPair.getFirst();
                        row = csv.readLine();
                    }
                }
                else if (expect_export_eof)
                {
                    throw new ValidationErr("previously logged row had unexpected number of columns");
                }

                int columnCount = splitCSV(row, rowValues);
                expect_export_eof = columnCount < rowValues.length;
                dcount++;

                if (expect_export_eof) {
                    System.err.println(
                            "ERROR: Unexpected number of columns for the following row:\n\t" +
                             row
                             );
                    continue;
                }
                /*
                 * client dude has only confirmed tx id, on asynch writer exceptions we
                 * writer row id for which we don't have confirmed commit, and thus use
                 * rows' own tx id for verification
                 */
                if (++ttlVerified % VALIDATION_REPORT_INTERVAL == 0) {
                    System.out.println("Verified " + ttlVerified + " rows.");
                }

                int partition =  Integer.MAX_VALUE;
                if (rowValues[3] < Integer.MAX_VALUE)
                {
                    partition = (int)rowValues[3];
                }
                long rowTxnId = rowValues[6];
                long rowId = rowValues[7];

                if (TxnEgo.getPartitionId(rowTxnId) != partition) {
                    System.err.println("ERROR: mismatched exported partition for txid " + rowTxnId +
                            ", tx says it belongs to " + TxnEgo.getPartitionId(rowTxnId) +
                            ", while export record says " + partition);
                }

                txout.printf("%d:%d\n", rowTxnId, rowId);

                if (! m_rowTxnIds.containsKey(partition)) {
                    System.err.println("ERROR: unknow partition " + partition + " in txnid " + rowTxnId);
                    continue;
                }

                Long previous = m_rowTxnIds.get(partition).put(rowTxnId,rowId);
                if (previous != null)
                {
                    System.out.println("WARN Duplicate TXN ID in export stream: " + rowTxnId);
                }
                else
                {
                    //System.out.println("Added txnId: " + rowTxnId + " to outstanding export");
                }
            }

            System.out.println("\n!_!_! DEBUG !_!_! read " + dcount + " exported records");

            determineReadUpToCounters();
            dcount = m_clientOverFlow.size();
            processClientIdOverFlow();

            System.out.println("!_!_! DEBUG !_!_! processed " + (dcount - m_clientOverFlow.size()) + " client overflow txid records");
            System.out.println("!_!_! DEBUG !_!_! overflow size is now " + m_clientOverFlow.size());

            // If we've pulled in rows for every partition, or there are
            // no more export rows, and there are still unchecked client txnids,
            // attempt to validate as many client txnids as possible
            dcount = 0;
            long [] rec = new long [3];
            while ((!reachedReadUpTo() || !more_rows) && more_txnids)
            {
                String trace = txnIdReader.readLine();
                if (trace == null)
                {
                    txnIdReader = openNextClientFile();

                    if (txnIdReader == null)
                    {
                        System.out.println("No more client txn IDs");
                        more_txnids = false;
                    }
                    else
                    {
                        trace = txnIdReader.readLine();
                    }
                }
                int recColumns = splitClientTrace(trace, rec);
                if (recColumns == rec.length)
                {
                    long rowid = rec[0];
                    long txid = rec[1];
                    long ts = rec[2];

                    if (txid >= 0) {
                        m_clientTxnIds.put(txid,ts);
                        countDownReadUpTo(txid);
                    } else {
                        m_clientTxnIdOrphans.add(rowid);
                    }
                    dcount++;
                }
                else if (trace != null)
                {
                    System.out.println("WARN read malformed trace " + trace);
                }
            }

            System.out.println("!_!_! DEBUG !_!_! read " + dcount + " client txid records");

            if (matchClientTxnIds(vfout))
            {
                emptyRemovalCycles = 0;
            }
            else if (++emptyRemovalCycles >= 10)
            {
                System.err.println("ERROR: 10 check cycles failed to match client tx ids with exported tx id -- bailing out");
                System.exit(1);
            }

            printTxCountByPartition();

            if (!more_rows || !more_txnids)
            {
                if (more_rows && ! m_clientTxnIds.isEmpty())
                {
                    quit = false;
                }
                else
                {
                    quit = true;
                }
            }
        }

        try
        {
            vfout.close();
        }
        catch (Exception ioex)
        {
            System.err.println("ERROR: closing verified transactions file");
            ioex.printStackTrace();
        }
    }

    private void printTxCountByPartition() {
        StringBuilder sb = new StringBuilder(512).append("partition TxCounts: ");
        int cnt = 0;
        for (Map<Long,Long> part: m_rowTxnIds.values()) {
            if( cnt++ > 0) sb.append(", ");
            sb.append(part.size());
        }
        System.out.println("\n==================================================================");
        System.out.println(sb.toString());
        System.out.println("client tx id list size is " + m_clientTxnIds.size());
        int [] txByPart    = new int [m_partitions]; Arrays.fill(txByPart, 0);
        int [] staleByPart = new int [m_partitions]; Arrays.fill(staleByPart, 0);
        for( Long tx: m_clientTxnIds.keySet())
        {
            int partid = TxnEgo.getPartitionId(tx);
            ++txByPart[partid];
            if (tx < m_maxPartTxId.get(partid)) ++staleByPart[partid];
        }
        sb.delete(0, sb.length()).append(" -- ");
        for (int i = 0; i < m_partitions; ++i)
        {
            if (i>0) sb.append(", ");
            sb.append(txByPart[i]).append("[").append(staleByPart[i]).append("]");
        }
        System.out.println(sb.toString());
        System.out.println("overflow txid id list size is " + m_clientOverFlow.size());
        Arrays.fill(txByPart, 0);
        Arrays.fill(staleByPart, 0);
        for( Long tx: m_clientOverFlow)
        {
            int partid = TxnEgo.getPartitionId(tx);
            ++txByPart[partid];
            if (tx < m_maxPartTxId.get(partid)) ++staleByPart[partid];
        }
        sb.delete(0, sb.length()).append(" -- ");
        for (int i = 0; i < m_partitions; ++i)
        {
            if (i>0) sb.append(", ");
            sb.append(txByPart[i]).append("[").append(staleByPart[i]).append("]");
        }
        System.out.println(sb.toString());
        System.out.println("orphaned client row id list size is " + m_clientTxnIdOrphans.size());
        System.out.println("stale client txid id list size is " + m_staleTxnIds.size());
        System.out.println("==================================================================\n");
    }

    void printClientTxIds() {
        SimpleDateFormat dfmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss.SSSZ");

        TreeMap<Long, Long> sortedByDate = new TreeMap<Long,Long>();
        Iterator<Map.Entry<Long, Long>> itr = m_clientTxnIds.entrySet().iterator();
        while (itr.hasNext()) {
            Map.Entry<Long, Long> entry = itr.next();
            sortedByDate.put(entry.getValue(), entry.getKey());
            itr.remove();
        }

        System.out.println("\n==================================================================");
        System.out.println("List of remaining committed but unmatched client transactions:");
        for (Map.Entry<Long,Long> entry: sortedByDate.entrySet()) {
            String ts = dfmt.format(new Date(entry.getKey()));
            long txid = entry.getValue();
            int partid = TxnEgo.getPartitionId(txid);
            System.out.println(ts+ ", partition: " + partid + ", txid: " + txid);
        }
        System.out.println("==================================================================\n");
    }

    private void loadAllPrivateKeys(File file) throws Exception {
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                loadAllPrivateKeys(f);
            }
        } else if (file.isFile() && file.canRead()) {
            FileReader fr = new FileReader(file);
            BufferedReader br = new BufferedReader(fr);
            final String firstLine = br.readLine();
            if (firstLine.contains("PRIVATE KEY")) {
                m_jsch.addIdentity(file.getAbsolutePath());
            }
        }
    }

    private void touchActiveTracker(RemoteHost rh) throws Exception
    {
        final String trackerFileName = rh.path + "/" + TRACKER_FILENAME;
        final ByteArrayInputStream bis = new ByteArrayInputStream(
                new String("__DUMMY__").getBytes(VoltDB.UTF8ENCODING)
                );
        rh.channel.put(bis,trackerFileName);
    }

    @SuppressWarnings("unchecked")
    private void checkForMoreFilesRemote(Comparator<String> comparator) throws Exception
    {
        int onDoneRetries = 6;
        long start_time = System.currentTimeMillis();
        while (m_exportFiles.isEmpty())
        {
            /*
             * Collect the list of remote files at each node
             * Sort the list from each node
             */
            int activeFound = 0;
            List<Pair<ChannelSftp, List<String>>> pathsFromAllNodes = new ArrayList<Pair<ChannelSftp, List<String>>>();
            for (RemoteHost rh : m_hosts) {

                Vector<LsEntry> files = rh.channel.ls(rh.path);
                List<String> paths = new ArrayList<String>();

                int trackerModifyTime = rh.channel.stat(rh.path + "/" + TRACKER_FILENAME).getMTime();

                boolean activeInRemote = false;

                for (LsEntry entry : files) {
                    activeInRemote = activeInRemote || entry.getFilename().startsWith("active");

                    if (    !entry.getFilename().equals(".") &&
                            !entry.getFilename().equals("..") &&
                            !entry.getAttrs().isDir())
                    {
                        final String entryFileName = rh.path + "/" + entry.getFilename();

                        if (!entry.getFilename().contains("active"))
                        {
                            Matcher mtc = EXPORT_FILENAME_REGEXP.matcher(entry.getFilename());
                            if (mtc.matches()) {
                                paths.add(entryFileName);
                            } else {
                                System.err.println(
                                        "ERROR: " + entryFileName +
                                        " does not match expected export file name pattern");
                            }
                        }
                        else if (entry.getFilename().startsWith("active-"))
                        {
                            int activeModifyTime = entry.getAttrs().getMTime();
                            if ((trackerModifyTime - activeModifyTime) > 120)
                            {
                                final String renamed = rh.path + "/" + entry.getFilename().substring("active-".length());
                                rh.channel.rename(entryFileName, renamed);
                                paths.add(renamed);
                            }
                        }
                    }
                }

                touchActiveTracker(rh);

                rh.activeSeen = rh.activeSeen || activeInRemote;
                if( activeInRemote) activeFound++;

                Collections.sort(paths, comparator);
                if (!paths.isEmpty()) pathsFromAllNodes.add(Pair.of(rh.channel, paths));
            }

            if( pathsFromAllNodes.isEmpty() && activeFound == 0 && allActiveSeen())
            {
                if (--onDoneRetries <= 0) return;
                Thread.sleep(5000);
            }

            // add them to m_exportFiles as ordered by the comparator
            TreeMap<String, Pair<ChannelSftp,String>> hadPaths =
                    new TreeMap<String, Pair<ChannelSftp, String>>(comparator);

            for (Pair<ChannelSftp, List<String>> p : pathsFromAllNodes)
            {
                final ChannelSftp c = p.getFirst();
                for (String path: p.getSecond())
                {
                    hadPaths.put( path, Pair.of(c, path));
                }
            }

            boolean hadOne = !hadPaths.isEmpty();

            Iterator<Map.Entry<String, Pair<ChannelSftp, String>>> itr =
                    hadPaths.entrySet().iterator();

            while (itr.hasNext())
            {
                Map.Entry<String, Pair<ChannelSftp, String>> entry = itr.next();
                m_exportFiles.offer(entry.getValue());
                itr.remove();
            }

            long now = System.currentTimeMillis();
            if ((now - start_time) > FILE_TIMEOUT_MS)
            {
                throw new ValidationErr("Timed out waiting on new files.\n" +
                        "This indicates a mismatch in the transaction streams between the client logs and the export data or the death of something important.",
                        null, null);
            }
            else if (!hadOne)
            {
                Thread.sleep(1200);
            }
        }
    }


    private boolean sameFiles( File [] a, File [] b, Comparator<File> comparator) {
        boolean same = true;
        if( a == null || b == null) {
            return b == a;
        } else if ( a.length != b.length) {
            return false;
        } else {
            Arrays.sort(a, comparator);
            Arrays.sort(b, comparator);
            for ( int i = 0; same && i < a.length; ++i) {
                if( a[i] == null) {
                    same = b[i] == null;
                } else {
                    same = a[i].equals(b[i]);
                }
            }
        }
        return same;
    }

    private File[] checkForMoreFiles(File path, File[] files, FileFilter acceptor,
                                   Comparator<File> comparator) throws ValidationErr
    {
        File [] oldFiles = files;
        int emptyRetries = 50;
        long start_time = System.currentTimeMillis();
        while (sameFiles(files, oldFiles, comparator) || ( files.length == 0 && emptyRetries >=0))
        {
            files = path.listFiles(acceptor);
            emptyRetries = (files.length > 0 ? 50 : emptyRetries - 1);
            m_clientlogSeen = m_clientlogSeen || files.length > 0;
            long now = System.currentTimeMillis();
            if ((now - start_time) > FILE_TIMEOUT_MS)
            {
                throw new ValidationErr("Timed out waiting on new files in " + path.getName()+ ".\n" +
                                        "This indicates a mismatch in the transaction streams between the client logs and the export data or the death of something important.",
                                        null, null);
            } else {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignoreIt) {
                }
            }
        }
        Arrays.sort(files, comparator);
        return files;
    }

    private void checkForMoreExportFiles() throws Exception
    {
        Comparator<String> comparator = new Comparator<String>()
        {
            @Override
            public int compare(String f1, String f2)
            {
                f1 = f1.substring(f1.lastIndexOf('/') + 1);
                f2 = f2.substring(f2.lastIndexOf('/') + 1);

                Matcher m1 = EXPORT_FILENAME_REGEXP.matcher(f1);
                Matcher m2 = EXPORT_FILENAME_REGEXP.matcher(f2);

                if (m1.matches() && !m2.matches()) return -1;
                else if (m2.matches() && !m1.matches()) return 1;

                long first_ts = Long.parseLong(m1.group(2));
                long second_ts = Long.parseLong(m2.group(2));

                if (first_ts != second_ts)
                {
                    return (int)(first_ts - second_ts);
                }
                else
                {
                    long first_txnid = Long.parseLong(m1.group(1));
                    long second_txnid = Long.parseLong(m2.group(1));

                    if (first_txnid < second_txnid)
                    {
                        return -1;
                    }
                    else if (first_txnid > second_txnid)
                    {
                        return 1;
                    }
                    else
                    {
                        return 0;
                    }
                }
            }
        };

        checkForMoreFilesRemote(comparator);
        for (Pair<ChannelSftp, String> p : m_exportFiles)
        {
            System.out.println("" + p.getFirst().getSession().getHost() + " : " + p.getSecond());
        }
    }

    private void checkForMoreClientFiles() throws ValidationErr
    {
        FileFilter acceptor = new FileFilter()
        {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().contains("dude") && !pathname.getName().startsWith("active-");
            }
        };

        Comparator<File> comparator = new Comparator<File>()
        {
            @Override
            public int compare(File f1, File f2)
            {
                long first = Long.parseLong(f1.getName().split("-")[0]);
                long second = Long.parseLong(f2.getName().split("-")[0]);
                return (int)(first - second);
            }
        };

        m_clientFiles = checkForMoreFiles(m_clientPath, m_clientFiles, acceptor, comparator);
    }

    Pair<BufferedReader,Runnable> openNextExportFile() throws Exception
    {
        if (m_exportFiles.isEmpty())
        {
            checkForMoreExportFiles();
        }
        Pair<ChannelSftp, String> remotePair = m_exportFiles.poll();
        if (remotePair == null) return null;
        final ChannelSftp channel = remotePair.getFirst();
        final String path = remotePair.getSecond();
        System.out.println(
                "Opening export file: " + channel.getSession().getHost() + "@" + path);
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(channel.get(path)), 4096 * 32
                );
        Runnable r = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    reader.close();
                    channel.rm(path);
                } catch (Exception e)
                {
                    Throwables.propagate(e);
                }
            }
        };

        return Pair.of(reader,r);
    }

    private BufferedReader openNextClientFile() throws FileNotFoundException, ValidationErr
    {
        BufferedReader clientreader = null;
        if (m_clientIndex == m_clientFiles.length)
        {
            for (int i = 0; i < m_clientIndex; i++)
            {
                m_clientFiles[i].delete();
            }
            checkForMoreClientFiles();
            if (m_clientFiles.length == 0) return null;
            m_clientIndex = 0;
        }
        File data = m_clientFiles[m_clientIndex];
        System.out.println("Opening client file: " + data.getName());
        FileInputStream dataIs = new FileInputStream(data);
        Reader reader = new InputStreamReader(dataIs);
        clientreader = new BufferedReader(reader);
        m_clientIndex++;
        return clientreader;
    }

    private boolean allActiveSeen()
    {
        boolean seen = true;
        for (RemoteHost host: m_hosts)
        {
            seen = seen && host.activeSeen;
        }
        return seen;
    }

    private void determineReadUpToCounters()
    {
        for (int i = 0; i < m_partitions; ++i)
        {
            long partitionReadUpToCount =
                    m_rowTxnIds.get(i).size() - m_checkedUpTo.get(i);
            m_readUpTo.get(i).set(partitionReadUpToCount);
        }
    }

    private void markCheckedUpTo()
    {
        for (int i = 0; i < m_partitions; ++i)
        {
            int partid = i;
            int mark = m_rowTxnIds.get(i).size();
            m_checkedUpTo.put(partid, mark);
        }
    }

    private void countDownReadUpTo( long txid)
    {
        int partid = TxnEgo.getPartitionId(txid);
        if (m_readUpTo.get(partid) == null)
        {
            System.out.println("WARN: could find countdown for partition " + partid);
            return;
        }
        if (m_readUpTo.get(partid).decrementAndGet() <= 0)
        {
            m_clientOverFlow.add(txid);
        }
    }

    private boolean reachedReadUpTo() {
        boolean allCountedDown = true;

        for (int i = 0; i < m_partitions; ++i)
        {
            allCountedDown = allCountedDown && m_readUpTo.get(i).get() <= 0L;
        }
        return allCountedDown;
    }

    private boolean matchClientTxnIds(PrintWriter writer)
    {
        Collections.sort(m_clientTxnIdOrphans);

        int matchCount = 0;
        int staleCount = 0;
        int dupStaleCount = 0;

        Iterator<Long> staleitr = m_staleTxnIds.iterator();
        while (staleitr.hasNext())
        {
            long staletx = staleitr.next();
            if (m_clientTxnIds.remove(staletx) != null)
            {
                ++matchCount;
                staleitr.remove();

                if (writer != null) writer.println(staletx);

                System.out.println(" *** Resurfaced stale tx " + staletx + " found in the client tx list");
            }
        }
        for (int i = 0; i < m_partitions; ++i)
        {
            Iterator<Map.Entry<Long, Long>> txitr = m_rowTxnIds.get(i).entrySet().iterator();
            while (txitr.hasNext())
            {
                Map.Entry<Long, Long> e = txitr.next();

                if (m_clientTxnIds.remove(e.getKey()) != null)
                {
                    txitr.remove();
                    ++matchCount;

                    if (writer != null) writer.println(e.getKey());

                    m_clientOverFlow.remove(e.getKey());
                    if (e.getKey() > m_maxPartTxId.get(i))
                    {
                        m_maxPartTxId.put(i, e.getKey());
                    }
                    if (m_staleTxnIds.remove(e.getKey()))
                    {
                        System.out.println(" *** Matched stale tx " + e.getKey() + " found in the client tx list");
                    }
                }
                else if (e.getKey() <= m_maxPartTxId.get(i))
                {
                    txitr.remove();
                    if (!m_staleTxnIds.add(e.getKey()))
                    {
                        ++dupStaleCount;
                    }
                    ++staleCount;
                }
                else
                {
                    long bsidx = Collections.binarySearch(m_clientTxnIdOrphans, e.getValue());
                    if( bsidx >= 0)
                    {
                        System.out.println(" *** Found unrecorded txid " + e.getKey() + " for rowId " + e.getValue());

                        if (writer != null) writer.println(e.getKey());

                        txitr.remove();
                        m_clientTxnIdOrphans.remove(bsidx);
                        ++matchCount;
                    }
                }
            }
        }

        System.out.println("!_!_! DEBUG !_!_! *MATCHED* " + matchCount
                + " exported records, with *STALE* " + staleCount + " of which  "
                + dupStaleCount + " are duplicate"
                );

        return (matchCount + staleCount) > 0;
    }

    private void processClientIdOverFlow()
    {
        Iterator<Long> ovfitr = m_clientOverFlow.iterator();
        while (ovfitr.hasNext())
        {
            long txnId = ovfitr.next();
            int partid = TxnEgo.getPartitionId(txnId);

            if (m_readUpTo.get(partid).decrementAndGet() > 0)
            {
                ovfitr.remove();
            }
        }
    }

    private void verifyRow(String[] row) throws ValidationErr {

        if (row.length < 29)
        {
            System.err.println("ERROR: Unexpected number of columns for the following row:\n\t" + Arrays.toString(row));
            if (row.length < 8) {
                error("row column count", row.length, 29);
            }
            return;
        }

        int col = 5; // col offset is always pre-incremented.
        Long txnid = Long.parseLong(row[++col]); // col 6
        Long rowid = Long.parseLong(row[++col]); // col 7
        // matches VoltProcedure.getSeededRandomNumberGenerator()
        Random prng = new Random(txnid);
        SampleRecord valid = new SampleRecord(rowid, prng);

        // col 8
        Byte rowid_group = Byte.parseByte(row[++col]);
        if (rowid_group != valid.rowid_group)
            error("rowid_group invalid", rowid_group, valid.rowid_group);

        // col 9
        Byte type_null_tinyint = row[++col].equals("NULL") ? null : Byte.valueOf(row[col]);
        if ( (!(type_null_tinyint == null && valid.type_null_tinyint == null)) &&
             (!type_null_tinyint.equals(valid.type_null_tinyint)) )
            error("type_not_null_tinyint", type_null_tinyint, valid.type_null_tinyint);

        // col 10
        Byte type_not_null_tinyint = Byte.valueOf(row[++col]);
        if (!type_not_null_tinyint.equals(valid.type_not_null_tinyint))
            error("type_not_null_tinyint", type_not_null_tinyint, valid.type_not_null_tinyint);

        // col 11
        Short type_null_smallint = row[++col].equals("NULL") ? null : Short.valueOf(row[col]);
        if ( (!(type_null_smallint == null && valid.type_null_smallint == null)) &&
             (!type_null_smallint.equals(valid.type_null_smallint)) )
            error("type_null_smallint", type_null_smallint, valid.type_null_smallint);

        // col 12
        Short type_not_null_smallint = Short.valueOf(row[++col]);
        if (!type_not_null_smallint.equals(valid.type_not_null_smallint))
            error("type_null_smallint", type_not_null_smallint, valid.type_not_null_smallint);

        // col 13
        Integer type_null_integer = row[++col].equals("NULL") ? null : Integer.valueOf(row[col]);
        if ( (!(type_null_integer == null && valid.type_null_integer == null)) &&
             (!type_null_integer.equals(valid.type_null_integer)) )
            error("type_null_integer", type_null_integer, valid.type_null_integer);

        // col 14
        Integer type_not_null_integer = Integer.valueOf(row[++col]);
        if (!type_not_null_integer.equals(valid.type_not_null_integer))
            error("type_not_null_integer", type_not_null_integer, valid.type_not_null_integer);

        // col 15
        Long type_null_bigint = row[++col].equals("NULL") ? null : Long.valueOf(row[col]);
        if ( (!(type_null_bigint == null && valid.type_null_bigint == null)) &&
             (!type_null_bigint.equals(valid.type_null_bigint)) )
            error("type_null_bigint", type_null_bigint, valid.type_null_bigint);

        // col 16
        Long type_not_null_bigint = Long.valueOf(row[++col]);
        if (!type_not_null_bigint.equals(valid.type_not_null_bigint))
            error("type_not_null_bigint", type_not_null_bigint, valid.type_not_null_bigint);

        // The ExportToFileClient truncates microseconds. Construct a TimestampType here
        // that also truncates microseconds.
        TimestampType type_null_timestamp;
        if (row[++col].equals("NULL")) {  // col 17
            type_null_timestamp = null;
        } else {
            TimestampType tmp = new TimestampType(row[col]);
            type_null_timestamp = new TimestampType(tmp.asApproximateJavaDate());
        }

        if ( (!(type_null_timestamp == null && valid.type_null_timestamp == null)) &&
             (!type_null_timestamp.equals(valid.type_null_timestamp)) )
        {
            System.out.println("CSV value: " + row[col]);
            System.out.println("EXP value: " + valid.type_null_timestamp.toString());
            System.out.println("ACT value: " + type_null_timestamp.toString());
            error("type_null_timestamp", type_null_timestamp, valid.type_null_timestamp);
        }

        // col 18
        TimestampType type_not_null_timestamp = new TimestampType(row[++col]);
        if (!type_not_null_timestamp.equals(valid.type_not_null_timestamp))
            error("type_null_timestamp", type_not_null_timestamp, valid.type_not_null_timestamp);

        // col 19
        BigDecimal type_null_decimal = row[++col].equals("NULL") ? null : new BigDecimal(row[col]);
        if ( (!(type_null_decimal == null && valid.type_null_decimal == null)) &&
             (!type_null_decimal.equals(valid.type_null_decimal)) )
            error("type_null_decimal", type_null_decimal, valid.type_null_decimal);

        // col 20
        BigDecimal type_not_null_decimal = new BigDecimal(row[++col]);
        if (!type_not_null_decimal.equals(valid.type_not_null_decimal))
            error("type_not_null_decimal", type_not_null_decimal, valid.type_not_null_decimal);

        // col 21
        Double type_null_float = row[++col].equals("NULL") ? null : Double.valueOf(row[col]);
        if ( (!(type_null_float == null && valid.type_null_float == null)) &&
             (!type_null_float.equals(valid.type_null_float)) )
        {
            System.out.println("CSV value: " + row[col]);
            System.out.println("EXP value: " + valid.type_null_float);
            System.out.println("ACT value: " + type_null_float);
            System.out.println("valueOf():" + Double.valueOf("-2155882919525625344.000000000000"));
            System.out.flush();
            error("type_null_float", type_null_float, valid.type_null_float);
        }

        // col 22
        Double type_not_null_float = Double.valueOf(row[++col]);
        if (!type_not_null_float.equals(valid.type_not_null_float))
            error("type_not_null_float", type_not_null_float, valid.type_not_null_float);

        // col 23
        String type_null_varchar25 = row[++col].equals("NULL") ? null : row[col];
        if (!(type_null_varchar25 == valid.type_null_varchar25 ||
              type_null_varchar25.equals(valid.type_null_varchar25)))
            error("type_null_varchar25", type_null_varchar25, valid.type_null_varchar25);

        // col 24
        String type_not_null_varchar25 = row[++col];
        if (!type_not_null_varchar25.equals(valid.type_not_null_varchar25))
            error("type_not_null_varchar25", type_not_null_varchar25, valid.type_not_null_varchar25);

        // col 25
        String type_null_varchar128 = row[++col].equals("NULL") ? null : row[col];
        if (!(type_null_varchar128 == valid.type_null_varchar128 ||
              type_null_varchar128.equals(valid.type_null_varchar128)))
            error("type_null_varchar128", type_null_varchar128, valid.type_null_varchar128);

        // col 26
        String type_not_null_varchar128 = row[++col];
        if (!type_not_null_varchar128.equals(valid.type_not_null_varchar128))
            error("type_not_null_varchar128", type_not_null_varchar128, valid.type_not_null_varchar128);

        // col 27
        String type_null_varchar1024 = row[++col].equals("NULL") ? null : row[col];
        if (!(type_null_varchar1024 == valid.type_null_varchar1024 ||
              type_null_varchar1024.equals(valid.type_null_varchar1024)))
            error("type_null_varchar1024", type_null_varchar1024, valid.type_null_varchar1024);

        // col 28
        String type_not_null_varchar1024 = row[++col];
        if (!type_not_null_varchar1024.equals(valid.type_not_null_varchar1024))
            error("type_not_null_varchar1024", type_not_null_varchar1024, valid.type_not_null_varchar1024);
    }

    private void error(String msg, Object val, Object exp) throws ValidationErr {
        System.err.println("ERROR: " + msg + " " + val + " " + exp);
        throw new ValidationErr(msg, val, exp);
    }

    int m_partitions = 0;
    HashMap<Integer, TreeMap<Long,Long>> m_rowTxnIds =
        new HashMap<Integer, TreeMap<Long,Long>>();

    HashMap<Integer,Long> m_maxPartTxId = new HashMap<Integer,Long>();

    TreeMap<Long,Long> m_clientTxnIds = new TreeMap<Long,Long>();
    ArrayList<Long> m_clientTxnIdOrphans = new ArrayList<Long>();
    HashMap<Integer,AtomicLong> m_readUpTo = new HashMap<Integer, AtomicLong>();
    HashMap<Integer,Integer> m_checkedUpTo = new HashMap<Integer, Integer>();
    TreeSet<Long> m_clientOverFlow = new TreeSet<Long>();
    TreeSet<Long> m_staleTxnIds = new TreeSet<Long>();

    Queue<Pair<ChannelSftp, String>> m_exportFiles = new ArrayDeque<Pair<ChannelSftp, String>>();
    File m_clientPath = null;
    File[] m_clientFiles = {};
    int m_clientIndex = 0;
    boolean m_clientlogSeen = false;

    static {
        VoltDB.setDefaultTimezone();
    }

    public static void main(String[] args) throws Exception {
        ExportOnServerVerifier verifier = new ExportOnServerVerifier();
        try
        {
            boolean skinny = verifier.verifySetup(args);

            if (skinny) verifier.verifySkinny();
            else verifier.verifyFat();

            if (verifier.m_clientTxnIds.size() > 0)
            {
                System.err.println("ERROR: not all client txids were matched against exported txids");
                verifier.printClientTxIds();
                System.exit(1);
            }
        }
        catch(IOException e) {
            e.printStackTrace(System.err);
            System.exit(-1);
        }
        catch (ValidationErr e ) {
            System.err.println("Validation error: " + e.toString());
            System.exit(-1);
        }
        System.exit(0);
    }

    public static class RoughCSVTokenizer {

        private RoughCSVTokenizer() {
        }

        private static void moveToBuffer(List<String> resultBuffer, StringBuilder buf) {
            resultBuffer.add(buf.toString());
            buf.delete(0, buf.length());
        }

        public static String[] tokenize(String csv) {
            List<String> resultBuffer = new java.util.ArrayList<String>();

            if (csv != null) {
                int z = csv.length();
                Character openingQuote = null;
                boolean trimSpace = false;
                StringBuilder buf = new StringBuilder();

                for (int i = 0; i < z; ++i) {
                    char c = csv.charAt(i);
                    trimSpace = trimSpace && Character.isWhitespace(c);
                    if (c == '"' || c == '\'') {
                        if (openingQuote == null) {
                            openingQuote = c;
                            int bi = 0;
                            while (bi < buf.length()) {
                                if (Character.isWhitespace(buf.charAt(bi))) {
                                    buf.deleteCharAt(bi);
                                } else {
                                    bi++;
                                }
                            }
                        }
                        else if (openingQuote == c ) {
                            openingQuote = null;
                            trimSpace = true;
                        }
                    }
                    else if (c == '\\') {
                        if ((z > i + 1)
                            && ((csv.charAt(i + 1) == '"')
                                || (csv.charAt(i + 1) == '\\'))) {
                            buf.append(csv.charAt(i + 1));
                            ++i;
                        } else {
                            buf.append("\\");
                        }
                    } else {
                        if (openingQuote != null) {
                            buf.append(c);
                        } else {
                            if (c == ',') {
                                moveToBuffer(resultBuffer, buf);
                            } else {
                                if (!trimSpace) buf.append(c);
                            }
                        }
                    }
                }
                moveToBuffer(resultBuffer, buf);
            }

            String[] result = new String[resultBuffer.size()];
            return resultBuffer.toArray(result);
        }
    }
}
