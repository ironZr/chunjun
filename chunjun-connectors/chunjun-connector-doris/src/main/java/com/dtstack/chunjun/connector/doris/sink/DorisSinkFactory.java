/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
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

package com.dtstack.chunjun.connector.doris.sink;

import com.dtstack.chunjun.conf.OperatorConf;
import com.dtstack.chunjun.conf.SyncConf;
import com.dtstack.chunjun.connector.doris.converter.DorisRowTypeConverter;
import com.dtstack.chunjun.connector.doris.options.DorisConf;
import com.dtstack.chunjun.connector.doris.options.LoadConf;
import com.dtstack.chunjun.connector.doris.options.LoadConfBuilder;
import com.dtstack.chunjun.connector.jdbc.adapter.ConnectionAdapter;
import com.dtstack.chunjun.connector.jdbc.conf.ConnectionConf;
import com.dtstack.chunjun.connector.jdbc.dialect.JdbcDialect;
import com.dtstack.chunjun.connector.jdbc.exclusion.FieldNameExclusionStrategy;
import com.dtstack.chunjun.connector.jdbc.util.JdbcUtil;
import com.dtstack.chunjun.connector.mysql.dialect.MysqlDialect;
import com.dtstack.chunjun.converter.AbstractRowConverter;
import com.dtstack.chunjun.converter.RawTypeConverter;
import com.dtstack.chunjun.sink.SinkFactory;
import com.dtstack.chunjun.util.GsonUtil;
import com.dtstack.chunjun.util.TableUtil;

import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.lang3.tuple.Pair;

import java.sql.Connection;
import java.util.List;
import java.util.Properties;

import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DESERIALIZE_ARROW_ASYNC_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DESERIALIZE_QUEUE_SIZE_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_BATCH_SIZE_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_DESERIALIZE_ARROW_ASYNC_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_DESERIALIZE_QUEUE_SIZE_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_EXEC_MEM_LIMIT_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_REQUEST_CONNECT_TIMEOUT_MS_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_REQUEST_QUERY_TIMEOUT_S_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_REQUEST_READ_TIMEOUT_MS_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.DORIS_REQUEST_RETRIES_DEFAULT;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.EXEC_MEM_LIMIT_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.LOAD_OPTIONS_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.REQUEST_BATCH_SIZE_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.REQUEST_CONNECT_TIMEOUT_MS_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.REQUEST_QUERY_TIMEOUT_S_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.REQUEST_READ_TIMEOUT_MS_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.REQUEST_RETRIES_KEY;
import static com.dtstack.chunjun.connector.doris.options.DorisKeys.REQUEST_TABLET_SIZE_KEY;

public class DorisSinkFactory extends SinkFactory {
    private final DorisConf options;

