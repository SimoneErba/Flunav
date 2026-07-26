import { useChat } from '@ai-sdk/react';
import { useTriggerChatTransport } from '@trigger.dev/sdk/chat/react';
import type { UIMessage } from 'ai';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { axiosInstance } from '../../api/axiosInstance';
import { useAuth } from '../../context/auth.context';
import { useSimulationContext } from '../../context/simulation.context';
import { AppHeader } from '../AppHeader';
import { InvestigationWidgets, type VisualAnswerDocument } from './InvestigationWidgets';

type AgentProgress = { label: string; percentage: number; status: string };
type AgentMessage = UIMessage<unknown, { 'agent-progress': AgentProgress; 'visual-answer': VisualAnswerDocument }>;
type PersistedMessage = { role: 'user' | 'assistant'; text: string; answer?: VisualAnswerDocument };

const parseResponse = <T,>(value: unknown): T => typeof value === 'string' ? JSON.parse(value) as T : value as T;

export const AssistantPage = () => {
  const navigate = useNavigate();
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

  useEffect(() => {
    const saved = loadChat(storageKey);
    setChatId(saved.chatId); setHistory(saved.history); setAnswer(saved.answer);
  }, [storageKey]);

  useEffect(() => {
    localStorage.setItem(storageKey, JSON.stringify({ chatId, history, answer }));
  }, [answer, chatId, history, storageKey]);

  useEffect(() => {
    axiosInstance.get('/api/assistant-api/health', { validateStatus: () => true, timeout: 2500 })
      .then(response => {
        const data = parseResponse<{ status?: string; triggerPublicUrl?: string }>(response.data);
        setAvailable(response.status === 200 && data.status === 'ok');
        setTriggerPublicUrl(data.triggerPublicUrl);
      }).catch(() => { setAvailable(false); setTriggerPublicUrl(undefined); });
  }, [scope]);

  const clientData = useMemo(() => ({
    inheritedContext: answer ? { entityIds: answer.context.entityIds, selectedTimestamp: answer.context.selectedTimestamp } : undefined,
  }), [answer]);
  const transport = useTriggerChatTransport({
    task: 'flumen-investigation-agent',
    baseURL: triggerPublicUrl ?? import.meta.env.VITE_TRIGGER_PUBLIC_URL ?? 'http://localhost:8030',
    clientData,
    accessToken: async ({ chatId: sessionChatId }) => (await axiosInstance.post('/api/assistant-api/sessions/token', { chatId: sessionChatId, simulationId: activeSimulation?.id ?? null })).data.publicAccessToken,
    startSession: async ({ chatId: sessionChatId, clientData: sessionClientData }) => (await axiosInstance.post('/api/assistant-api/sessions/start', {
      chatId: sessionChatId,
      simulationId: activeSimulation?.id ?? null,
      clientData: sessionClientData,
    })).data,
  });
  const { messages, sendMessage, stop: stopChat, status, error } = useChat<AgentMessage>({ id: chatId, transport });
  const active = status === 'submitted' || status === 'streaming';
  const latest = latestParts(messages);

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
    setPrompt('');
    await sendMessage({ text: question });
  };
  const stop = useCallback(() => {
    void transport.stopGeneration(chatId);
    void axiosInstance.post('/api/assistant-api/sessions/stop', { chatId, simulationId: activeSimulation?.id ?? null });
    void stopChat();
  }, [activeSimulation?.id, chatId, stopChat, transport]);

  const canAccessUsers = user?.role === 'SUPERADMIN';
  const canAccessDestinationMappings = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
  const canAccessBi = user?.role === 'ADMIN' || user?.role === 'SUPERADMIN';
  const nav = (label: string, path: string, enabled: boolean, selected = false) => <button type="button" onClick={() => navigate(path)} disabled={!enabled} className={`rounded-md px-3 py-1.5 text-sm font-semibold disabled:cursor-not-allowed disabled:opacity-40 ${selected ? 'bg-blue-600 text-white' : 'text-gray-600 hover:bg-blue-50 hover:text-blue-600 dark:text-gray-300 dark:hover:bg-gray-800 dark:hover:text-blue-400'}`}>{label}</button>;
  const rendered = active || messages.length ? messages.flatMap(toPersistedMessage) : history;

  return <div className="flex min-h-screen flex-col bg-gray-50 text-gray-900 dark:bg-[#121212] dark:text-white">
    <AppHeader centerContent={<div className="font-semibold">Assistant / {scope}</div>} leftActions={<div className="flex items-center gap-2">{nav('Live', '/live', true)}{nav('Users', '/admin', canAccessUsers)}{nav('Mappings', '/admin/destination-mappings', canAccessDestinationMappings)}{nav('BI', '/admin/bi', canAccessBi)}{nav('Assistant', '/assistant', true, true)}</div>} />
    <main className="mx-auto flex min-h-0 w-full max-w-6xl flex-1 flex-col px-4 py-6 pb-32">
      {active && <Progress progress={latest.progress} onStop={stop} />}
      <div className="space-y-4">{rendered.map((message, index) => <div key={`${message.role}-${index}`} className={message.role === 'user' ? 'ml-auto max-w-[78%]' : 'mr-auto w-full'}><div className={`rounded-lg p-3 text-sm ${message.role === 'user' ? 'bg-blue-600 text-white' : 'bg-white shadow-sm dark:bg-gray-900'}`}>{message.text}</div>{message.role === 'assistant' && <InvestigationWidgets answer={message.answer ?? answer} />}</div>)}</div>
    </main>
    <form className="fixed inset-x-0 bottom-0 mx-auto flex w-full max-w-6xl flex-col gap-2 bg-gray-50 px-4 pb-6 pt-3 dark:bg-[#121212]" onSubmit={event => { event.preventDefault(); void submitQuestion(); }}>
      {!available && <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-200">Assistant services are unavailable. Start the assistant gateway or check its configuration.</div>}
      {error && <div className="rounded-lg border border-red-300 bg-red-50 px-4 py-2 text-sm text-red-900 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200">{error.message}</div>}
      <div className="flex gap-2"><input value={prompt} onChange={event => setPrompt(event.target.value)} disabled={!available || active} placeholder="Ask about throughput, alarms, item journeys, or bottlenecks" className="min-w-0 flex-1 rounded-full border border-gray-300 bg-white px-5 py-3 text-sm shadow-sm outline-none focus:border-blue-500 disabled:opacity-50 dark:border-gray-700 dark:bg-gray-900" /><button type="submit" disabled={!available || !prompt.trim() || active} className="rounded-full bg-blue-600 px-5 py-3 text-sm font-semibold text-white shadow-sm disabled:opacity-40">Ask</button>{active && <button type="button" onClick={stop} className="rounded-full border border-red-300 px-5 py-3 text-sm font-semibold text-red-700 dark:border-red-800 dark:text-red-300">Stop</button>}</div>
    </form>
  </div>;
};

