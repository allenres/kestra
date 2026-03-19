import {describe, it, expect, vi, beforeEach} from "vitest";
import {setActivePinia, createPinia} from "pinia";

// ── Mandatory mocks for Pinia store dependencies ─────────────────────────────

vi.mock("nprogress", () => ({
    start: vi.fn(),
    done: vi.fn(),
    set: vi.fn(),
    inc: vi.fn(),
}));

vi.mock("vue-router", () => ({
    useRoute: () => ({query: {}}),
    useRouter: () => ({
        beforeEach: vi.fn(),
        afterEach: vi.fn(),
    }),
}));

const axiosGet = vi.fn();
const axiosPost = vi.fn();

vi.mock("../../../src/utils/axios", () => ({
    useAxios: () => ({
        get: axiosGet,
        post: axiosPost,
        delete: vi.fn(),
    }),
}));

vi.mock("override/utils/route", () => ({
    apiUrl: () => "/api/v1/main",
}));

// ─────────────────────────────────────────────────────────────────────────────

describe("useExecutionsStore — loadFlowAvgDuration", () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        vi.clearAllMocks();
    });

    it("calls the correct URL without executionId", async () => {
        axiosGet.mockResolvedValue({data: {avgDurationMs: 5000, count: 3}});

        const {useExecutionsStore} = await import("../../../src/stores/executions");
        const store = useExecutionsStore();

        const result = await store.loadFlowAvgDuration("my.namespace", "my-flow");

        expect(axiosGet).toHaveBeenCalledWith(
            "/api/v1/main/executions/namespaces/my.namespace/flows/my-flow/average-duration",
            {params: undefined}
        );
        expect(result.avgDurationMs).toBe(5000);
        expect(result.count).toBe(3);
    });

    it("includes executionId as query param when provided", async () => {
        axiosGet.mockResolvedValue({data: {avgDurationMs: 12000, count: 2}});

        const {useExecutionsStore} = await import("../../../src/stores/executions");
        const store = useExecutionsStore();

        const result = await store.loadFlowAvgDuration("my.namespace", "my-flow", "exec-abc-123");

        expect(axiosGet).toHaveBeenCalledWith(
            "/api/v1/main/executions/namespaces/my.namespace/flows/my-flow/average-duration",
            {params: {executionId: "exec-abc-123"}}
        );
        expect(result.avgDurationMs).toBe(12000);
    });

    it("omits params entirely when executionId is undefined", async () => {
        axiosGet.mockResolvedValue({data: {avgDurationMs: null, count: 0}});

        const {useExecutionsStore} = await import("../../../src/stores/executions");
        const store = useExecutionsStore();

        await store.loadFlowAvgDuration("ns", "flow-id", undefined);

        expect(axiosGet).toHaveBeenCalledWith(
            "/api/v1/main/executions/namespaces/ns/flows/flow-id/average-duration",
            {params: undefined}
        );
    });

    it("returns null avgDurationMs when backend has no history", async () => {
        axiosGet.mockResolvedValue({data: {avgDurationMs: null, count: 0}});

        const {useExecutionsStore} = await import("../../../src/stores/executions");
        const store = useExecutionsStore();

        const result = await store.loadFlowAvgDuration("ns", "flow-id");

        expect(result.avgDurationMs).toBeNull();
        expect(result.count).toBe(0);
    });
});
