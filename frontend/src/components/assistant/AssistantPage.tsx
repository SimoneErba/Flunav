import { useChat } from '@ai-sdk/react';
import { useTriggerChatTransport } from '@trigger.dev/sdk/chat/react';
import type { ChatSessionPersistedState } from '@trigger.dev/sdk/chat';
import type { UIMessage } from 'ai';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { axiosInstance } from '../../api/axiosInstance';
import { useAuth } from '../../context/auth.context';
import { useSimulationContext } from '../../context/simulation.context';
import { AppHeader } from '../AppHeader';
import { AppNavigation } from '../AppNavigation';
import { InvestigationWidgets, type VisualAnswerDocument } from './InvestigationWidgets';

type AgentProgress = { label: string; percentage: number; status: string };
type AssistantErrorData = { source: 'semantic' | 'model'; message: string };
type AgentMessage = UIMessage<unknown, { 'agent-progress': AgentProgress; 'visual-answer': VisualAnswerDocument; 'assistant-error': AssistantErrorData }>;
type PersistedMessage = { role: 'user' | 'assistant'; text: string; answer?: VisualAnswerDocument };
type PersistedChat = {
  chatId: string;
  history: PersistedMessage[];
  answer?: VisualAnswerDocument;
  sessions: Record<string, ChatSessionPersistedState>;
};

const parseResponse = <T,>(value: unknown): T => typeof value === 'string' ? JSON.parse(value) as T : value as T;

