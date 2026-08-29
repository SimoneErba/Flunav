import { createOpenAI } from "@ai-sdk/openai";
import { chat } from "@trigger.dev/sdk/ai";
import { sessions } from "@trigger.dev/sdk";
import { createUIMessageStream, streamText, type ModelMessage, type UIMessage } from "ai";
import {
  chatClientDataSchema,
  classifyStrategy,
  composeVisualAnswer,
  evidencePrompt,
  planOperations,
  resolveContext,
  runOperation,
  validateSemanticAccess,
  validateOperations,
  type AgentProgress,
  type ChatClientData,
  type InvestigationScope,
  type VisualAnswerDocument,
} from "./lib/investigation.js";

type FlumenDataParts = {
  "agent-progress": AgentProgress;
  "visual-answer": VisualAnswerDocument;
  "assistant-error": AssistantErrorData;
};

type FlumenChatMessage = UIMessage<unknown, FlumenDataParts>;
type AssistantErrorData = {
  source: "semantic" | "model";
  message: string;
};

const scopeLocal = chat.local<InvestigationScope>({ id: "flumen-investigation-scope" });
const retainedContextLocal = chat.local<{ entityIds: string[]; selectedTimestamp?: string }>({ id: "flumen-investigation-context" });
const MODEL_TIMEOUT_MS = Number(process.env.FLUMEN_ASSISTANT_MODEL_TIMEOUT_MS ?? 20_000);

/**
 * Runs read-only investigations from a session-bound scope so browser-provided
 * selections cannot redirect semantic requests into another user's simulation.
 */
