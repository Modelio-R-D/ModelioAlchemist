package com.docaposte.modelioalchemist.langchain.impl;

import com.docaposte.modelioalchemist.i18n.Messages;

/**
 * Callback used by the pipeline runners to report progress while they execute, so that
 * the UI (e.g. a {@code ProgressMonitorDialog}) can show the user what is currently happening
 * instead of appearing frozen until the whole (potentially long-running) pipeline completes.
 */
@FunctionalInterface
public interface PipelineProgressListener {

    /** No-op listener used when no progress reporting is needed. */
    PipelineProgressListener NONE = stage -> { };

    /**
     * Called whenever the pipeline moves on to a new stage.
     *
     * @param stage a short, human readable, already localized description of the stage that just started
     */
    void onStage(String stage);

    /**
     * Whether the user requested cancellation of the pipeline (e.g. pressed the Cancel button on the
     * progress dialog). Checked cooperatively between stages by {@link #onStep}.
     *
     * @return {@code true} if the pipeline should stop as soon as possible
     */
    default boolean isCancelled() {
        return false;
    }

    /**
     * Reports progress for a step within a pipeline that has a known, fixed number of steps, so the
     * user can see overall completion (e.g. "Step 3 of 8: Classifying requirements...") rather than just
     * an isolated stage description. Also acts as a cooperative cancellation checkpoint: if the user
     * cancelled, this throws {@link PipelineCancelledException} to unwind the pipeline immediately.
     *
     * @param step        the 1-based index of the current step
     * @param totalSteps  the total number of steps in the pipeline
     * @param stageKey    the resource bundle key of the localized stage description
     */
    default void onStep(int step, int totalSteps, String stageKey) {
        if (isCancelled()) {
            throw new PipelineCancelledException();
        }
        onStage(Messages.getString("progress.step",
            String.valueOf(step), String.valueOf(totalSteps), Messages.getString(stageKey)));
    }

    /**
     * Reports progress for multiple stages executing in parallel. This is used when the pipeline
     * spawns multiple concurrent tasks (e.g. analyzing multiple requirement domains at once).
     * Shows the user that multiple activities are happening simultaneously to make it clear why
     * the overall progress jumps multiple steps at once.
     *
     * @param step             the 1-based index of the first step in this parallel batch
     * @param totalSteps       the total number of steps in the pipeline
     * @param parallelStages   array of resource bundle keys for the stages running in parallel
     *                         (e.g. ["progress.pipeline.analyze.technique", "progress.pipeline.analyze.rssi", ...])
     */
    default void onParallelSteps(int step, int totalSteps, String... parallelStages) {
        if (isCancelled()) {
            throw new PipelineCancelledException();
        }
        if (parallelStages == null || parallelStages.length == 0) {
            return;
        }

        // Format: "Steps 4-8 of 15 (5 in parallel): Analyzing technique, rssi, fonctionnel, rse, ecoconception..."
        StringBuilder stageList = new StringBuilder();
        for (int i = 0; i < parallelStages.length; i++) {
            if (i > 0) {
                stageList.append(", ");
            }
            String stageMessage = Messages.getString(parallelStages[i]);
            // Extract just the key name from the full message (e.g. "technique" from "progress.pipeline.analyze.technique")
            String key = parallelStages[i].substring(parallelStages[i].lastIndexOf('.') + 1);
            stageList.append(key);
        }

        int endStep = step + parallelStages.length - 1;
        String message;
        if (step == endStep) {
            // Only one stage
            message = Messages.getString("progress.step",
                String.valueOf(step), String.valueOf(totalSteps), stageList.toString());
        } else {
            // Multiple stages in parallel
            message = Messages.getString("progress.parallelSteps",
                String.valueOf(step), String.valueOf(endStep), String.valueOf(totalSteps),
                String.valueOf(parallelStages.length), stageList.toString());
        }

        onStage(message);
    }
}
