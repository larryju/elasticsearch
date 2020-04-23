/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.dataframe.process;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.collect.Set;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.license.License;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsDest;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsSource;
import org.elasticsearch.xpack.core.ml.dataframe.analyses.Classification;
import org.elasticsearch.xpack.core.ml.dataframe.analyses.DataFrameAnalysis;
import org.elasticsearch.xpack.core.ml.dataframe.analyses.Regression;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelConfig;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelDefinition;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelDefinitionTests;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.PredictionFieldType;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TargetType;
import org.elasticsearch.xpack.core.security.user.XPackUser;
import org.elasticsearch.xpack.ml.dataframe.process.results.AnalyticsResult;
import org.elasticsearch.xpack.ml.dataframe.process.results.RowResults;
import org.elasticsearch.xpack.ml.dataframe.stats.StatsHolder;
import org.elasticsearch.xpack.ml.dataframe.stats.StatsPersister;
import org.elasticsearch.xpack.ml.extractor.DocValueField;
import org.elasticsearch.xpack.ml.extractor.ExtractedField;
import org.elasticsearch.xpack.ml.extractor.ExtractedFields;
import org.elasticsearch.xpack.ml.extractor.MultiField;
import org.elasticsearch.xpack.ml.inference.persistence.TrainedModelProvider;
import org.elasticsearch.xpack.ml.notifications.DataFrameAnalyticsAuditor;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AnalyticsResultProcessorTests extends ESTestCase {

    private static final String JOB_ID = "analytics-result-processor-tests";
    private static final String JOB_DESCRIPTION = "This describes the job of these tests";

    private AnalyticsProcess<AnalyticsResult> process;
    private DataFrameRowsJoiner dataFrameRowsJoiner;
    private StatsHolder statsHolder = new StatsHolder();
    private TrainedModelProvider trainedModelProvider;
    private DataFrameAnalyticsAuditor auditor;
    private StatsPersister statsPersister;
    private DataFrameAnalyticsConfig analyticsConfig;

    @Before
    @SuppressWarnings("unchecked")
    public void setUpMocks() {
        process = mock(AnalyticsProcess.class);
        dataFrameRowsJoiner = mock(DataFrameRowsJoiner.class);
        trainedModelProvider = mock(TrainedModelProvider.class);
        auditor = mock(DataFrameAnalyticsAuditor.class);
        statsPersister = mock(StatsPersister.class);
        analyticsConfig = new DataFrameAnalyticsConfig.Builder()
            .setId(JOB_ID)
            .setDescription(JOB_DESCRIPTION)
            .setSource(new DataFrameAnalyticsSource(new String[] {"my_source"}, null, null))
            .setDest(new DataFrameAnalyticsDest("my_dest", null))
            .setAnalysis(new Regression("foo"))
            .build();
    }

    public void testProcess_GivenNoResults() {
        givenDataFrameRows(0);
        givenProcessResults(Collections.emptyList());
        AnalyticsResultProcessor resultProcessor = createResultProcessor();

        resultProcessor.process(process);
        resultProcessor.awaitForCompletion();

        verify(dataFrameRowsJoiner).close();
        verifyNoMoreInteractions(dataFrameRowsJoiner);
    }

    public void testProcess_GivenEmptyResults() {
        givenDataFrameRows(2);
        givenProcessResults(Arrays.asList(
            new AnalyticsResult(null, null,50, null, null, null, null, null),
            new AnalyticsResult(null, null, 100, null, null, null, null, null)));
        AnalyticsResultProcessor resultProcessor = createResultProcessor();

        resultProcessor.process(process);
        resultProcessor.awaitForCompletion();

        verify(dataFrameRowsJoiner).close();
        Mockito.verifyNoMoreInteractions(dataFrameRowsJoiner);
        assertThat(statsHolder.getProgressTracker().writingResultsPercent.get(), equalTo(100));
    }

    public void testProcess_GivenRowResults() {
        givenDataFrameRows(2);
        RowResults rowResults1 = mock(RowResults.class);
        RowResults rowResults2 = mock(RowResults.class);
        givenProcessResults(Arrays.asList(new AnalyticsResult(rowResults1, null,50, null, null, null, null, null),
            new AnalyticsResult(rowResults2, null, 100, null, null, null, null, null)));
        AnalyticsResultProcessor resultProcessor = createResultProcessor();

        resultProcessor.process(process);
        resultProcessor.awaitForCompletion();

        InOrder inOrder = Mockito.inOrder(dataFrameRowsJoiner);
        inOrder.verify(dataFrameRowsJoiner).processRowResults(rowResults1);
        inOrder.verify(dataFrameRowsJoiner).processRowResults(rowResults2);

        assertThat(statsHolder.getProgressTracker().writingResultsPercent.get(), equalTo(100));
    }

    public void testProcess_GivenDataFrameRowsJoinerFails() {
        givenDataFrameRows(2);
        RowResults rowResults1 = mock(RowResults.class);
        RowResults rowResults2 = mock(RowResults.class);
        givenProcessResults(Arrays.asList(new AnalyticsResult(rowResults1, null,50, null, null, null, null, null),
            new AnalyticsResult(rowResults2, null, 100, null, null, null, null, null)));

        doThrow(new RuntimeException("some failure")).when(dataFrameRowsJoiner).processRowResults(any(RowResults.class));

        AnalyticsResultProcessor resultProcessor = createResultProcessor();

        resultProcessor.process(process);
        resultProcessor.awaitForCompletion();

        assertThat(resultProcessor.getFailure(), equalTo("error processing results; some failure"));

        ArgumentCaptor<String> auditCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditor).error(eq(JOB_ID), auditCaptor.capture());
        assertThat(auditCaptor.getValue(), containsString("Error processing results; some failure"));

        assertThat(statsHolder.getProgressTracker().writingResultsPercent.get(), equalTo(0));
    }

    @SuppressWarnings("unchecked")
    public void testProcess_GivenInferenceModelIsStoredSuccessfully() {
        givenDataFrameRows(0);

        doAnswer(invocationOnMock -> {
            ActionListener<Boolean> storeListener = (ActionListener<Boolean>) invocationOnMock.getArguments()[1];
            storeListener.onResponse(true);
            return null;
        }).when(trainedModelProvider).storeTrainedModel(any(TrainedModelConfig.class), any(ActionListener.class));

        List<ExtractedField> extractedFieldList = new ArrayList<>(3);
        extractedFieldList.add(new DocValueField("foo", Collections.emptySet()));
        extractedFieldList.add(new MultiField("bar", new DocValueField("bar.keyword", Collections.emptySet())));
        extractedFieldList.add(new DocValueField("baz", Collections.emptySet()));
        TargetType targetType = analyticsConfig.getAnalysis() instanceof Regression ? TargetType.REGRESSION : TargetType.CLASSIFICATION;
        TrainedModelDefinition.Builder inferenceModel = TrainedModelDefinitionTests.createRandomBuilder(targetType);
        givenProcessResults(Arrays.asList(new AnalyticsResult(null, null, null, inferenceModel, null, null, null, null)));
        AnalyticsResultProcessor resultProcessor = createResultProcessor(extractedFieldList);

        resultProcessor.process(process);
        resultProcessor.awaitForCompletion();

        ArgumentCaptor<TrainedModelConfig> storedModelCaptor = ArgumentCaptor.forClass(TrainedModelConfig.class);
        verify(trainedModelProvider).storeTrainedModel(storedModelCaptor.capture(), any(ActionListener.class));

        TrainedModelConfig storedModel = storedModelCaptor.getValue();
        assertThat(storedModel.getLicenseLevel(), equalTo(License.OperationMode.PLATINUM));
        assertThat(storedModel.getModelId(), containsString(JOB_ID));
        assertThat(storedModel.getVersion(), equalTo(Version.CURRENT));
        assertThat(storedModel.getCreatedBy(), equalTo(XPackUser.NAME));
        assertThat(storedModel.getTags(), contains(JOB_ID));
        assertThat(storedModel.getDescription(), equalTo(JOB_DESCRIPTION));
        assertThat(storedModel.getModelDefinition(), equalTo(inferenceModel.build()));
        assertThat(storedModel.getDefaultFieldMap(), equalTo(Collections.singletonMap("bar", "bar.keyword")));
        assertThat(storedModel.getInput().getFieldNames(), equalTo(Arrays.asList("bar.keyword", "baz")));
        assertThat(storedModel.getEstimatedHeapMemory(), equalTo(inferenceModel.build().ramBytesUsed()));
        assertThat(storedModel.getEstimatedOperations(), equalTo(inferenceModel.build().getTrainedModel().estimatedNumOperations()));
        if (targetType.equals(TargetType.CLASSIFICATION)) {
            assertThat(storedModel.getInferenceConfig().getName(), equalTo("classification"));
        } else {
            assertThat(storedModel.getInferenceConfig().getName(), equalTo("regression"));
        }
        Map<String, Object> metadata = storedModel.getMetadata();
        assertThat(metadata.size(), equalTo(1));
        assertThat(metadata, hasKey("analytics_config"));
        Map<String, Object> analyticsConfigAsMap = XContentHelper.convertToMap(JsonXContent.jsonXContent, analyticsConfig.toString(),
            true);
        assertThat(analyticsConfigAsMap, equalTo(metadata.get("analytics_config")));

        ArgumentCaptor<String> auditCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditor).info(eq(JOB_ID), auditCaptor.capture());
        assertThat(auditCaptor.getValue(), containsString("Stored trained model with id [" + JOB_ID));
        Mockito.verifyNoMoreInteractions(auditor);
    }

    public void testGetPredictionFieldType() {
        List<ExtractedField> extractedFieldList = Arrays.asList(
            new DocValueField("foo", Collections.emptySet()),
            new DocValueField("bar", Set.of("keyword")),
            new DocValueField("baz", Set.of("long")),
            new DocValueField("bingo", Set.of("boolean")));
        AnalyticsResultProcessor resultProcessor = createResultProcessor(extractedFieldList);
        assertThat(resultProcessor.getPredictionFieldType(new Classification("foo")), equalTo(PredictionFieldType.STRING));
        assertThat(resultProcessor.getPredictionFieldType(new Classification("bar")), equalTo(PredictionFieldType.STRING));
        assertThat(resultProcessor.getPredictionFieldType(new Classification("baz")), equalTo(PredictionFieldType.NUMBER));
        assertThat(resultProcessor.getPredictionFieldType(new Classification("bingo")), equalTo(PredictionFieldType.BOOLEAN));
    }

    @SuppressWarnings("unchecked")
    public void testProcess_GivenInferenceModelFailedToStore() {
        givenDataFrameRows(0);

        doAnswer(invocationOnMock -> {
            ActionListener<Boolean> storeListener = (ActionListener<Boolean>) invocationOnMock.getArguments()[1];
            storeListener.onFailure(new RuntimeException("some failure"));
            return null;
        }).when(trainedModelProvider).storeTrainedModel(any(TrainedModelConfig.class), any(ActionListener.class));

        TargetType targetType = analyticsConfig.getAnalysis() instanceof Regression ? TargetType.REGRESSION : TargetType.CLASSIFICATION;
        TrainedModelDefinition.Builder inferenceModel = TrainedModelDefinitionTests.createRandomBuilder(targetType);
        givenProcessResults(Arrays.asList(new AnalyticsResult(null, null, null, inferenceModel, null, null, null, null)));
        AnalyticsResultProcessor resultProcessor = createResultProcessor();

        resultProcessor.process(process);
        resultProcessor.awaitForCompletion();

        // This test verifies the processor knows how to handle a failure on storing the model and completes normally
        ArgumentCaptor<String> auditCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditor).error(eq(JOB_ID), auditCaptor.capture());
        assertThat(auditCaptor.getValue(), containsString("Error processing results; error storing trained model with id [" + JOB_ID));
        Mockito.verifyNoMoreInteractions(auditor);

        assertThat(resultProcessor.getFailure(), startsWith("error processing results; error storing trained model with id [" + JOB_ID));
        assertThat(statsHolder.getProgressTracker().writingResultsPercent.get(), equalTo(0));
    }

    private void givenProcessResults(List<AnalyticsResult> results) {
        when(process.readAnalyticsResults()).thenReturn(results.iterator());
    }

    private void givenDataFrameRows(int rows) {
        AnalyticsProcessConfig config = new AnalyticsProcessConfig(
            "job_id", rows, 1, ByteSizeValue.ZERO, 1, "ml", Collections.emptySet(), mock(DataFrameAnalysis.class),
            mock(ExtractedFields.class));
        when(process.getConfig()).thenReturn(config);
    }

    private AnalyticsResultProcessor createResultProcessor() {
        return createResultProcessor(Collections.emptyList());
    }

    private AnalyticsResultProcessor createResultProcessor(List<ExtractedField> fieldNames) {
        return new AnalyticsResultProcessor(analyticsConfig,
            dataFrameRowsJoiner,
            statsHolder,
            trainedModelProvider,
            auditor,
            statsPersister,
            fieldNames);
    }
}