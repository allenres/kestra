package io.kestra.webserver.controllers.api;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.junit.annotations.LoadFlows;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.queues.QueueException;
import io.kestra.core.runners.TestRunnerUtils;
import io.kestra.core.tenant.TenantService;
import io.kestra.webserver.tenants.TenantValidationFilter;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.reactor.http.client.ReactorHttpClient;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.kestra.core.runners.LocalPath;
import io.micronaut.test.annotation.MockBean;

import java.util.concurrent.TimeoutException;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static io.micronaut.http.HttpRequest.GET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the execution progress bar: getFlowAverageDuration endpoint (including
 * ForEach-aware estimation) and the foreachEffectiveBatches helper.
 */
@Slf4j
@KestraTest(startRunner = true)
@Property(name = LocalPath.ALLOWED_PATHS_CONFIG, value = "/tmp")
class ExecutionProgressTest {

    static final String NS = "io.kestra.tests";

    @Inject
    @Client("/")
    ReactorHttpClient client;

    @Inject
    protected TestRunnerUtils runnerUtils;

    @MockBean(TenantService.class)
    public TenantService getTenantService() {
        return mock(TenantService.class);
    }

    @Inject
    private TenantService tenantService;

    @MockBean(TenantValidationFilter.class)
    public TenantValidationFilter getTenantValidationFilter() {
        return mock(TenantValidationFilter.class);
    }