export const AssistantPage = () => {
  const { user } = useAuth();
  const { activeSimulation } = useSimulationContext();
  const [available, setAvailable] = useState(false);
  const [triggerPublicUrl, setTriggerPublicUrl] = useState<string>();
  const [prompt, setPrompt] = useState('');
  const scope = activeSimulation?.id ?? 'live';
  const storageKey = useMemo(() => `flumen_assistant:${user?.username ?? 'anonymous'}:${scope}`, [scope, user?.username]);
  const [chatId, setChatId] = useState(() => loadChat(storageKey).chatId);
  const [history, setHistory] = useState<PersistedMessage[]>(() => loadChat(storageKey).history);
  const [answer, setAnswer] = useState<VisualAnswerDocument | undefined>(() => loadChat(storageKey).answer);
  const [sessions, setSessions] = useState<Record<string, ChatSessionPersistedState>>(() => loadChat(storageKey).sessions);
  const [sendError, setSendError] = useState<string>();
  const [healthError, setHealthError] = useState<string>();
  const [noProgress, setNoProgress] = useState(false);

  useEffect(() => {
    const saved = loadChat(storageKey);
    setChatId(saved.chatId); setHistory(saved.history); setAnswer(saved.answer); setSessions(saved.sessions); setSendError(undefined); setHealthError(undefined); setNoProgress(false);
  }, [storageKey]);

  useEffect(() => {
    localStorage.setItem(storageKey, JSON.stringify({ chatId, history, answer, sessions }));
  }, [answer, chatId, history, sessions, storageKey]);

  useEffect(() => {
    axiosInstance.get('/api/assistant-api/health', { validateStatus: () => true, timeout: 2500 })
      .then(response => {
        const data = parseResponse<{ status?: string; triggerPublicUrl?: string; semantic?: { ok?: boolean; error?: string } }>(response.data);
        setAvailable(response.status === 200 && data.status === 'ok');
        setTriggerPublicUrl(data.triggerPublicUrl);
        setHealthError(data.semantic?.ok === false ? data.semantic.error ?? 'Assistant semantic access is unavailable.' : undefined);
      }).catch(() => { setAvailable(false); setTriggerPublicUrl(undefined); setHealthError('Assistant services are unavailable.'); });
  }, [scope]);

  const clientData = useMemo(() => ({
    inheritedContext: answer ? { entityIds: answer.context.entityIds, selectedTimestamp: answer.context.selectedTimestamp } : undefined,
  }), [answer]);
  const transport = useTriggerChatTransport({
    task: 'flumen-investigation-agent',
    baseURL: triggerPublicUrl ?? import.meta.env.VITE_TRIGGER_PUBLIC_URL ?? 'http://localhost:8030',
    clientData,
    sessions,
    fetch: async (url, init, context) => {
      console.info('[flumen-assistant-ui] trigger fetch', {
        endpoint: context.endpoint,
        url,
        method: init.method,
        body: typeof init.body === 'string' ? init.body : undefined,
      });
      const response = await fetch(url, init);
      console.info('[flumen-assistant-ui] trigger response', {
        endpoint: context.endpoint,
        url,
        status: response.status,
        contentType: response.headers.get('content-type'),
      });
      return response;
    },
    onEvent: event => {
      console.info('[flumen-assistant-ui] transport', event);
    },
    onSessionChange: (sessionChatId, session) => {
      setSessions(previous => {
        const next = { ...previous };
        if (session) {
          if (sameSession(previous[sessionChatId], session)) return previous;
          next[sessionChatId] = session;
        } else {
          if (!(sessionChatId in previous)) return previous;
          delete next[sessionChatId];
        }
        return next;
      });
    },
    accessToken: async ({ chatId: sessionChatId }) => (await axiosInstance.post('/api/assistant-api/sessions/token', { chatId: sessionChatId, simulationId: activeSimulation?.id ?? null })).data.publicAccessToken,
    startSession: async ({ chatId: sessionChatId, clientData: sessionClientData }) => (await axiosInstance.post('/api/assistant-api/sessions/start', {
      chatId: sessionChatId,
      simulationId: activeSimulation?.id ?? null,
      clientData: sessionClientData,
    })).data,
  });
  useEffect(() => {
    const session = sessions[chatId];
    if (session) transport.setSession(chatId, session);
  }, [chatId, sessions, transport]);

  const resume = shouldResume(history, sessions[chatId]);
  const { messages, sendMessage, stop: stopChat, status, error } = useChat<AgentMessage>({
    id: chatId,
    messages: historyToMessages(history),
    transport,
    resume,
  });
  const active = status === 'submitted' || status === 'streaming';
  const latest = latestParts(messages);

  useEffect(() => {
    console.info('[flumen-assistant-ui] chat state', {
      chatId,
      status,
      messages: messages.map(message => ({
        role: message.role,
        parts: message.parts.map(part => part.type),
      })),
      latestProgress: latest.progress,
      latestAnswer: latest.answer?.id,
      latestAssistantError: latest.assistantError,
    });
  }, [chatId, latest.answer?.id, latest.assistantError, latest.progress, messages, status]);

  useEffect(() => {
    if (!active || latest.progress) {
      setNoProgress(false);
      return;
    }
    const timeout = window.setTimeout(() => setNoProgress(true), 5000);
    return () => window.clearTimeout(timeout);
  }, [active, latest.progress]);

  useEffect(() => {
    if (latest.answer) setAnswer(latest.answer);
  }, [latest.answer]);

  useEffect(() => {
    if (active || !messages.length) return;
    const completed = messages.flatMap(toPersistedMessage);
    if (completed.length) setHistory(previous => mergeHistory(previous, completed));
  }, [active, messages]);

  const submitQuestion = async () => {
    const question = prompt.trim();
    if (!question || active || !available) return;
    setSendError(undefined);
    setPrompt('');
    try {
      await sendMessage({ text: question });
    } catch (sendFailure) {
      const message = sendFailure instanceof Error ? sendFailure.message : 'Unable to send the assistant question.';
      setSendError(message);
    }
  };
  const stop = useCallback(() => {
    void transport.stopGeneration(chatId);
    void axiosInstance.post('/api/assistant-api/sessions/stop', { chatId, simulationId: activeSimulation?.id ?? null });
    void stopChat();
  }, [activeSimulation?.id, chatId, stopChat, transport]);
  const resetCurrentSession = useCallback(async () => {
    const previousChatId = chatId;
    void transport.stopGeneration(previousChatId);
    void stopChat();
    setSendError(undefined);
    try {
      const response = await axiosInstance.post('/api/assistant-api/sessions/reset', { chatId: previousChatId, simulationId: activeSimulation?.id ?? null }, { validateStatus: () => true });
      if (response.status >= 400) {
        const data = parseResponse<{ error?: string }>(response.data);
        throw new Error(data.error ?? `Assistant reset failed with HTTP ${response.status}`);
      }
    } catch (resetFailure) {
      const message = resetFailure instanceof Error ? resetFailure.message : 'Unable to reset the assistant session.';
      setSendError(message);
    }
    const nextChatId = crypto.randomUUID();
    clearAssistantStorage(storageKey);
    setChatId(nextChatId);
    setHistory([]);
    setAnswer(undefined);
    setSessions({});
    setSendError(undefined);
    setNoProgress(false);
  }, [activeSimulation?.id, chatId, stopChat, storageKey, transport]);

  const rendered = active || messages.length ? messages.flatMap(toPersistedMessage) : history;

  return <div className="flex h-screen min-h-0 flex-col overflow-hidden bg-gray-50 text-gray-900 dark:bg-[#121212] dark:text-white">
    <AppHeader centerContent={<div className="font-semibold">Assistant / {scope}</div>} leftActions={<AppNavigation />} />
    <main className="mx-auto flex min-h-0 w-full max-w-6xl flex-1 flex-col overflow-y-scroll px-4 py-6 pb-40">
      {active && <Progress progress={latest.progress} stalled={noProgress} onStop={stop} onReset={resetCurrentSession} />}
      <div className="space-y-4">{rendered.map((message, index) => <div key={`${message.role}-${index}`} className={message.role === 'user' ? 'ml-auto max-w-[78%]' : 'mr-auto w-full'}><div className={`rounded-lg p-3 text-sm ${message.role === 'user' ? 'bg-blue-600 text-white' : 'bg-white shadow-sm dark:bg-gray-900'}`}>{message.role === 'assistant' ? <AssistantSummary text={message.text} /> : message.text}</div>{message.role === 'assistant' && <InvestigationWidgets answer={message.answer ?? answer} />}</div>)}</div>
    </main>
    <form className="fixed inset-x-0 bottom-0 mx-auto flex w-full max-w-6xl flex-col gap-2 bg-gray-50 px-4 pb-6 pt-3 dark:bg-[#121212]" onSubmit={event => { event.preventDefault(); void submitQuestion(); }}>
      {!available && <Alert tone="warning" message={healthError ?? 'Assistant services are unavailable. Start the assistant gateway or check its configuration.'} />}
      {error && <Alert tone="error" message={`Trigger transport error: ${error.message}`} actionLabel="Reset" onAction={resetCurrentSession} />}
      {sendError && <Alert tone="error" message={`Assistant send error: ${sendError}`} actionLabel="Reset" onAction={resetCurrentSession} />}
      {latest.assistantError && <Alert tone="error" message={`${latest.assistantError.source === 'semantic' ? 'Assistant evidence error' : 'Assistant model error'}: ${latest.assistantError.message}`} actionLabel="Reset" onAction={resetCurrentSession} />}
      <div className="flex gap-2"><input value={prompt} onChange={event => setPrompt(event.target.value)} disabled={!available || active} placeholder="Ask about throughput, alarms, item journeys, or bottlenecks" className="min-w-0 flex-1 rounded-full border border-gray-300 bg-white px-5 py-3 text-sm shadow-sm outline-none focus:border-blue-500 disabled:opacity-50 dark:border-gray-700 dark:bg-gray-900" /><button type="submit" disabled={!available || !prompt.trim() || active} className="rounded-full bg-blue-600 px-5 py-3 text-sm font-semibold text-white shadow-sm disabled:opacity-40">Ask</button>{active && <button type="button" onClick={stop} className="rounded-full border border-red-300 px-5 py-3 text-sm font-semibold text-red-700 dark:border-red-800 dark:text-red-300">Stop</button>}</div>
    </form>
  </div>;
};