export const flumenInvestigationAgent = chat
  .withUIMessage<FlumenChatMessage>({ streamOptions: { sendReasoning: false } })
  .withClientData({ schema: chatClientDataSchema })
  .agent({
    id: "flumen-investigation-agent",
    maxTurns: 8,
    turnTimeout: "5m",
    uiMessageStreamOptions: {
      onError: () => "The investigation evidence was collected, but the model response failed. Use the dashboard evidence shown above and retry after checking the assistant provider configuration.",
    },
    onBoot: async ({ chatId }) => {
      debug("boot:start", { chatId });
      const session = await sessions.retrieve(chatId);
      const metadata = session.metadata && typeof session.metadata === "object"
        ? session.metadata as Record<string, unknown>
        : {};
      const username = typeof metadata.username === "string" ? metadata.username : undefined;
      const simulationId = typeof metadata.simulationId === "string" ? metadata.simulationId : undefined;
      if (!username) throw new Error("Assistant session is missing its authenticated binding");
      scopeLocal.init({ username, simulationId });
      retainedContextLocal.init(readRetainedContext(metadata.lastInvestigation));
      const diagnostic = await validateSemanticAccess({ username, simulationId });
      debug("boot:semantic-diagnostic", { chatId, ok: diagnostic.ok, status: diagnostic.status, error: diagnostic.error });
      if (!diagnostic.ok) {
        throw new Error(diagnostic.error ?? `Assistant semantic diagnostic failed for ${diagnostic.url}`);
      }
      debug("boot:complete", { chatId, username, simulationId });
    },
    onTurnComplete: async ({ chatId, responseMessage }) => {
      debug("turn:complete", { chatId, partTypes: responseMessage?.parts?.map(part => part.type) ?? [] });
      const answer = (responseMessage?.parts as Array<{ type?: string; data?: unknown }> | undefined ?? [])
        .find(part => part.type === "data-visual-answer")?.data;
      if (answer && typeof answer === "object") {
        const session = await sessions.retrieve(chatId);
        await sessions.update(chatId, {
          metadata: { ...(session.metadata ?? {}), lastInvestigation: compactAnswer(answer as VisualAnswerDocument) },
        });
      }
    },
    run: async ({ messages, signal, clientData, chatId }) => {
      debug("run:start", {
        chatId,
        modelMessages: messages.map(message => ({ role: message.role, contentType: typeof message.content, content: summarizeContent(message.content) })),
      });
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("resolving-context", "Resolving investigation context", 5, 0, 0),
      });
      const input = (clientData ?? {}) as ChatClientData;
      const question = latestQuestion(messages);
      const retained = retainedContextLocal.get();
      const context = resolveContext(question, {
        ...input,
        inheritedContext: {
          entityIds: [...new Set([...(input.inheritedContext?.entityIds ?? []), ...retained.entityIds])].slice(0, 12),
          selectedTimestamp: input.inheritedContext?.selectedTimestamp ?? retained.selectedTimestamp,
        },
      });
      const strategy = classifyStrategy(question, context);
      debug("run:classified", { chatId, strategy, question, context });
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("classifying-strategy", "Classified investigation strategy", 15, 0, 0, strategy),
      });
      const operations = validateOperations(strategy, planOperations(strategy, context));
      debug("run:planned", { chatId, operations: operations.map(operation => `${operation.id}:${operation.kind}`) });
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("planning-evidence", `Planned ${operations.length} semantic operations`, 25, 0, 0, strategy),
      });
      const evidence = [];
      for (const [index, operation] of operations.entries()) {
        const completed = evidence.filter(entry => !entry.error).length;
        const failed = evidence.filter(entry => entry.error).length;
        chat.response.write({
          type: "data-agent-progress",
          id: "agent-progress",
          data: progress("running-tool", `Running ${index + 1}/${operations.length}: ${operation.kind}`, 25 + Math.round(index / Math.max(operations.length, 1) * 55), completed, failed, strategy),
        });
        debug("run:operation:start", { chatId, operationId: operation.id, kind: operation.kind, path: operation.path });
        evidence.push(await runOperation(operation, scopeLocal.get()));
        const latest = evidence[evidence.length - 1];
        debug("run:operation:complete", { chatId, operationId: operation.id, kind: operation.kind, error: latest.error });
        chat.response.write({
          type: "data-agent-progress",
          id: "agent-progress",
          data: progress(
            "running-tool",
            `${latest.error ? "Failed" : "Retrieved"} ${operation.kind} (${index + 1}/${operations.length})`,
            25 + Math.round((index + 1) / Math.max(operations.length, 1) * 55),
            evidence.filter(entry => !entry.error).length,
            evidence.filter(entry => entry.error).length,
            strategy,
          ),
        });
      }
      const answer = composeVisualAnswer(question, strategy, context, scopeLocal.get(), evidence);
      debug("run:answer-composed", { chatId, evidence: evidence.length, widgets: answer.widgets.length, evidenceErrors: evidence.filter(entry => entry.error).length });
      const semanticError = semanticFailureMessage(evidence);
      if (semanticError) {
        chat.response.write({
          type: "data-assistant-error",
          id: "assistant-error",
          data: { source: "semantic", message: semanticError },
        });
      }
      retainedContextLocal.entityIds = answer.context.entityIds;
      retainedContextLocal.selectedTimestamp = answer.context.selectedTimestamp;
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("assembling", "Assembling visual dashboard", 90, evidence.filter(entry => !entry.error).length, evidence.filter(entry => entry.error).length, strategy),
      });
      chat.response.write({ type: "data-visual-answer", id: "visual-answer", data: answer });
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("complete", "Visual investigation complete", 100, evidence.filter(entry => !entry.error).length, evidence.filter(entry => entry.error).length, strategy),
      });
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("assembling", "Generating text response", 100, evidence.filter(entry => !entry.error).length, evidence.filter(entry => entry.error).length, strategy),
      });
      try {
        const timeoutSignal = AbortSignal.timeout(MODEL_TIMEOUT_MS);
        debug("run:model:start", { chatId, timeoutMs: MODEL_TIMEOUT_MS, model: process.env.GROQ_LARGE_MODEL ?? "openai/gpt-oss-120b" });
        const modelStream = streamText({
          ...chat.toStreamTextOptions(),
          model: groqModel(),
          messages: [{ role: "user", content: evidencePrompt(answer, evidence) }],
          abortSignal: combineSignals(signal, timeoutSignal),
          temperature: 0.1,
          onError: ({ error }) => {
            debug("run:model:error", { chatId, error: error instanceof Error ? error.message : String(error) });
          },
          onFinish: ({ finishReason, usage }) => {
            debug("run:model:finish", { chatId, finishReason, usage });
          },
          onAbort: () => {
            debug("run:model:abort", { chatId });
          },
        });
        debug("run:model:created", {
          chatId,
          streamable: typeof modelStream.toUIMessageStream === "function",
          text: typeof modelStream.text === "object",
        });
        await chat.pipe(modelStream);
        debug("run:model:piped", { chatId });
        return undefined;
      } catch (error) {
        debug("run:model:start-failed", { chatId, error: error instanceof Error ? error.message : String(error) });
        const message = "The semantic evidence was retrieved, but the assistant model could not start. Check GROQ_API_KEY and GROQ_LARGE_MODEL in the Trigger worker environment.";
        chat.response.write({
          type: "data-assistant-error",
          id: "assistant-error",
          data: { source: "model", message },
        });
        chat.response.write({
          type: "data-agent-progress",
          id: "agent-progress",
          data: progress("failed", "Model response unavailable", 100, evidence.filter(entry => !entry.error).length, evidence.filter(entry => entry.error).length, strategy),
        });
        return fallbackTextStream(`${message} ${shortEvidenceSummary(answer, evidence)}`);
      }
    },
  });

