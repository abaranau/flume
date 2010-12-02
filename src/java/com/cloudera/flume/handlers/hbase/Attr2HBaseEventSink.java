/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.flume.handlers.hbase;

import com.cloudera.flume.conf.Context;
import com.cloudera.flume.conf.SinkFactory;
import com.cloudera.flume.conf.SinkFactory.SinkBuilder;
import com.cloudera.flume.core.Event;
import com.cloudera.flume.core.EventSink;
import com.cloudera.util.Pair;
import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

/**
 * This generates an HBase output sink which puts event attributes into HBase record based on their names.
 * It is similar to {@link com.cloudera.flume.handlers.hbase.HBaseEventSink}, please refer to README.txt for basic steps.
 *
 * Sink has the next parameters: attr2hbase("table" [,"family"[, "writeBody"[,"attrPrefix"[,"writeBufferSize"[,"writeToWal"]]]]]).
 * "table"           - HBase table name to perform output into.
 * "sysFamily"       - Column family's name which is used to store "system" data (event's timestamp, host, priority).
 *                     In case this param is absent or ="" the sink doesn't write "system" data.
 * "writeBody"       - Indicates whether event's body should be written among other "system" data.
 *                     Default is "true" which means it should be written.
 *                     In case this param is absent or ="" the sink doesn't write "system" data.
 * "attrPrefix"      - Attributes with this prefix in key will be placed into HBase table. Default value: "2hb_".
 *                     Attribute key should be in the following format: "&lt;attrPrefix&gt;&lt;columnFamily&gt;:&lt;qualifier&gt;",
 *                     e.g. "2hb_user:name" means that its value will be placed into "user" column family with "name" qualifier.
 *                     Attribute with key "&lt;attrPrefix&gt;" SHOULD contain row key for Put,
 *                     otherwise (if no row can be extracted) the event is skipped and no records are written to the HBase table.
 *                     Next table shows what gets written into HBase table depending on the attribute name and other settings (in format columnFamily:qualifier->value, "-" means nothing is written).
 * <blockquote><table border=1>
 *   <tr>
 *     <th>Event's attr ("name"->"value")</th>
 *     <th>attrPrefix="2hb_", sysFamily=null</th>
 *     <th>attrPrefix="2hb_", sysFamily="sysfam"</th>
 *     <th>attrPrefix="", sysFamily="sysfam"</th>
 *     <th>attrPrefix="", sysFamily=null</th>
 *   </tr>
 *   <tr>
 *     <td>"any"->"foo"</td>
 *     <td>-</td>
 *     <td>-</td>
 *     <td>sysfam:any->foo</td>
 *     <td>-</td>
 *   </tr>
 *   <tr>
 *     <td>"colfam:col"->"foo"</td>
 *     <td>-</td>
 *     <td>-</td>
 *     <td>colfam:col->foo</td>
 *     <td>colfam:col->foo</td>
 *   </tr>
 *   <tr>
 *     <td>"2hb_any"->"foo"</td>
 *     <td>-</td>
 *     <td>sysfam:any->foo</td>
 *     <td>sysfam:2hb_any->foo</td>
 *     <td>-</td>
 *   </tr>
 *   <tr>
 *     <td>"2hb_colfam:col"->"foo"</td>
 *     <td>colfam:col->foo</td>
 *     <td>colfam:col->foo</td>
 *     <td>2hb_colfam:col->foo</td>
 *     <td>2hb_colfam:col->foo</td>
 *   </tr>
 * </table></blockquote>
 *
 * "writeBufferSize" - If provided, autoFlush for the HTable set to "false", and writeBufferSize is set to its value.
 *                     If not provided, by default autoFlush is set to "true" (default HTable setting).
 *                     This setting is valuable to boost HBase write speed.
 * "writeToWal"      - Determines whether WAL should be used during writing to HBase. If not provided Puts are written to WAL by default
 *                     This setting is valuable to boost HBase write speed, but decreases reliability level. Use it if you know what it does.
 *
 * The Sink also implements method getSinkBuilders(), so it can be used as Flume's extension plugin (see flume.plugin.classes property of flume-site.xml config details)
 */