const AssistantSummary = ({ text }: { text: string }) => {
  const sections = splitSummarySections(text);
  return <div className="space-y-3 leading-6">
    {sections.map((section, index) => (
      <section key={`${section.title ?? 'summary'}-${index}`}>
        {section.title && <h3 className="mb-1 font-semibold text-gray-900 dark:text-white">{section.title}</h3>}
        <p className="whitespace-pre-line text-gray-700 dark:text-gray-200">{renderInlineMarkdown(section.body)}</p>
      </section>
    ))}
  </div>;
};

function splitSummarySections(text: string) {
  const sections: Array<{ title?: string; body: string }> = [];
  const headingPattern = /\*\*([^*]+)\*\*:\s*/g;
  let cursor = 0;
  let match = headingPattern.exec(text);
  while (match) {
    const preamble = text.slice(cursor, match.index).replace(/\s*-{3,}\s*$/, '').trim();
    if (preamble) sections.push({ body: preamble });
    const bodyStart = headingPattern.lastIndex;
    const nextHeading = headingPattern.exec(text);
    const body = text.slice(bodyStart, nextHeading?.index ?? text.length).replace(/\s*-{3,}\s*$/, '').trim();
    sections.push({ title: match[1].trim(), body });
    cursor = nextHeading?.index ?? text.length;
    match = nextHeading;
  }
  const remainder = text.slice(cursor).replace(/\s*-{3,}\s*$/, '').trim();
  if (remainder && !sections.length) return [{ body: remainder }];
  if (remainder && cursor < text.length) sections.push({ body: remainder });
  return sections.length ? sections : [{ body: text.trim() }];
}