function groqModel() {
  const apiKey = process.env.GROQ_API_KEY;
  if (!apiKey) throw new Error("GROQ_API_KEY is required");
  return createOpenAI({ apiKey, baseURL: "https://api.groq.com/openai/v1" })
    .chat(process.env.GROQ_LARGE_MODEL ?? "openai/gpt-oss-120b");
}

function latestQuestion(messages: ModelMessage[]): string {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message.role !== "user") continue;
    if (typeof message.content === "string" && message.content.trim()) return message.content.trim();
    if (Array.isArray(message.content)) {
      const text = message.content.filter(part => part.type === "text").map(part => part.text).join(" ").trim();
      if (text) return text;
    }
  }
  return "Show the current system state";
}

function progress(
  status: AgentProgress["status"], label: string, percentage: number, completedOperations: number, failedOperations: number,
  strategy?: AgentProgress["strategy"],
): AgentProgress {
  return { status, label, percentage, completedOperations, failedOperations, strategy };
}

function debug(event: string, data?: Record<string, unknown>) {
  console.info(`[flumen-assistant] ${event}`, JSON.stringify(data ?? {}));
}

function summarizeContent(content: ModelMessage["content"]): unknown {
  if (typeof content === "string") return content.slice(0, 120);
  if (Array.isArray(content)) {
    return content.map(part => part.type === "text" ? { type: part.type, text: part.text.slice(0, 120) } : { type: part.type });
  }
  return content;
}

function fallbackTextStream(text: string) {
  return createUIMessageStream<FlumenChatMessage>({
    execute: ({ writer }) => {
      const id = `fallback-${Date.now()}`;
      writer.write({ type: "text-start", id });
      writer.write({ type: "text-delta", id, delta: text });
      writer.write({ type: "text-end", id });
    },
  });
}

function combineSignals(primary: AbortSignal, secondary: AbortSignal): AbortSignal {
  if (primary.aborted) return primary;
  if (secondary.aborted) return secondary;
  const controller = new AbortController();
  const abort = () => controller.abort();
  primary.addEventListener("abort", abort, { once: true });
  secondary.addEventListener("abort", abort, { once: true });
  return controller.signal;
}

function shortEvidenceSummary(answer: VisualAnswerDocument, evidence: Array<{ error?: string }>) {
  const succeeded = evidence.filter(entry => !entry.error).length;
  const failed = evidence.filter(entry => entry.error).length;
  return `${answer.title}: ${answer.verdict} Retrieved ${succeeded} operation${succeeded === 1 ? "" : "s"} and ${failed} failed.`;
}

function semanticFailureMessage(evidence: Array<{ error?: string }>): string | undefined {
  const errors = evidence.map(entry => entry.error).filter((error): error is string => Boolean(error));
  if (!errors.length) return undefined;
  if (errors.some(error => /HTTP 40[13]/.test(error))) {
    return "Semantic endpoint rejected the assistant service token. Align backend APP_ASSISTANT_SERVICE_TOKEN with FLUMEN_SERVICE_TOKEN or FLUMEN_ASSISTANT_SERVICE_TOKEN, then restart trigger-dev.";
  }
  if (errors.length === evidence.length) {
    return "No semantic evidence could be retrieved for this investigation. Check backend availability and assistant service-token configuration.";
  }
  return undefined;
}

function compactAnswer(answer: VisualAnswerDocument) {
  return {
    id: answer.id,
    strategy: answer.strategy,
    entityIds: answer.context.entityIds,
    selectedTimestamp: answer.context.selectedTimestamp,
    generatedAt: answer.generatedAt,
  };
}

function readRetainedContext(value: unknown): { entityIds: string[]; selectedTimestamp?: string } {
  if (!value || typeof value !== "object" || Array.isArray(value)) return { entityIds: [] };
  const candidate = value as { entityIds?: unknown; selectedTimestamp?: unknown };
  return {
    entityIds: Array.isArray(candidate.entityIds)
      ? candidate.entityIds.filter((id): id is string => typeof id === "string" && /^[A-Za-z0-9_.:-]{1,160}$/.test(id)).slice(0, 12)
      : [],
    selectedTimestamp: typeof candidate.selectedTimestamp === "string" && !Number.isNaN(Date.parse(candidate.selectedTimestamp))
      ? candidate.selectedTimestamp
      : undefined,
  };
}