    @BeforeEach
    void initMock() {
        when(tenantService.resolveTenant()).thenReturn(MAIN_TENANT);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Unit tests for the pure helper — no Micronaut context needed, static method
    // ──────────────────────────────────────────────────────────────────────────────

    @Nested
    class ForeachEffectiveBatchesTest {

        @Test
        void unlimitedConcurrency_alwaysOneBatch() {
            assertThat(ExecutionController.foreachEffectiveBatches(1, 0)).isEqualTo(1);
            assertThat(ExecutionController.foreachEffectiveBatches(10, 0)).isEqualTo(1);
            assertThat(ExecutionController.foreachEffectiveBatches(100, 0)).isEqualTo(1);
        }

        @Test
        void sequentialConcurrency_oneBatchPerItem() {
            assertThat(ExecutionController.foreachEffectiveBatches(1, 1)).isEqualTo(1);
            assertThat(ExecutionController.foreachEffectiveBatches(3, 1)).isEqualTo(3);
            assertThat(ExecutionController.foreachEffectiveBatches(10, 1)).isEqualTo(10);
        }

        @Test
        void limitedConcurrency_ceilingDivision() {
            // C=2: ceil(3/2)=2, ceil(4/2)=2, ceil(5/2)=3
            assertThat(ExecutionController.foreachEffectiveBatches(3, 2)).isEqualTo(2);
            assertThat(ExecutionController.foreachEffectiveBatches(4, 2)).isEqualTo(2);
            assertThat(ExecutionController.foreachEffectiveBatches(5, 2)).isEqualTo(3);

            // C=3: ceil(6/3)=2, ceil(7/3)=3
            assertThat(ExecutionController.foreachEffectiveBatches(6, 3)).isEqualTo(2);
            assertThat(ExecutionController.foreachEffectiveBatches(7, 3)).isEqualTo(3);
        }

        @Test
        void nEqualsToConcurrencyLimit_oneBatch() {
            // If N <= C all items run in the same batch
            assertThat(ExecutionController.foreachEffectiveBatches(2, 2)).isEqualTo(1);
            assertThat(ExecutionController.foreachEffectiveBatches(1, 5)).isEqualTo(1);
        }

        @Test
        void singleItem_alwaysOneBatch() {
            assertThat(ExecutionController.foreachEffectiveBatches(1, 0)).isEqualTo(1);
            assertThat(ExecutionController.foreachEffectiveBatches(1, 1)).isEqualTo(1);
            assertThat(ExecutionController.foreachEffectiveBatches(1, 10)).isEqualTo(1);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Integration tests for the HTTP endpoint
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    @LoadFlows({"flows/valids/foreach-non-concurrent.yaml"})
    void noHistory_returnsNullAvg() {
        var result = avgDuration("foreach-non-concurrent", null);

        assertThat(result.avgDurationMs()).isNull();
        assertThat(result.count()).isZero();
    }

    @Test
    @LoadFlows({"flows/valids/foreach-non-concurrent.yaml"})
    void withHistory_noExecutionId_returnsSimpleAverage() throws TimeoutException, QueueException {
        runnerUtils.runOne(MAIN_TENANT, NS, "foreach-non-concurrent");
        runnerUtils.runOne(MAIN_TENANT, NS, "foreach-non-concurrent");

        var result = avgDuration("foreach-non-concurrent", null);

        assertThat(result.avgDurationMs()).isNotNull().isPositive();
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    @LoadFlows({"flows/valids/foreach-non-concurrent.yaml"})
    void withHistory_executionId_returnsForEachAwareEstimate() throws TimeoutException, QueueException {
        // Historical baseline
        runnerUtils.runOne(MAIN_TENANT, NS, "foreach-non-concurrent");

        // Completed current run — all ForEach children are visible, N can be determined exactly
        Execution current = runnerUtils.runOne(MAIN_TENANT, NS, "foreach-non-concurrent");
        assertThat(current.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        var withId    = avgDuration("foreach-non-concurrent", current.getId());
        var withoutId = avgDuration("foreach-non-concurrent", null);

        // ForEach-aware path must return a positive estimate
        assertThat(withId.avgDurationMs()).isNotNull().isPositive();
        // Both paths saw the same N=3 list, so estimates should be in the same ballpark
        assertThat(withId.avgDurationMs())
            .isGreaterThan(withoutId.avgDurationMs() / 5)
            .isLessThan(withoutId.avgDurationMs() * 5);
    }

    @Test
    @LoadFlows({"flows/valids/foreach-concurrent.yaml"})
    void forEachWithLimitedConcurrency_returnsAdjustedEstimate() throws TimeoutException, QueueException {
        // foreach-concurrent has C=2, N=3 → ceil(3/2)=2 effective batches
        runnerUtils.runOne(MAIN_TENANT, NS, "foreach-concurrent");

        Execution current = runnerUtils.runOne(MAIN_TENANT, NS, "foreach-concurrent");
        assertThat(current.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        var result = avgDuration("foreach-concurrent", current.getId());

        assertThat(result.avgDurationMs()).isNotNull().isPositive();
    }

    @Test
    @LoadFlows({"flows/valids/foreach-concurrent-no-limit.yaml"})
    void forEachWithUnlimitedConcurrency_allChildrenVisible_nIsAccurate() throws TimeoutException, QueueException {
        // C=0 means all N=3 children are dispatched in one batch; N is fully visible from taskRunList
        runnerUtils.runOne(MAIN_TENANT, NS, "foreach-concurrent-no-limit");

        Execution current = runnerUtils.runOne(MAIN_TENANT, NS, "foreach-concurrent-no-limit");
        assertThat(current.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        var result = avgDuration("foreach-concurrent-no-limit", current.getId());

        assertThat(result.avgDurationMs()).isNotNull().isPositive();
    }

    @Test
    @LoadFlows({"flows/valids/minimal.yaml"})
    void nonForEachFlow_executionIdProvided_fallsBackToSimpleAverage() throws TimeoutException, QueueException {
        Execution execution = runnerUtils.runOne(MAIN_TENANT, NS, "minimal");
        assertThat(execution.getState().getCurrent()).isEqualTo(State.Type.SUCCESS);

        var withId    = avgDuration("minimal", execution.getId());
        var withoutId = avgDuration("minimal", null);

        // No ForEach in the flow → both paths must return the same simple average
        assertThat(withId.avgDurationMs()).isEqualTo(withoutId.avgDurationMs());
        assertThat(withId.count()).isEqualTo(1);
    }

    @Test
    @LoadFlows({"flows/valids/foreach-non-concurrent.yaml"})
    void unknownExecutionId_fallsBackToSimpleAverage() throws TimeoutException, QueueException {
        runnerUtils.runOne(MAIN_TENANT, NS, "foreach-non-concurrent");

        var withBadId  = avgDuration("foreach-non-concurrent", "nonexistent-execution-id");
        var withoutId  = avgDuration("foreach-non-concurrent", null);

        // Non-existent executionId → graceful fallback to simple average
        assertThat(withBadId.avgDurationMs()).isEqualTo(withoutId.avgDurationMs());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private ExecutionController.FlowAverageDuration avgDuration(String flowId, String executionId) {
        String url = "/api/v1/main/executions/namespaces/" + NS + "/flows/" + flowId + "/average-duration";
        if (executionId != null) {
            url += "?executionId=" + executionId;
        }
        return client.toBlocking().retrieve(GET(url), ExecutionController.FlowAverageDuration.class);
    }
}