public class Attr2HBaseEventSink extends EventSink.Base {
  private final static Logger LOG = Logger.getLogger(Attr2HBaseEventSink.class.getName());
  public static final String USAGE = "usage: attr2hbase(\"table\" [,\"sysFamily\"[, \"writeBody\"[,\"attrPrefix\"[,\"writeBufferSize\"[,\"writeToWal\"]]]]])";

  private String tableName;

  /**
   * Column family name to store system data like timestamp of event, host
   */
  private byte[] systemFamilyName;
  private String attrPrefix = "2hb_";
  private long writeBufferSize = 0L;
  private boolean writeToWal = true;
  private boolean writeBody = true;

  private Configuration config;
  private HTable table;

  /**
   * Instantiates sink.
   * See detailed explanation of parameters and their values at {@link com.cloudera.flume.handlers.hbase.Attr2HBaseEventSink}
   * @param tableName HBase table name to output data into
   * @param systemFamilyName name of columnFamily where to store event's system data
   * @param writeBody Indicates whether event's body should be written
   * @param attrPrefix attributes with this prefix in key will be placed into HBase table
   * @param writeBufferSize HTable's writeBufferSize
   * @param writeToWal determines whether WAL should be used during writing to HBase
   */
  public Attr2HBaseEventSink(String tableName, String systemFamilyName, boolean writeBody, String attrPrefix,
                                 long writeBufferSize, boolean writeToWal) {
    // You need a configuration object to tell the client where to connect.
    // When you create a HBaseConfiguration, it reads in whatever you've set
    // into your hbase-site.xml and in hbase-default.xml, as long as these can
    // be found on the CLASSPATH
    this(tableName, systemFamilyName, writeBody, attrPrefix, writeBufferSize, writeToWal, HBaseConfiguration.create());
  }

  /**
   * Instantiates sink.
   * See detailed explanation of parameters and their values at {@link com.cloudera.flume.handlers.hbase.Attr2HBaseEventSink}
   * @param tableName HBase table name to output data into
   * @param systemFamilyName name of columnFamily where to store event's system data
   * @param writeBody Indicates whether event's body should be written
   * @param attrPrefix attributes with this prefix in key will be placed into HBase table
   * @param writeBufferSize HTable's writeBufferSize
   * @param writeToWal determines whether WAL should be used during writing to HBase
   * @param config HBase configuration
   */
  public Attr2HBaseEventSink(String tableName, String systemFamilyName, boolean writeBody, String attrPrefix,
                                 long writeBufferSize, boolean writeToWal, Configuration config)
  {
    Preconditions.checkNotNull(tableName, "HBase table's name MUST be provided.");
    this.tableName = tableName;
    // systemFamilyName can be null or empty String, which means "don't store "system" data
    if (systemFamilyName != null && !"".equals(systemFamilyName)) {
      this.systemFamilyName = Bytes.toBytes(systemFamilyName);
    }
    this.writeBody = writeBody;
    if (attrPrefix!= null) {
      this.attrPrefix = attrPrefix;
    }

    this.writeBufferSize = writeBufferSize;
    this.writeToWal = writeToWal;

    this.config = config;
  }

  @Override
  public void append(Event e) throws IOException {
    Put p = createPut(e);

    if (p != null && p.getFamilyMap().size() > 0) {
      p.setWriteToWAL(writeToWal);
      table.put(p);
    }
  }