    public DorisSinkFactory(SyncConf syncConf) {
        super(syncConf);

        final OperatorConf parameter = syncConf.getWriter();

        Gson gson =
                new GsonBuilder()
                        .registerTypeAdapter(
                                ConnectionConf.class, new ConnectionAdapter("SinkConnectionConf"))
                        .addDeserializationExclusionStrategy(
                                new FieldNameExclusionStrategy("column"))
                        .create();
        GsonUtil.setTypeAdapter(gson);
        options = gson.fromJson(gson.toJson(syncConf.getWriter().getParameter()), DorisConf.class);

        LoadConfBuilder loadConfBuilder = new LoadConfBuilder();

        Properties properties = parameter.getProperties(LOAD_OPTIONS_KEY, new Properties());
        LoadConf loadConf =
                loadConfBuilder
                        .setRequestTabletSize(
                                (int)
                                        properties.getOrDefault(
                                                REQUEST_TABLET_SIZE_KEY, Integer.MAX_VALUE))
                        .setRequestConnectTimeoutMs(
                                (int)
                                        properties.getOrDefault(
                                                REQUEST_CONNECT_TIMEOUT_MS_KEY,
                                                DORIS_REQUEST_CONNECT_TIMEOUT_MS_DEFAULT))
                        .setRequestReadTimeoutMs(
                                (int)
                                        properties.getOrDefault(
                                                REQUEST_READ_TIMEOUT_MS_KEY,
                                                DORIS_REQUEST_READ_TIMEOUT_MS_DEFAULT))
                        .setRequestQueryTimeoutMs(
                                (int)
                                        properties.getOrDefault(
                                                REQUEST_QUERY_TIMEOUT_S_KEY,
                                                DORIS_REQUEST_QUERY_TIMEOUT_S_DEFAULT))
                        .setRequestRetries(
                                (int)
                                        properties.getOrDefault(
                                                REQUEST_RETRIES_KEY, DORIS_REQUEST_RETRIES_DEFAULT))
                        .setRequestBatchSize(
                                (int)
                                        properties.getOrDefault(
                                                REQUEST_BATCH_SIZE_KEY, DORIS_BATCH_SIZE_DEFAULT))
                        .setExecMemLimit(
                                (long)
                                        properties.getOrDefault(
                                                EXEC_MEM_LIMIT_KEY, DORIS_EXEC_MEM_LIMIT_DEFAULT))
                        .setDeserializeQueueSize(
                                (int)
                                        properties.getOrDefault(
                                                DESERIALIZE_QUEUE_SIZE_KEY,
                                                DORIS_DESERIALIZE_QUEUE_SIZE_DEFAULT))
                        .setDeserializeArrowAsync(
                                (boolean)
                                        properties.getOrDefault(
                                                DESERIALIZE_ARROW_ASYNC_KEY,
                                                DORIS_DESERIALIZE_ARROW_ASYNC_DEFAULT))
                        .build();

        options.setColumn(syncConf.getWriter().getFieldList());
        options.setLoadProperties(properties);
        options.setLoadConf(loadConf);
        super.initCommonConf(options);
    }

    @Override
    public DataStreamSink<RowData> createSink(DataStream<RowData> dataSet) {
        if (options.getFeNodes() != null) {
            DorisHttpOutputFormatBuilder builder = new DorisHttpOutputFormatBuilder();
            builder.setDorisOptions(options);
            return createOutput(dataSet, builder.finish());
        }

        DorisJdbcOutputFormatBuilder builder =
                new DorisJdbcOutputFormatBuilder(new DorisJdbcOutputFormat());

        MysqlDialect dialect = new MysqlDialect();
        initColumnInfo(options, dialect, builder);
        builder.setJdbcConf(options);
        builder.setDdlConf(ddlConf);

        builder.setJdbcDialect(dialect);

        AbstractRowConverter rowConverter;
        final RowType rowType = TableUtil.createRowType(options.getColumn(), getRawTypeConverter());
        // 同步任务使用transform
        if (!useAbstractBaseColumn) {
            rowConverter = dialect.getRowConverter(rowType);
        } else {
            rowConverter = dialect.getColumnConverter(rowType, options);
        }
        builder.setRowConverter(rowConverter, useAbstractBaseColumn);
        return createOutput(dataSet, builder.finish());
    }

    protected void initColumnInfo(
            DorisConf conf, JdbcDialect dialect, DorisJdbcOutputFormatBuilder builder) {
        Connection conn = JdbcUtil.getConnection(conf, dialect);

        // get table metadata
        Tuple3<String, String, String> tableIdentify = dialect.getTableIdentify().apply(conf);
        Pair<List<String>, List<String>> tableMetaData =
                JdbcUtil.getTableMetaData(
                        tableIdentify.f0, tableIdentify.f1, tableIdentify.f2, conn);

        Pair<List<String>, List<String>> selectedColumnInfo =
                JdbcUtil.buildColumnWithMeta(conf, tableMetaData, null);
        builder.setColumnNameList(selectedColumnInfo.getLeft());
        builder.setColumnTypeList(selectedColumnInfo.getRight());
        JdbcUtil.closeDbResources(null, null, conn, false);
    }

    @Override
    public RawTypeConverter getRawTypeConverter() {
        return DorisRowTypeConverter::apply;
    }
}
