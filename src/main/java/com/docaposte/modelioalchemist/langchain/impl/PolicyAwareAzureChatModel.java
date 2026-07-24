package com.docaposte.modelioalchemist.langchain.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinitionFunction;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolDefinition;
import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestToolMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.FunctionCall;
import com.azure.core.http.rest.RequestOptions;
import com.azure.core.util.BinaryData;

final class PolicyAwareAzureChatModel implements ChatModel {

    static final class ToolExecutionTrace {
        final String phaseName;
        int chatRequests;
        int toolSpecificationsOffered;
        int modelRequestedToolCalls;
        int toolResultMessagesSent;
        boolean sawAnyToolCall;
        final Set<String> offeredToolNames = new LinkedHashSet<>();
        final Set<String> requestedToolNames = new LinkedHashSet<>();
        final Set<String> toolResultIds = new LinkedHashSet<>();

        ToolExecutionTrace(String phaseName) {
            this.phaseName = phaseName;
        }

        String toSummary() {
            return "MCP_TRACE phase=" + phaseName
                    + " chatRequests=" + chatRequests
                    + " toolSpecs=" + toolSpecificationsOffered
                    + " requestedToolCalls=" + modelRequestedToolCalls
                    + " toolResults=" + toolResultMessagesSent
                    + " requestedTools=" + requestedToolNames
                    + " offeredTools=" + offeredToolNames;
        }
    }

    private static final ThreadLocal<ToolExecutionTrace> ACTIVE_TRACE = new ThreadLocal<>();

    private final OpenAIAsyncClient client;
    private final AzureEndpointResolver.AzureEndpointInfo info;
    private final double temperature;

    PolicyAwareAzureChatModel(OpenAIAsyncClient client, AzureEndpointResolver.AzureEndpointInfo info, double temperature) {
        this.client = client; this.info = info; this.temperature = temperature;
    }