  // Made as package-private for unit-testing
  Put createPut(Event e) {
    Put p;
    // Attribute with key "<attrPrefix>" contains row key for Put
    if (e.getAttrs().containsKey(attrPrefix)) {
      p = new Put(e.getAttrs().get(attrPrefix));
    } else {
      LOG.warn("Cannot extract key for HBase row, the attribute with key '" + attrPrefix + "' is not present in event's data.");
      return null;
    }

    if (systemFamilyName != null) {
      p.add(systemFamilyName, Bytes.toBytes("timestamp"),
          Bytes.toBytes(e.getTimestamp()));
      p.add(systemFamilyName, Bytes.toBytes("host"),
          Bytes.toBytes(e.getHost()));
      if (e.getPriority() != null) {
        p.add(systemFamilyName, Bytes.toBytes("priority"),
            Bytes.toBytes(e.getPriority().toString()));
      }
      if (writeBody) {
        p.add(systemFamilyName, Bytes.toBytes("event"), e.getBody());
      }
    }

    for (Entry<String, byte[]> a : e.getAttrs().entrySet()) {
      attemptToAddAttribute(p, a);
    }
    return p;
  }

  // Made as package-private for unit-testing
  // Entry here represents event's attribute: key is attribute name and value is attribute value
  void attemptToAddAttribute(Put p, Entry<String, byte[]> a) {
    String attrKey = a.getKey();
    if (attrKey.startsWith(attrPrefix) && attrKey.length() > attrPrefix.length()) {
      String keyWithoutPrefix = attrKey.substring(attrPrefix.length());
      String[] col = keyWithoutPrefix.split(":", 2); // please see the javadoc of attrPrefix format for more info
      // if both columnFamily and qualifier can be fetched from attribute's key
      boolean hasColumnFamilyAndQualifier = col.length == 2 && col[0].length() > 0 && col[1].length() > 0;
      if (hasColumnFamilyAndQualifier) {
        p.add(Bytes.toBytes(col[0]), Bytes.toBytes(col[1]), a.getValue());
        return;
      } else if (systemFamilyName != null) {
        p.add(systemFamilyName, Bytes.toBytes(keyWithoutPrefix), a.getValue());
        return;
      } else {
        LOG.warn("Cannot determine column family and/or qualifier for attribute, attribute name: " + attrKey);
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (table != null) {
      table.close(); // performs flushCommits() internally, so we are good when autoFlush=false
      table = null;
    }
  }

  @Override
  public void open() throws IOException {
    if (table != null) {
      throw new IllegalStateException("HTable is already initialized. Looks like sink close() hasn't been proceeded properly.");
    }
    // This instantiates an HTable object that connects you to
    // the tableName table.
    table = new HTable(config, tableName);
    if (writeBufferSize > 0) {
      table.setAutoFlush(false);
      table.setWriteBufferSize(writeBufferSize);
    }
  }

  public static SinkBuilder builder() {
    return new SinkBuilder() {

      @Override
      public EventSink build(Context context, String... argv) {
        Preconditions.checkArgument(argv.length >= 1,
                USAGE);

        // TODO: check that arguments has proper types

        String tableName = argv[0];
        String systemFamilyName = argv.length >= 2 ? argv[1] : null;
        // TODO: add more sophisticated boolean conversion
        boolean writeBody = argv.length >= 3 ? Boolean.valueOf(argv[2].toLowerCase()) : true;
        String attrPrefix = argv.length >= 4 ? argv[3] : null;
        long bufferSize = argv.length >= 5 ? Long.valueOf(argv[4]) : 0;
        // TODO: add more sophisticated boolean conversion
        boolean writeToWal = argv.length >= 6 ? Boolean.valueOf(argv[5].toLowerCase()) : true;
        return new Attr2HBaseEventSink(tableName, systemFamilyName, writeBody, attrPrefix, bufferSize, writeToWal);
      }

    };
  }

  public static List<Pair<String, SinkFactory.SinkBuilder>> getSinkBuilders() {
    return Arrays.asList(new Pair<String, SinkFactory.SinkBuilder>("attr2hbase", builder()));
  }
}

