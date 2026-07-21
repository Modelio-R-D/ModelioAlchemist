package com.docaposte.modelioalchemist.langchain.impl;

/**
 * Thrown when the user cancels a running pipeline (e.g. via the Cancel button of the
 * {@code ProgressMonitorDialog}). Checked cooperatively between pipeline stages via
 * {@link PipelineProgressListener#isCancelled()}.
 */
public class PipelineCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PipelineCancelledException() {
        super("Pipeline execution was cancelled by the user");
    }
}
