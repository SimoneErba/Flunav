import { task } from "@trigger.dev/sdk";
import {
  classifyStrategy,
  composeVisualAnswer,
  isKnownOperation,
  planOperations,
  resolveContext,
  runOperation,
  validateOperations,
  type ChatClientData,
  type InvestigationScope,
  type SemanticOperation,
} from "./lib/investigation.js";

type InvestigationInput = {
  question: string;
  simulationId?: string;
  selections?: ChatClientData["selections"];
  inheritedContext?: ChatClientData["inheritedContext"];
};

const widgets = ["metric-card", "cartesian-chart", "topology-graph", "alarm-timeline", "item-journey", "traversal-time", "evidence-table"] as const;

/** Standalone workflow: answer a read-only question with typed evidence. */
export const answerUserQuestion = task({
  id: "flumen.answer-user-question",
  run: async (input: InvestigationInput) => investigate(input),
});

export const listVisualWidgets = task({
  id: "flumen.list-visual-widgets",
  run: async () => ({ widgets, readOnly: true }),
});

/** Kept as a dashboard-visible provider diagnostic without exposing credentials. */
export const modelCompletion = task({
  id: "flumen.model-completion",
  run: async ({ prompt }: { prompt: string }) => ({ prompt: prompt.slice(0, 8000), configured: Boolean(process.env.GROQ_API_KEY) }),
});

export const chooseBackendOperations = task({
  id: "flumen.choose-backend-operations",
  run: async (input: InvestigationInput) => {
    const context = resolveContext(requireQuestion(input.question), input);
    const strategy = classifyStrategy(input.question, context);
    return { strategy, operations: validateOperations(strategy, planOperations(strategy, context)) };
  },
});

export const classifyAnswerTemplate = task({
  id: "flumen.classify-answer-template",
  run: async (input: InvestigationInput) => {
    const context = resolveContext(requireQuestion(input.question), input);
    return { strategy: classifyStrategy(input.question, context), context };
  },
});

export const chooseVisualWidgets = task({
  id: "flumen.choose-visual-widgets",
  run: async (input: InvestigationInput) => {
    const investigation = await investigate(input);
    return { strategy: investigation.strategy, widgets: investigation.widgets.map(widget => ({ id: widget.id, kind: widget.kind, evidenceIds: widget.evidenceIds })) };
  },
});

export const fetchWidgetData = task({
  id: "flumen.fetch-widget-data",
  run: async ({ operations, simulationId }: { operations: SemanticOperation[]; simulationId?: string }) => Promise.all(
    operations.filter(isKnownOperation).map(operation => runOperation(operation, scope(simulationId))),
  ),
});

export const planInvestigation = task({
  id: "flumen.plan-investigation",
  run: async (input: InvestigationInput) => {
    const context = resolveContext(requireQuestion(input.question), input);
    const strategy = classifyStrategy(input.question, context);
    return { context, strategy, operations: validateOperations(strategy, planOperations(strategy, context)) };
  },
});

export const composeAnswer = task({
  id: "flumen.compose-answer",
  run: async (input: InvestigationInput) => investigate(input),
});

export const progressiveVisualAnswer = task({
  id: "flumen.progressive-visual-answer",
  run: async (input: InvestigationInput) => investigate(input),
});

async function investigate(input: InvestigationInput) {
  const question = requireQuestion(input.question);
  const context = resolveContext(question, input);
  const strategy = classifyStrategy(question, context);
  const operations = validateOperations(strategy, planOperations(strategy, context));
  const evidence = await Promise.all(operations.map(operation => runOperation(operation, scope(input.simulationId))));
  return composeVisualAnswer(question, strategy, context, scope(input.simulationId), evidence);
}

function scope(simulationId?: string): InvestigationScope {
  return { username: "workflow", simulationId };
}

function requireQuestion(question: string): string {
  if (!question?.trim() || question.length > 8000) throw new Error("question must contain between 1 and 8000 characters");
  return question.trim();
}