    @Override
    public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
        return doChat(ChatRequest.builder().messages(messages).build());
    }

    public ChatResponse doChat(ChatRequest request) {
        try {
            ToolExecutionTrace trace = ACTIVE_TRACE.get();
            if (trace != null) {
                trace.chatRequests++;
            }
            List<ChatRequestMessage> azureMessages = toAzureMessages(request.messages());
            ChatCompletionsOptions opts = new ChatCompletionsOptions(azureMessages);
            Double temp = request.temperature(); if (temp == null) temp = temperature; if (temp != null) opts.setTemperature(temp);
            List<ToolSpecification> specs = request.toolSpecifications();
            if (specs != null && !specs.isEmpty()) {
                if (trace != null) {
                    trace.toolSpecificationsOffered = specs.size();
                    for (ToolSpecification spec : specs) {
                        if (spec != null && spec.name() != null) {
                            trace.offeredToolNames.add(spec.name());
                        }
                    }
                }
                List<ChatCompletionsToolDefinition> toolDefs = new ArrayList<>();
                for (ToolSpecification spec : specs) {
                    try {
                        ChatCompletionsFunctionToolDefinitionFunction fn = new ChatCompletionsFunctionToolDefinitionFunction(spec.name());
                        if (spec.description() != null) fn.setDescription(spec.description());
                        String jsonSchema = ToolSchemaBuilder.build(spec);
                        if (jsonSchema != null) { try { fn.setParameters(BinaryData.fromString(jsonSchema)); } catch (Throwable pe) { /* ignore */ } }
                        toolDefs.add(new ChatCompletionsFunctionToolDefinition(fn));
                    } catch (Throwable te) { /* ignore */ }
                }
                if (!toolDefs.isEmpty()) opts.setTools(toolDefs);
            }
            RequestOptions reqOpts = new RequestOptions();
            reqOpts.setHeader("timeout", "300000");
            System.out.println("[AzureChatModel] Calling Azure OpenAI endpoint=" + info.endpoint + " deployment=" + info.deployment);
            com.azure.ai.openai.models.ChatCompletions completions = client.getChatCompletionsWithResponse(info.deployment, opts, reqOpts).block(Duration.ofSeconds(300)).getValue();
            final String[] assistantTextHolder = new String[]{""}; List<ToolExecutionRequest> toolCalls = new ArrayList<>();
            if (completions != null && completions.getChoices() != null && !completions.getChoices().isEmpty()) {
                try {
                    Object msg = completions.getChoices().get(0).getMessage();
                    try { Object c = msg.getClass().getMethod("getContent").invoke(msg); if (c instanceof String) assistantTextHolder[0] = (String) c; else if (c != null) assistantTextHolder[0] = c.toString(); } catch (Throwable ignore) {}
                    try {
                        Object tcObj = msg.getClass().getMethod("getToolCalls").invoke(msg);
                        if (tcObj instanceof List) for (Object tc : (List<?>) tcObj) parseToolCall(tc, toolCalls);
                    } catch (NoSuchMethodException nsme) { /* no tool calls */ } catch (Throwable t) { /* ignore */ }
                } catch (Throwable outer) { /* ignore */ }
            }
            final String assistantText = assistantTextHolder[0];
            if (trace != null && !toolCalls.isEmpty()) {
                trace.sawAnyToolCall = true;
                trace.modelRequestedToolCalls += toolCalls.size();
                for (ToolExecutionRequest toolCall : toolCalls) {
                    if (toolCall != null && toolCall.name() != null) {
                        trace.requestedToolNames.add(toolCall.name());
                    }
                }
            }
            AiMessage ai = toolCalls.isEmpty() ? AiMessage.from(assistantText) : AiMessage.from(assistantText, toolCalls);
            return ChatResponse.builder().aiMessage(ai).build();
        } catch (Throwable t) {
            System.err.println("[AzureChatModel] Failed calling endpoint=" + info.endpoint + " deployment=" + info.deployment + " cause=" + t);
            throw new RuntimeException("Azure chat model failure", t);
        }
    }

    private List<ChatRequestMessage> toAzureMessages(List<dev.langchain4j.data.message.ChatMessage> messages) {
        List<ChatRequestMessage> azureMessages = new ArrayList<>();
        Set<String> emittedToolCallIds = new HashSet<>();
        for (dev.langchain4j.data.message.ChatMessage m : messages) {
            if (m instanceof SystemMessage) {
                azureMessages.add(new ChatRequestSystemMessage(((SystemMessage) m).text()));
            } else if (m instanceof dev.langchain4j.data.message.UserMessage) {
                azureMessages.add(new ChatRequestUserMessage(((dev.langchain4j.data.message.UserMessage) m).singleText()));
            } else if (m instanceof AiMessage) {
                AiMessage ai = (AiMessage) m;
                List<ToolExecutionRequest> trs = ai.toolExecutionRequests();
                if (trs != null && !trs.isEmpty()) azureMessages.add(buildAssistantWithToolCalls(ai, trs, emittedToolCallIds));
                else azureMessages.add(new ChatRequestAssistantMessage(ai.text()));
            } else if (m instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage tr = (ToolExecutionResultMessage) m;
                String toolCallId = tr.id();
                if (toolCallId == null || !emittedToolCallIds.contains(toolCallId)) { continue; }
                String content = tr.text(); if (content == null) content = "";
                ToolExecutionTrace trace = ACTIVE_TRACE.get();
                if (trace != null) {
                    trace.toolResultMessagesSent++;
                    trace.toolResultIds.add(toolCallId);
                }
                azureMessages.add(new ChatRequestToolMessage(content, toolCallId));
            }
        }
        return azureMessages;
    }

    static void startToolExecutionTrace(String phaseName) {
        ACTIVE_TRACE.set(new ToolExecutionTrace(phaseName));
    }

    static ToolExecutionTrace finishToolExecutionTrace() {
        ToolExecutionTrace trace = ACTIVE_TRACE.get();
        ACTIVE_TRACE.remove();
        return trace;
    }

    static ToolExecutionTrace currentToolExecutionTrace() {
        return ACTIVE_TRACE.get();
    }

    private ChatRequestAssistantMessage buildAssistantWithToolCalls(AiMessage ai, List<ToolExecutionRequest> trs, Set<String> emittedToolCallIds) {
        String content = ai.text(); if (content == null) content = "";
        ChatRequestAssistantMessage assistant = new ChatRequestAssistantMessage(content);
        List<com.azure.ai.openai.models.ChatCompletionsToolCall> toolCalls = new ArrayList<>();
        int idx = 0;
        for (ToolExecutionRequest ter : trs) {
            try {
                String id = ter.id(); if (id == null || id.isEmpty()) id = "call_" + (idx++);
                ChatCompletionsFunctionToolCall call = new ChatCompletionsFunctionToolCall(id, new FunctionCall(ter.name(), ter.arguments()));
                emittedToolCallIds.add(id);
                toolCalls.add(call);
            } catch (Throwable inner) { /* ignore */ }
        }
        try {
            try { assistant.getClass().getMethod("setToolCalls", List.class).invoke(assistant, toolCalls); }
            catch (NoSuchMethodException nsme) {
                try { java.lang.reflect.Field f = assistant.getClass().getDeclaredField("toolCalls"); f.setAccessible(true); f.set(assistant, toolCalls); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) { /* ignore */ }
        return assistant;
    }

    private void parseToolCall(Object tc, List<ToolExecutionRequest> out) {
        try {
            if (tc instanceof ChatCompletionsFunctionToolCall) {
                ChatCompletionsFunctionToolCall ftc = (ChatCompletionsFunctionToolCall) tc;
                String id = null; try { id = (String) ftc.getClass().getMethod("getId").invoke(ftc); } catch (Throwable ignore) {}
                FunctionCall fc = ftc.getFunction();
                if (fc != null) out.add(ToolExecutionRequest.builder().id(id).name(fc.getName()).arguments(fc.getArguments()).build());
            } else if (tc instanceof ChatCompletionsToolCall) {
                ChatCompletionsToolCall gtc = (ChatCompletionsToolCall) tc;
                String id = null; try { id = (String) gtc.getClass().getMethod("getId").invoke(gtc); } catch (Throwable ignore) {}
                try {
                    Object fco = gtc.getClass().getMethod("getFunction").invoke(gtc);
                    if (fco instanceof FunctionCall) {
                        FunctionCall fc = (FunctionCall) fco;
                        out.add(ToolExecutionRequest.builder().id(id).name(fc.getName()).arguments(fc.getArguments()).build());
                    }
                } catch (Throwable ignore) {}
            }
        } catch (Throwable inner) { /* ignore */ }
    }
}