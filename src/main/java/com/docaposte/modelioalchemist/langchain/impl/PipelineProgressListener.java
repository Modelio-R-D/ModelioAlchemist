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
}