const Progress = ({ progress, onStop }: { progress?: AgentProgress; onStop: () => void }) => <div className="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-3 text-sm text-blue-900 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-100"><div className="flex justify-between gap-3"><span>{progress?.label ?? 'Starting investigation…'}</span><button type="button" onClick={onStop} className="font-semibold underline">Stop</button></div><div className="mt-2 h-1.5 rounded bg-blue-100 dark:bg-blue-950"><div className="h-1.5 rounded bg-blue-600" style={{ width: `${Math.max(0, Math.min(100, progress?.percentage ?? 0))}%` }} /></div></div>;

function latestParts(messages: AgentMessage[]) {
  let progress: AgentProgress | undefined; let answer: VisualAnswerDocument | undefined;
  for (const message of messages) for (const part of message.parts) {
    if (part.type === 'data-agent-progress') progress = part.data;
    if (part.type === 'data-visual-answer') answer = part.data;
  }
  return { progress, answer };
}

function toPersistedMessage(message: AgentMessage): PersistedMessage[] {
  const text = message.parts.filter(part => part.type === 'text').map(part => part.text).join('').trim();
  const answer = message.parts.find(part => part.type === 'data-visual-answer')?.data;
  return message.role === 'user' || message.role === 'assistant' ? [{ role: message.role, text: text || (message.role === 'assistant' ? 'Investigation complete.' : ''), answer }] : [];
}

function loadChat(key: string): { chatId: string; history: PersistedMessage[]; answer?: VisualAnswerDocument } {
  try { const parsed = JSON.parse(localStorage.getItem(key) ?? '{}') as Partial<{ chatId: string; history: PersistedMessage[]; answer: VisualAnswerDocument }>; return { chatId: typeof parsed.chatId === 'string' ? parsed.chatId : crypto.randomUUID(), history: Array.isArray(parsed.history) ? parsed.history : [], answer: parsed.answer }; } catch { return { chatId: crypto.randomUUID(), history: [] }; }
}

function mergeHistory(previous: PersistedMessage[], next: PersistedMessage[]) {
  const serialized = new Set(previous.map(message => `${message.role}:${message.text}`));
  return [...previous, ...next.filter(message => !serialized.has(`${message.role}:${message.text}`))].slice(-30);
}
