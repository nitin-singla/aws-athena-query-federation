/*-
 * #%L
 * athena-datalakegen2
 * %%
 * Copyright (C) 2019 - 2022 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.datalakegen2;

import com.amazonaws.athena.connector.lambda.QueryStatusChecker;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.data.BlockSpiller;
import com.amazonaws.athena.connector.lambda.proto.domain.Split;
import com.amazonaws.athena.connector.lambda.proto.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.proto.records.ReadRecordsRequest;
import com.amazonaws.athena.connectors.jdbc.connection.DatabaseConnectionConfig;
import com.amazonaws.athena.connectors.jdbc.connection.JdbcConnectionFactory;
import com.amazonaws.athena.connectors.jdbc.manager.JdbcRecordHandler;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;

public class DataLakeGen2MuxRecordHandlerTest
{
    private Map<String, JdbcRecordHandler> recordHandlerMap;
    private DataLakeGen2RecordHandler dataLakeGen2RecordHandler;
    private JdbcRecordHandler jdbcRecordHandler;
    private AmazonS3 amazonS3;
    private AWSSecretsManager secretsManager;
    private AmazonAthena athena;
    private QueryStatusChecker queryStatusChecker;
    private JdbcConnectionFactory jdbcConnectionFactory;
    private BlockAllocator blockAllocator;

    @Before
    public void setup()
    {
        this.dataLakeGen2RecordHandler = Mockito.mock(DataLakeGen2RecordHandler.class);
        this.recordHandlerMap = Collections.singletonMap(DataLakeGen2Constants.NAME, this.dataLakeGen2RecordHandler);
        this.amazonS3 = Mockito.mock(AmazonS3.class);
        this.secretsManager = Mockito.mock(AWSSecretsManager.class);
        this.athena = Mockito.mock(AmazonAthena.class);
        this.queryStatusChecker = Mockito.mock(QueryStatusChecker.class);
        this.jdbcConnectionFactory = Mockito.mock(JdbcConnectionFactory.class);
        DatabaseConnectionConfig databaseConnectionConfig = new DatabaseConnectionConfig("testCatalog", DataLakeGen2Constants.NAME,
                "datalakegentwo://jdbc:sqlserver://hostname/${testSecret}", "testSecret");
        this.jdbcRecordHandler = new DataLakeGen2MuxRecordHandler(this.amazonS3, this.secretsManager, this.athena, this.jdbcConnectionFactory, databaseConnectionConfig, this.recordHandlerMap, com.google.common.collect.ImmutableMap.of("spill_bucket", "asdf_spill_bucket_loc"));
        this.blockAllocator = new BlockAllocatorImpl();
    }

    @Test
    public void readWithConstraint()
            throws Exception
    {
        BlockSpiller blockSpiller = Mockito.mock(BlockSpiller.class);
        ReadRecordsRequest readRecordsRequest = ReadRecordsRequest.newBuilder().setCatalogName(DataLakeGen2Constants.NAME).build();
        this.jdbcRecordHandler.readWithConstraint(blockAllocator, blockSpiller, readRecordsRequest, queryStatusChecker);
        Mockito.verify(this.dataLakeGen2RecordHandler, Mockito.times(1)).readWithConstraint(Mockito.eq(blockAllocator), Mockito.eq(blockSpiller), Mockito.eq(readRecordsRequest), Mockito.eq(queryStatusChecker));
    }

    @Test(expected = RuntimeException.class)
    public void readWithConstraintWithUnsupportedCatalog()
            throws Exception
    {
        BlockSpiller blockSpiller = Mockito.mock(BlockSpiller.class);
        ReadRecordsRequest readRecordsRequest = ReadRecordsRequest.newBuilder().setCatalogName("unsupportedCatalog").build();
        this.jdbcRecordHandler.readWithConstraint(blockAllocator, blockSpiller, readRecordsRequest, queryStatusChecker);
    }

    @Test
    public void buildSplitSql()
            throws SQLException
    {
        ReadRecordsRequest readRecordsRequest = ReadRecordsRequest.newBuilder().setCatalogName(DataLakeGen2Constants.NAME).build();
        Connection jdbcConnection = Mockito.mock(Connection.class);
        TableName tableName = TableName.newBuilder().setSchemaName("testSchema").setTableName("tableName").build();
        Schema schema = Mockito.mock(Schema.class);
        Constraints constraints = Mockito.mock(Constraints.class);
        Split split = Split.newBuilder().build();
        this.jdbcRecordHandler.buildSplitSql(jdbcConnection, DataLakeGen2Constants.NAME, tableName, schema, constraints, split);
        Mockito.verify(this.dataLakeGen2RecordHandler, Mockito.times(1)).buildSplitSql(Mockito.eq(jdbcConnection), Mockito.eq(DataLakeGen2Constants.NAME), Mockito.eq(tableName), Mockito.eq(schema), Mockito.eq(constraints), Mockito.eq(split));
    }
}
