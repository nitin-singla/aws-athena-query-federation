/*-
 * #%L
 * athena-example
 * %%
 * Copyright (C) 2019 Amazon Web Services
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
package com.amazonaws.athena.connectors.example;

import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.data.BlockUtils;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.proto.domain.Split;
import com.amazonaws.athena.connector.lambda.proto.domain.TableName;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.domain.predicate.Range;
import com.amazonaws.athena.connector.lambda.domain.predicate.SortedRangeSet;
import com.amazonaws.athena.connector.lambda.domain.predicate.ValueSet;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableLayoutRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableLayoutResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListSchemasRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListSchemasResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListTablesRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListTablesResponse;
import com.amazonaws.athena.connector.lambda.proto.security.FederatedIdentity;
import com.amazonaws.athena.connector.lambda.security.LocalKeyFactory;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.google.common.collect.ImmutableList;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.amazonaws.athena.connector.lambda.domain.predicate.Constraints.DEFAULT_NO_LIMIT;
import static com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufSerDe.UNLIMITED_PAGE_SIZE_VALUE;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class ExampleMetadataHandlerTest
{
    private static final Logger logger = LoggerFactory.getLogger(ExampleMetadataHandlerTest.class);

    private ExampleMetadataHandler handler = new ExampleMetadataHandler(new LocalKeyFactory(),
            mock(AWSSecretsManager.class),
            mock(AmazonAthena.class),
            "spill-bucket",
            "spill-prefix",
            com.google.common.collect.ImmutableMap.of());

    private boolean enableTests = System.getenv("publishing") != null &&
            System.getenv("publishing").equalsIgnoreCase("true");

    private BlockAllocatorImpl allocator;

    @Before
    public void setUp()
    {
        logger.info("setUpBefore - enter");
        allocator = new BlockAllocatorImpl();
        logger.info("setUpBefore - exit");
    }

    @After
    public void after()
    {
        allocator.close();
    }

    @Test
    public void doListSchemaNames()
    {
        if (!enableTests) {
            //We do this because until you complete the tutorial these tests will fail.
            //This is how we avoid breaking the build but still have a useful tutorial. We are also duplicateing this block
            //on purpose since this is a somewhat odd pattern.
            logger.info("doListSchemaNames: Tests are disabled, to enable them set the 'publishing' environment variable " +
                    "using maven clean install -Dpublishing=true");
            return;
        }

        logger.info("doListSchemas - enter");
        ListSchemasRequest req = ListSchemasRequest.newBuilder().setIdentity(fakeIdentity()).setQueryId("queryId").setCatalogName("default").build();
        ListSchemasResponse res = handler.doListSchemaNames(allocator, req);
        logger.info("doListSchemas - {}", res.getSchemasList());
        assertFalse(res.getSchemasList().isEmpty());
        logger.info("doListSchemas - exit");
    }

    @Test
    public void doListTables()
    {
        if (!enableTests) {
            //We do this because until you complete the tutorial these tests will fail.
            //This is how we avoid breaking the build but still have a useful tutorial. We are also duplicateing this block
            //on purpose since this is a somewhat odd pattern.
            logger.info("doListTables: Tests are disabled, to enable them set the 'publishing' environment variable " +
                    "using maven clean install -Dpublishing=true");
            return;
        }

        logger.info("doListTables - enter");

        // Test request with unlimited page size
        logger.info("doListTables - Test unlimited page size");
        ListTablesRequest req = new ListTablesRequest(fakeIdentity(), "queryId", "default",
                "schema1", null, UNLIMITED_PAGE_SIZE_VALUE);
        ListTablesResponse res = handler.doListTables(allocator, req);
        ListTablesResponse expectedResponse = new ListTablesResponse("default",
                new ImmutableList.Builder<TableName>()
                        .add(TableName.newBuilder().setSchemaName("schema1").setTableName("table1")).build()
                        .add(TableName.newBuilder().setSchemaName("schema1").setTableName("table2")).build()
                        .add(TableName.newBuilder().setSchemaName("schema1").setTableName("table3")).build()
                        .build(), null);
        logger.info("doListTables - {}", res);
        assertEquals("Expecting a different response", expectedResponse, res);

        // Test first paginated request with pageSize: 2, nextToken: null
        logger.info("doListTables - Test first pagination request");
        req = ListTablesRequest.newBuilder().setIdentity(fakeIdentity()).setQueryId("queryId").setCatalogName("default").setSchemaName("schema1").setPageSize(2).build();
        expectedResponse = new ListTablesResponse("default",
                new ImmutableList.Builder<TableName>()
                        .add(TableName.newBuilder().setSchemaName("schema1").setTableName("table1")).build()
                        .add(TableName.newBuilder().setSchemaName("schema1").setTableName("table2")).build()
                        .build(), "table3");
        res = handler.doListTables(allocator, req);
        logger.info("doListTables - {}", res);
        assertEquals("Expecting a different response", expectedResponse, res);

        // Test second paginated request with pageSize: 2, nextToken: res.getNextToken()
        logger.info("doListTables - Test second pagination request");
        req = new ListTablesRequest(fakeIdentity(), "queryId", "default", "schema1",
                res.getNextToken(), 2);
        expectedResponse = new ListTablesResponse("default",
                new ImmutableList.Builder<TableName>()
                        .add(TableName.newBuilder().setSchemaName("schema1").setTableName("table3")).build()
                        .build(), null);
        res = handler.doListTables(allocator, req);
        logger.info("doListTables - {}", res);
        assertEquals("Expecting a different response", expectedResponse, res);

        logger.info("doListTables - exit");
    }

    @Test
    public void doGetTable()
    {
        if (!enableTests) {
            //We do this because until you complete the tutorial these tests will fail.
            //This is how we avoid breaking the build but still have a useful tutorial. We are also duplicateing this block
            //on purpose since this is a somewhat odd pattern.
            logger.info("doGetTable: Tests are disabled, to enable them set the 'publishing' environment variable " +
                    "using maven clean install -Dpublishing=true");
            return;
        }

        logger.info("doGetTable - enter");
        GetTableRequest req = new GetTableRequest(fakeIdentity(), "queryId", "default",
                TableName.newBuilder().setSchemaName("schema1").setTableName("table1")).build();
        GetTableResponse res = handler.doGetTable(allocator, req);
        assertTrue(ProtobufMessageConverter.fromProtoSchema(allocator, res.getSchema()).getFields().size() > 0);
        assertTrue(ProtobufMessageConverter.fromProtoSchema(allocator, res.getSchema()).getCustomMetadata().size() > 0);
        logger.info("doGetTable - {}", res);
        logger.info("doGetTable - exit");
    }

    @Test
    public void getPartitions()
            throws Exception
    {
        if (!enableTests) {
            //We do this because until you complete the tutorial these tests will fail.
            //This is how we avoid breaking the build but still have a useful tutorial. We are also duplicateing this block
            //on purpose since this is a somewhat odd pattern.
            logger.info("getPartitions: Tests are disabled, to enable them set the 'publishing' environment variable " +
                    "using maven clean install -Dpublishing=true");
            return;
        }

        logger.info("doGetTableLayout - enter");

        Schema tableSchema = SchemaBuilder.newBuilder()
                .addIntField("day")
                .addIntField("month")
                .addIntField("year")
                .build();

        Set<String> partitionCols = new HashSet<>();
        partitionCols.add("day");
        partitionCols.add("month");
        partitionCols.add("year");

        Map<String, ValueSet> constraintsMap = new HashMap<>();

        constraintsMap.put("day", SortedRangeSet.copyOf(Types.MinorType.INT.getType(),
                ImmutableList.of(Range.greaterThan(allocator, Types.MinorType.INT.getType(), 0)), false));

        constraintsMap.put("month", SortedRangeSet.copyOf(Types.MinorType.INT.getType(),
                ImmutableList.of(Range.greaterThan(allocator, Types.MinorType.INT.getType(), 0)), false));

        constraintsMap.put("year", SortedRangeSet.copyOf(Types.MinorType.INT.getType(),
                ImmutableList.of(Range.greaterThan(allocator, Types.MinorType.INT.getType(), 2000)), false));

        GetTableLayoutRequest req = null;
        GetTableLayoutResponse res = null;
        try {

            req = new GetTableLayoutRequest(fakeIdentity(), "queryId", "default",
                    TableName.newBuilder().setSchemaName("schema1").setTableName("table1").build(),
                    new Constraints(constraintsMap),
                    tableSchema,
                    partitionCols);

            res = handler.doGetTableLayout(allocator, req);

            logger.info("doGetTableLayout - {}", res);
            Block partitions = ProtobufMessageConverter.fromProtoBlock(blockAllocator, res.getPartitions());
            for (int row = 0; row < partitions.getRowCount() && row < 10; row++) {
                logger.info("doGetTableLayout:{} {}", row, BlockUtils.rowToString(partitions, row));
            }
            assertTrue(partitions.getRowCount() > 0);
            logger.info("doGetTableLayout: partitions[{}]", partitions.getRowCount());
        }
        finally {
            try {
                req.close();
                res.close();
            }
            catch (Exception ex) {
                logger.error("doGetTableLayout: ", ex);
            }
        }

        logger.info("doGetTableLayout - exit");
    }

    @Test
    public void doGetSplits()
    {
        if (!enableTests) {
            //We do this because until you complete the tutorial these tests will fail.
            //This is how we avoid breaking the build but still have a useful tutorial. We are also duplicateing this block
            //on purpose since this is a somewhat odd pattern.
            logger.info("doGetSplits: Tests are disabled, to enable them set the 'publishing' environment variable " +
                    "using maven clean install -Dpublishing=true");
            return;
        }

        logger.info("doGetSplits: enter");

        String yearCol = "year";
        String monthCol = "month";
        String dayCol = "day";

        //This is the schema that ExampleMetadataHandler has layed out for a 'Partition' so we need to populate this
        //minimal set of info here.
        Schema schema = SchemaBuilder.newBuilder()
                .addIntField(yearCol)
                .addIntField(monthCol)
                .addIntField(dayCol)
                .build();

        List<String> partitionCols = new ArrayList<>();
        partitionCols.add(yearCol);
        partitionCols.add(monthCol);
        partitionCols.add(dayCol);

        Map<String, ValueSet> constraintsMap = new HashMap<>();

        Block partitions = allocator.createBlock(schema);

        int num_partitions = 10;
        for (int i = 0; i < num_partitions; i++) {
            BlockUtils.setValue(partitions.getFieldVector(yearCol), i, 2016 + i);
            BlockUtils.setValue(partitions.getFieldVector(monthCol), i, (i % 12) + 1);
            BlockUtils.setValue(partitions.getFieldVector(dayCol), i, (i % 28) + 1);
        }
        partitions.setRowCount(num_partitions);

        String continuationToken = null;
        GetSplitsRequest originalReq = new GetSplitsRequest(fakeIdentity(), "queryId", "catalog_name",
                TableName.newBuilder().setSchemaName("schema").setTableName("table_name").build(),
                partitions,
                partitionCols,
                new Constraints(constraintsMap, Collections.emptyList(), Collections.emptyList(), DEFAULT_NO_LIMIT),
                continuationToken);
        int numContinuations = 0;
        do {
            GetSplitsRequest req = new GetSplitsRequest(originalReq, continuationToken);

            logger.info("doGetSplits: req[{}]", req);
            MetadataResponse rawResponse = handler.doGetSplits(allocator, req);
            assertEquals(MetadataRequestType.GET_SPLITS, rawResponse.getRequestType());

            GetSplitsResponse response = (GetSplitsResponse) rawResponse;
            continuationToken = response.getContinuationToken();

            logger.info("doGetSplits: continuationToken[{}] - splits[{}]", continuationToken, response.getSplitsList());

            for (Split nextSplit : response.getSplitsList()) {
                assertNotNull(nextSplit.getProperty("year"));
                assertNotNull(nextSplit.getProperty("month"));
                assertNotNull(nextSplit.getProperty("day"));
            }

            assertTrue(!response.getSplitsList().isEmpty());

            if (continuationToken != null) {
                numContinuations++;
            }
        }
        while (continuationToken != null);

        assertTrue(numContinuations == 0);

        logger.info("doGetSplits: exit");
    }

    private static FederatedIdentity fakeIdentity()
    {
        return FederatedIdentity.newBuilder().setArn("arn").setAccount("account").build();
    }
}
