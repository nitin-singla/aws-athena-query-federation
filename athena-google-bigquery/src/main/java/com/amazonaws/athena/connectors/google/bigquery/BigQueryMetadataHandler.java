
/*-
 * #%L
 * athena-google-bigquery
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
package com.amazonaws.athena.connectors.google.bigquery;

import com.amazonaws.athena.connector.lambda.QueryStatusChecker;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockWriter;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.domain.predicate.functions.StandardFunctions;
import com.amazonaws.athena.connector.lambda.handlers.MetadataHandler;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.DataSourceOptimizations;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.OptimizationSubType;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.pushdown.ComplexExpressionPushdownSubType;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.pushdown.FilterPushdownSubType;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.pushdown.LimitPushdownSubType;
import com.amazonaws.athena.connector.lambda.metadata.optimizations.pushdown.TopNPushdownSubType;
import com.amazonaws.athena.connector.lambda.proto.domain.Split;
import com.amazonaws.athena.connector.lambda.proto.domain.TableName;
import com.amazonaws.athena.connector.lambda.proto.domain.spill.SpillLocation;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetDataSourceCapabilitiesRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetDataSourceCapabilitiesResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableLayoutRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListSchemasRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListSchemasResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListTablesRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.ListTablesResponse;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufMessageConverter;
import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import com.google.common.collect.ImmutableMap;
import org.apache.arrow.vector.types.pojo.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufSerDe.UNLIMITED_PAGE_SIZE_VALUE;
import static com.amazonaws.athena.connectors.google.bigquery.BigQueryUtils.fixCaseForDatasetName;
import static com.amazonaws.athena.connectors.google.bigquery.BigQueryUtils.fixCaseForTableName;
import static com.amazonaws.athena.connectors.google.bigquery.BigQueryUtils.translateToArrowType;

public class BigQueryMetadataHandler
    extends MetadataHandler
{
    private static final Logger logger = LoggerFactory.getLogger(BigQueryMetadataHandler.class);
    private final String projectName = configOptions.get(BigQueryConstants.GCP_PROJECT_ID);

    BigQueryMetadataHandler(java.util.Map<String, String> configOptions)
    {
        super(BigQueryConstants.SOURCE_TYPE, configOptions);
    }

    @Override
    public GetDataSourceCapabilitiesResponse doGetDataSourceCapabilities(BlockAllocator allocator, GetDataSourceCapabilitiesRequest request)
    {
        ImmutableMap.Builder<String, List<OptimizationSubType>> capabilities = ImmutableMap.builder();
        capabilities.put(DataSourceOptimizations.SUPPORTS_FILTER_PUSHDOWN.withSupportedSubTypes(
                FilterPushdownSubType.SORTED_RANGE_SET, FilterPushdownSubType.NULLABLE_COMPARISON
        ));
        capabilities.put(DataSourceOptimizations.SUPPORTS_COMPLEX_EXPRESSION_PUSHDOWN.withSupportedSubTypes(
                ComplexExpressionPushdownSubType.SUPPORTED_FUNCTION_EXPRESSION_TYPES
                        .withSubTypeProperties(Arrays.stream(StandardFunctions.values())
                                .map(standardFunctions -> standardFunctions.getFunctionName().getFunctionName())
                                .toArray(String[]::new))
        ));
        capabilities.put(DataSourceOptimizations.SUPPORTS_TOP_N_PUSHDOWN.withSupportedSubTypes(
                TopNPushdownSubType.SUPPORTS_ORDER_BY
        ));
        capabilities.put(DataSourceOptimizations.SUPPORTS_LIMIT_PUSHDOWN.withSupportedSubTypes(
                LimitPushdownSubType.INTEGER_CONSTANT
        ));

        return GetDataSourceCapabilitiesResponse.newBuilder().setCatalogName(request.getCatalogName()).putAllCapabilities(ProtobufMessageConverter.toProtoCapabilities(capabilities.build())).build();
    }

    @Override
    public ListSchemasResponse doListSchemaNames(BlockAllocator blockAllocator, ListSchemasRequest listSchemasRequest)
    {
        try {
            logger.info("doListSchemaNames called with Catalog: {}", listSchemasRequest.getCatalogName());

            final List<String> schemas = new ArrayList<>();
            BigQuery bigQuery = BigQueryUtils.getBigQueryClient(configOptions);
            Page<Dataset> response = bigQuery.listDatasets(projectName, BigQuery.DatasetListOption.pageSize(100));
            if (response == null) {
                logger.info("Dataset does not contain any models: {}");
            }
            else {
                do {
                    for (Dataset dataset : response.iterateAll()) {
                        if (schemas.size() > BigQueryConstants.MAX_RESULTS) {
                            throw new BigQueryExceptions.TooManyTablesException();
                        }
                        schemas.add(dataset.getDatasetId().getDataset().toLowerCase());
                        logger.debug("Found Dataset: {}", dataset.getDatasetId().getDataset());
                    }
                } while (response.hasNextPage());
            }

            logger.info("Found {} schemas!", schemas.size());

            return ListSchemasResponse.newBuilder().setCatalogName(listSchemasRequest.getCatalogName()).addAllSchemas(schemas).build();
        }
        catch
        (Exception e) {
            logger.error("Error: ", e);
        }
        return null;
    }

    @Override
    public ListTablesResponse doListTables(BlockAllocator blockAllocator, ListTablesRequest listTablesRequest)
    {
        try {
            logger.info("doListTables called with request {}:{}", listTablesRequest.getCatalogName(),
                    listTablesRequest.getSchemaName());
            //Get the project name, dataset name, and dataset id. Google BigQuery is case sensitive.
            String nextToken = null;
            BigQuery bigQuery = BigQueryUtils.getBigQueryClient(configOptions);
            final String datasetName = fixCaseForDatasetName(projectName, listTablesRequest.getSchemaName(), bigQuery);
            final DatasetId datasetId = DatasetId.of(projectName, datasetName);
            List<TableName> tables = new ArrayList<>();
            if (listTablesRequest.getPageSize() == UNLIMITED_PAGE_SIZE_VALUE) {
                Page<Table> response = bigQuery.listTables(datasetId);
                for (Table table : response.iterateAll()) {
                    if (tables.size() > BigQueryConstants.MAX_RESULTS) {
                        throw new BigQueryExceptions.TooManyTablesException();
                    }
                    tables.add(TableName.newBuilder().setSchemaName(listTablesRequest.getSchemaName()).setTableName(table.getTableId().getTable()).build());
                }
            }
            else {
                Page<Table> response = bigQuery.listTables(datasetId,
                        BigQuery.TableListOption.pageToken(listTablesRequest.getNextToken()), BigQuery.TableListOption.pageSize(listTablesRequest.getPageSize()));
                for (Table table : response.getValues()) {
                    tables.add(TableName.newBuilder().setSchemaName(listTablesRequest.getSchemaName()).setTableName(table.getTableId().getTable()).build());
                }
                nextToken = response.getNextPageToken();
            }
            if (nextToken == null) {
                return ListTablesResponse.newBuilder().setCatalogName(listTablesRequest.getCatalogName()).addAllTables(tables).build();
            }
            return ListTablesResponse.newBuilder().setCatalogName(listTablesRequest.getCatalogName()).addAllTables(tables).setNextToken(nextToken).build();
        }
        catch
        (Exception e) {
            logger.error("Error:", e);
        }
        return null;
    }

    @Override
    public GetTableResponse doGetTable(BlockAllocator blockAllocator, GetTableRequest getTableRequest) throws java.io.IOException
    {
        logger.info("doGetTable called with request {}. Resolved projectName: {}", getTableRequest.getCatalogName(), projectName);
        final Schema tableSchema = getSchema(getTableRequest.getTableName().getSchemaName(),
                getTableRequest.getTableName().getTableName());
        // TODO: Do we actually need to lowercase here?
        return GetTableResponse.newBuilder().setCatalogName(projectName.toLowerCase()).setTableName(getTableRequest.getTableName()).setSchema(ProtobufMessageConverter.toProtoSchemaBytes(tableSchema)).build();
    }

    /**
     *
     * Currently not supporting Partitions since Bigquery having quota limits with triggering concurrent queries and having bit complexity to extract and use the partitions
     * in the query instead we are using limit and offset for non constraints query with basic concurrency limit
     */
    @Override
    public void getPartitions(BlockAllocator allocator, BlockWriter blockWriter, GetTableLayoutRequest request, QueryStatusChecker queryStatusChecker)
            throws Exception
    {
        //NoOp since we don't support partitioning at this time.
    }

    /**
     * Making minimum(10) splits based on constraints. Since without constraints query may give lambda timeout if table has large data,
     * concurrencyLimit is configurable and it can be changed based on Google BigQuery Quota Limits.
     * @param allocator Tool for creating and managing Apache Arrow Blocks.
     * @param request Provides details of the catalog, database, table, and partition(s) being queried as well as
     * any filter predicate.
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public GetSplitsResponse doGetSplits(BlockAllocator allocator, GetSplitsRequest request) throws IOException, InterruptedException
    {
        int constraintsSize = ProtobufMessageConverter.fromProtoConstraints(allocator, request.getConstraints()).getSummary().size();
        if (constraintsSize > 0) {
            //Every split must have a unique location if we wish to spill to avoid failures
            SpillLocation spillLocation = makeSpillLocation(request.getQueryId());
            return GetSplitsResponse.newBuilder().setCatalogName(request.getCatalogName()).addSplits(Split.newBuilder().setSpillLocation(spillLocation).setEncryptionKey(makeEncryptionKey()).build()).build();
        }
        else {
            BigQuery bigQuery = BigQueryUtils.getBigQueryClient(configOptions);
            String dataSetName = fixCaseForDatasetName(projectName, request.getTableName().getSchemaName(), bigQuery);
            String tableName = fixCaseForTableName(projectName, dataSetName, request.getTableName().getTableName(), bigQuery);
            QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder("SELECT count(*) FROM `" + projectName + "." + dataSetName + "." + tableName + "` ").setUseLegacySql(false).build();
            // Create a job ID so that we can safely retry.
            JobId jobId = JobId.of(UUID.randomUUID().toString());
            Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build()).waitFor();
            TableResult result = queryJob.getQueryResults();

            double numberOfRows = result.iterateAll().iterator().next().get(0).getLongValue();
            logger.debug("numberOfRows: " + numberOfRows);
            int concurrencyLimit = Integer.parseInt(configOptions.get("concurrencyLimit"));
            logger.debug("concurrencyLimit: " + numberOfRows);
            long pageCount = (long) numberOfRows / concurrencyLimit;
            long totalPageCountLimit = (pageCount == 0) ? (long) numberOfRows : pageCount;
            double limit = (int) Math.ceil(numberOfRows / totalPageCountLimit);
            Set<Split> splits = new HashSet<>();
            long offSet = 0;

            for (int i = 1; i <= limit; i++) {
                if (i > 1) {
                    offSet = offSet + totalPageCountLimit;
                }
                // Every split must have a unique location if we wish to spill to avoid failures
                SpillLocation spillLocation = makeSpillLocation(request.getQueryId());
                // Create a new split (added to the splits set) that includes the domain and endpoint, and
                // shard information (to be used later by the Record Handler).
                Map<String, String> map = new HashMap<>();
                map.put(Long.toString(totalPageCountLimit), Long.toString(offSet));
                splits.add(Split.newBuilder().setSpillLocation(spillLocation).setEncryptionKey(makeEncryptionKey()).putAllProperties(map).build());
            }
            return GetSplitsResponse.newBuilder().setCatalogName(request.getCatalogName()).addAllSplits(splits).build();
        }
    }

    /**
     * Getting Bigquery table schema details
     * @param datasetName
     * @param tableName
     * @return
     */
    private Schema getSchema(String datasetName, String tableName) throws java.io.IOException
    {
        BigQuery bigQuery = BigQueryUtils.getBigQueryClient(configOptions);
        datasetName = fixCaseForDatasetName(projectName, datasetName, bigQuery);
        tableName = fixCaseForTableName(projectName, datasetName, tableName, bigQuery);
        TableId tableId = TableId.of(projectName, datasetName, tableName);
        Table response = bigQuery.getTable(tableId);
        TableDefinition tableDefinition = response.getDefinition();
        SchemaBuilder schemaBuilder = SchemaBuilder.newBuilder();
        List<String> timeStampColsList = new ArrayList<>();

        for (Field field : tableDefinition.getSchema().getFields()) {
            if (field.getType().getStandardType().toString().equals("TIMESTAMP")) {
                timeStampColsList.add(field.getName());
            }
            schemaBuilder.addField(field.getName(), translateToArrowType(field.getType()));
        }
        schemaBuilder.addMetadata("timeStampCols", timeStampColsList.toString());
        logger.debug("BigQuery table schema {}", schemaBuilder.toString());
        return schemaBuilder.build();
    }
}