function renderInlineMarkdown(text: string) {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, index) => part.startsWith('**') && part.endsWith('**')
    ? <strong key={index}>{part.slice(2, -2)}</strong>
    : part);
}

const Progress = ({ progress, stalled, onStop, onReset }: { progress?: AgentProgress; stalled: boolean; onStop: () => void; onReset: () => void }) => <div className="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-900 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-100"><div className="flex flex-wrap justify-between gap-3"><span>{stalled ? 'No assistant progress has been received yet.' : progress?.label ?? 'Starting investigation...'}</span><div className="flex gap-3"><button type="button" onClick={onStop} className="font-semibold underline">Stop</button>{stalled && <button type="button" onClick={onReset} className="font-semibold underline">Reset</button>}</div></div><div className="mt-2 h-1.5 rounded bg-blue-100 dark:bg-blue-950"><div className="h-1.5 rounded bg-blue-600" style={{ width: `${Math.max(0, Math.min(100, progress?.percentage ?? 0))}%` }} /></div></div>;

const Alert = ({ tone, message, actionLabel, onAction }: { tone: 'warning' | 'error'; message: string; actionLabel?: string; onAction?: () => void }) => {
  const classes = tone === 'warning'
    ? 'border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-200'
    : 'border-red-300 bg-red-50 text-red-900 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200';
  return <div className={`flex items-center justify-between gap-3 rounded-lg border px-4 py-2 text-sm ${classes}`}><span>{message}</span>{actionLabel && onAction && <button type="button" onClick={onAction} className="shrink-0 font-semibold underline">{actionLabel}</button>}</div>;
};

function latestParts(messages: AgentMessage[]) {
  let progress: AgentProgress | undefined; let answer: VisualAnswerDocument | undefined; let assistantError: AssistantErrorData | undefined;
  for (const message of messages) for (const part of message.parts) {
    if (part.type === 'data-agent-progress') progress = part.data;
    if (part.type === 'data-visual-answer') answer = part.data;
    if (part.type === 'data-assistant-error') assistantError = part.data;
  }
  return { progress, answer, assistantError };
}

function toPersistedMessage(message: AgentMessage): PersistedMessage[] {
  const text = message.parts.filter(part => part.type === 'text').map(part => part.text).join('').trim();
  const answer = message.parts.find(part => part.type === 'data-visual-answer')?.data;
  return message.role === 'user' || message.role === 'assistant' ? [{ role: message.role, text: text || (message.role === 'assistant' ? 'Investigation complete.' : ''), answer }] : [];
}

function historyToMessages(history: PersistedMessage[]): AgentMessage[] {
  return history.map((message, index) => ({
    id: `persisted-${index}`,
    role: message.role,
    parts: message.answer
      ? [{ type: 'text', text: message.text }, { type: 'data-visual-answer', data: message.answer }]
      : [{ type: 'text', text: message.text }],
  }));
}

function shouldResume(history: PersistedMessage[], session?: ChatSessionPersistedState) {
  if (!session) return false;
  return session.isStreaming === true;
}

function loadChat(key: string): PersistedChat {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) ?? '{}') as Partial<PersistedChat>;
    const chatId = typeof parsed.chatId === 'string' ? parsed.chatId : crypto.randomUUID();
    return {
      chatId,
      history: Array.isArray(parsed.history) ? parsed.history : [],
      answer: parsed.answer,
      sessions: isSessionMap(parsed.sessions) ? parsed.sessions : {},
    };
  } catch {
    return { chatId: crypto.randomUUID(), history: [], sessions: {} };
  }
}

function clearAssistantStorage(currentKey: string) {
  localStorage.removeItem(currentKey);
  for (const key of Object.keys(localStorage)) {
    if (key.startsWith(`${currentKey}:`)) {
      localStorage.removeItem(key);
    }
  }
}

function mergeHistory(previous: PersistedMessage[], next: PersistedMessage[]) {
  const serialized = new Set(previous.map(message => `${message.role}:${message.text}`));
  return [...previous, ...next.filter(message => !serialized.has(`${message.role}:${message.text}`))].slice(-30);
}

function isSessionMap(value: unknown): value is Record<string, ChatSessionPersistedState> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function sameSession(left: ChatSessionPersistedState | undefined, right: ChatSessionPersistedState) {
  return left?.publicAccessToken === right.publicAccessToken
    && left.lastEventId === right.lastEventId
    && left.isStreaming === right.isStreaming;
}
