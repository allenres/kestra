<template>
    <div class="execution-progress">
        <div class="progress-header">
            <span class="progress-title">{{ title ?? $t('execution progress') }}</span>
            <span class="progress-meta">
                <template v-if="avgDurationMs && isRunning">
                    &nbsp;~{{ avgDisplay }}
                </template>
            </span>
        </div>
        <el-progress
            :percentage="progressPercent"
            :striped="isRunning"
            :stripedFlow="isRunning"
            :stroke-width="18"
            :status="progressStatus"
            :showText="false"
        />
        <div v-if="isRunning && avgDurationMs && remainingMs > 0" class="progress-footer small mt-1">
            {{ $t('estimated remaining', {duration: remainingDisplay}) }}
        </div>
        <div v-else-if="isRunning && !avgDurationMs" class="progress-footer small mt-1">
            {{ $t('no duration baseline') }}
        </div>
    </div>
</template>

<script setup lang="ts">
    import {ref, computed, onMounted, onBeforeUnmount} from "vue";
    import {State} from "@kestra-io/ui-libs";
    import Utils from "../../utils/utils";
    import {useExecutionsStore} from "../../stores/executions";

    const props = defineProps<{
        execution: {
            id: string;
            namespace: string;
            flowId: string;
            state: {
                current: string;
                startDate: string;
                endDate?: string;
            };
        };
        title?: string;
    }>();

    const executionsStore = useExecutionsStore();
    const avgDurationMs = ref<number | null>(null);
    const now = ref(Date.now());
    let timerInterval: ReturnType<typeof setInterval> | undefined;

    const isRunning = computed(() => State.isRunning(props.execution.state.current));

    const startMs = computed(() => new Date(props.execution.state.startDate).getTime());

    const endMs = computed(() => {
        if (props.execution.state.endDate) {
            return new Date(props.execution.state.endDate).getTime();
        }
        return null;
    });

    const elapsedMs = computed(() => {
        const stop = endMs.value ?? now.value;
        return Math.max(0, stop - startMs.value);
    });

    const remainingMs = computed(() => {
        if (!avgDurationMs.value) return 0;
        return Math.max(0, avgDurationMs.value - elapsedMs.value);
    });

    const progressPercent = computed(() => {
        if (!isRunning.value) return 100;
        if (!avgDurationMs.value) return 0;
        return Math.min(99, (elapsedMs.value / avgDurationMs.value) * 100);
    });

    const progressStatus = computed(() => {
        const state = props.execution.state.current;
        if (isRunning.value) return undefined;
        if (state === "SUCCESS" || state === "WARNING") return "success";
        if (["FAILED", "KILLED", "CANCELLED"].includes(state)) return "exception";
        return undefined;
    });

    const avgDisplay = computed(() => avgDurationMs.value ? Utils.humanDuration(avgDurationMs.value / 1000) : "");
    const remainingDisplay = computed(() => Utils.humanDuration(remainingMs.value / 1000));

    onMounted(async () => {
        try {
            const result = await executionsStore.loadFlowAvgDuration(
                props.execution.namespace,
                props.execution.flowId,
                props.execution.id
            );
            avgDurationMs.value = result.avgDurationMs;
        } catch {
            // silently ignore — component still shows elapsed time without a baseline
        }

        timerInterval = setInterval(() => {
            now.value = Date.now();
            if (!isRunning.value) {
                clearInterval(timerInterval);
                timerInterval = undefined;
            }
        }, 500);
    });

    onBeforeUnmount(() => {
        clearInterval(timerInterval);
    });
</script>

<style scoped lang="scss">
    .execution-progress {
        .progress-header {
            display: flex;
            justify-content: space-between;
            align-items: baseline;
            margin-bottom: 8px;
            font-size: 0.9rem;
        }

        .progress-footer {
            font-size: 0.8rem;
        }
    }

    :deep(.el-progress) {
        .el-progress-bar,
        .el-progress-bar__outer,
        .el-progress-bar__inner {
            border-radius: var(--bs-border-radius);
        }
    }
</style>
