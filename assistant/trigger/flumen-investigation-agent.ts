import { createOpenAI } from "@ai-sdk/openai";
import { chat } from "@trigger.dev/sdk/ai";
import { sessions } from "@trigger.dev/sdk";
import { streamText, type ModelMessage, type UIMessage } from "ai";
import {
  chatClientDataSchema,
  classifyStrategy,
  composeVisualAnswer,
  evidencePrompt,
  planOperations,
  resolveContext,
  runOperation,
  type AgentProgress,
  type ChatClientData,
  type InvestigationScope,
  type VisualAnswerDocument,
} from "./lib/investigation.js";

type FlumenDataParts = {
  "agent-progress": AgentProgress;
  "visual-answer": VisualAnswerDocument;
};

type FlumenChatMessage = UIMessage<unknown, FlumenDataParts>;

const scopeLocal = chat.local<InvestigationScope>({ id: "flumen-investigation-scope" });
const retainedContextLocal = chat.local<{ entityIds: string[]; selectedTimestamp?: string }>({ id: "flumen-investigation-context" });

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
    onBoot: async ({ chatId }) => {
      const session = await sessions.retrieve(chatId);
      const metadata = session.metadata && typeof session.metadata === "object"
        ? session.metadata as Record<string, unknown>
        : {};
      const username = typeof metadata.username === "string" ? metadata.username : undefined;
      const simulationId = typeof metadata.simulationId === "string" ? metadata.simulationId : undefined;
      if (!username) throw new Error("Assistant session is missing its authenticated binding");
      scopeLocal.init({ username, simulationId });
      retainedContextLocal.init(readRetainedContext(metadata.lastInvestigation));
    },
    onTurnStart: async ({ writer }) => {
      writer.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("resolving-context", "Resolving investigation context", 5, 0, 0),
      });
    },
    onTurnComplete: async ({ chatId, responseMessage }) => {
      const answer = (responseMessage?.parts as Array<{ type?: string; data?: unknown }> | undefined ?? [])
        .find(part => part.type === "data-visual-answer")?.data;
      if (answer && typeof answer === "object") {
        const session = await sessions.retrieve(chatId);
        await sessions.update(chatId, {
          metadata: { ...(session.metadata ?? {}), lastInvestigation: compactAnswer(answer as VisualAnswerDocument) },
        });
      }
    },
    run: async ({ messages, signal, clientData }) => {
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
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("classifying-strategy", "Classified investigation strategy", 15, 0, 0, strategy),
      });
      const operations = planOperations(strategy, context);
      chat.response.write({
        type: "data-agent-progress",
        id: "agent-progress",
        data: progress("planning-evidence", `Planned ${operations.length} semantic operations`, 25, 0, 0, strategy),
      });
      const evidence = [];
      for (const [index, operation] of operations.entries()) {
        chat.response.write({
          type: "data-agent-progress",
          id: "agent-progress",
          data: progress("running-tool", `Running ${operation.kind}`, 25 + Math.round(index / Math.max(operations.length, 1) * 55), index, evidence.filter(entry => entry.error).length, strategy),
        });
        evidence.push(await runOperation(operation, scopeLocal.get()));
      }
      const answer = composeVisualAnswer(question, strategy, context, scopeLocal.get(), evidence);
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
      return streamText({
        model: groqModel(),
        messages: [{ role: "user", content: evidencePrompt(answer, evidence) }],
        abortSignal: signal,
        temperature: 0.1,
      });
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
