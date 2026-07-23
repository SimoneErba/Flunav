import { useEffect, useMemo, useRef, useState } from 'react';
import { useRealtimeRun } from '@trigger.dev/react-hooks';
import { useNavigate } from 'react-router-dom';
import { AppHeader } from '../AppHeader';
import { axiosInstance } from '../../api/axiosInstance';
import { useAuth } from '../../context/auth.context';
import { useSimulationContext } from '../../context/simulation.context';

type Alarm = {
  alarmId: string;
  conveyorId: string;
  eventType: string;
  severity: string;
  typology: string;
  stopsConveyor: boolean;
  timestamp: string;
};

type Summary = {
  locations: number;
  conveyors: number;
  activeConveyors: number;
  activeItems: number;
  activeAlarms: number;
  stoppingAlarms: number;
};

type Envelope<T> = { data: T; meta: { simulationId?: string }; error?: { message: string } };

type Conversation = {
  storageKey: string;
  chatId: string;
  messages: Array<{ role: 'user' | 'assistant'; content: string }>;
  streamCursor?: string;
  publicAccessToken?: string;
};

const loadConversation = (key: string): Conversation => {
  const stored = localStorage.getItem(key);
  if (stored) {
    try {
      const parsed = JSON.parse(stored) as Partial<Conversation>;
      const messages = Array.isArray(parsed.messages)
        ? parsed.messages.filter((message): message is Conversation['messages'][number] => (
          message !== null
          && typeof message === 'object'
          && (message.role === 'user' || message.role === 'assistant')
          && typeof message.content === 'string'
        ))
        : null;
      if (typeof parsed.chatId === 'string' && messages) {
        return {
          storageKey: key,
          chatId: parsed.chatId,
          messages,
          streamCursor: typeof parsed.streamCursor === 'string' ? parsed.streamCursor : undefined,
        };
      }
    } catch {
      // Malformed browser-local history is discarded below.
    }
    localStorage.removeItem(key);
  }
  return { storageKey: key, chatId: crypto.randomUUID(), messages: [] };
};

export const AssistantPage = () => {
  const navigate = useNavigate();
  const { user } = useAuth();
  const { activeSimulation } = useSimulationContext();
  const [summary, setSummary] = useState<Summary | null>(null);
  const [alarms, setAlarms] = useState<Alarm[]>([]);
  const [servicesAvailable, setServicesAvailable] = useState(false);
  const [triggerPublicUrl, setTriggerPublicUrl] = useState<string>();
  const [question, setQuestion] = useState('');
  const scope = activeSimulation?.id ?? 'live';
  const conversationKey = useMemo(
    () => `flumen_assistant:${user?.username ?? 'anonymous'}:${scope}`,
    [scope, user?.username],
  );
  const [conversation, setConversation] = useState<Conversation>(() => loadConversation(conversationKey));

  useEffect(() => {
    setConversation(loadConversation(conversationKey));
  }, [conversationKey]);

  useEffect(() => {
    if (conversation.storageKey === conversationKey) {
      const persistedConversation: Conversation = {
        storageKey: conversation.storageKey,
        chatId: conversation.chatId,
        messages: conversation.messages,
        streamCursor: conversation.streamCursor,
      };
      localStorage.setItem(conversationKey, JSON.stringify(persistedConversation));
    }
  }, [conversation, conversationKey]);

  useEffect(() => {
    const headers = activeSimulation?.id ? { 'X-Simulation-ID': activeSimulation.id } : undefined;
    Promise.all([
      axiosInstance.get<Envelope<Summary>>('/analytics/investigation/system/summary', { headers }),
      axiosInstance.get<Envelope<Alarm[]>>('/analytics/investigation/alarms', { headers }),
    ]).then(([summaryResponse, alarmResponse]) => {
      setSummary(summaryResponse.data.data);
      setAlarms(alarmResponse.data.data);
    }).catch(() => {
      setSummary(null);
      setAlarms([]);
    });

    axiosInstance.get('/assistant-api/health', {
      timeout: 2500,
      validateStatus: () => true,
    }).then(response => {
      setServicesAvailable(response.status === 200
        && response.headers['content-type']?.includes('application/json')
        && response.data?.status === 'ok');
      setTriggerPublicUrl(typeof response.data?.triggerPublicUrl === 'string'
        ? response.data.triggerPublicUrl
        : undefined);
    }).catch(() => {
      setServicesAvailable(false);
      setTriggerPublicUrl(undefined);
    });
  }, [activeSimulation?.id]);

  const submitQuestion = async () => {
    const content = question.trim();
    if (!content || !servicesAvailable || conversation.streamCursor) return;
    const expectedConversationKey = conversationKey;
    const chatId = conversation.chatId;
    const simulationId = activeSimulation?.id ?? null;
    setQuestion('');
    setConversation(previous => ({
      ...previous,
      messages: [...previous.messages, { role: 'user', content }],
    }));
    try {
      const response = await axiosInstance.post('/assistant-api/chat', {
        chatId,
        message: content,
        simulationId,
      });
      setConversation(previous => ({
        ...(previous.storageKey === expectedConversationKey ? {
          ...previous,
          messages: [...previous.messages, {
            role: 'assistant' as const,
            content: response.data.message ?? 'Investigation started.',
          }],
          streamCursor: response.data.runId,
          publicAccessToken: response.data.publicAccessToken,
        } : previous),
      }));
    } catch {
      setServicesAvailable(false);
      setConversation(previous => previous.storageKey === expectedConversationKey ? {
        ...previous,
        messages: [...previous.messages, {
          role: 'assistant',
          content: 'Assistant services are unavailable. Your question was not submitted.',
        }],
      } : previous);
    }
  };

  useEffect(() => {
    if (conversation.storageKey !== conversationKey
      || !conversation.streamCursor
      || conversation.publicAccessToken) {
      return;
    }
    const expectedConversationKey = conversationKey;
    axiosInstance.post('/assistant-api/token', {
      runId: conversation.streamCursor,
      chatId: conversation.chatId,
      simulationId: activeSimulation?.id ?? null,
    }).then(response => {
      setConversation(previous => previous.storageKey === expectedConversationKey ? {
        ...previous,
        publicAccessToken: response.data.publicAccessToken,
      } : previous);
    }).catch(() => setServicesAvailable(false));
  }, [
    activeSimulation?.id,
    conversation.chatId,
    conversation.publicAccessToken,
    conversation.storageKey,
    conversation.streamCursor,
    conversationKey,
  ]);

  const finishRun = (status: string, output: unknown) => {
    const expectedConversationKey = conversationKey;
    const successful = status === 'COMPLETED';
    const message = successful && output && typeof output === 'object' && 'message' in output
      && typeof output.message === 'string'
      ? output.message
      : successful ? 'Investigation completed.' : 'The investigation task could not complete.';
    setConversation(previous => previous.storageKey === expectedConversationKey ? {
      ...previous,
      messages: [...previous.messages.slice(0, -1), { role: 'assistant', content: message }],
      streamCursor: undefined,
      publicAccessToken: undefined,
    } : previous);
  };

  const latestByAlarm = Array.from(new Map(alarms.map(alarm => [alarm.alarmId, alarm])).values());

  return (
    <div className="flex min-h-screen flex-col bg-gray-50 text-gray-900 dark:bg-[#121212] dark:text-white">
      <AppHeader
        centerContent={<div className="font-semibold">FlowLens Assistant · {scope}</div>}
        leftActions={(
          <button type="button" onClick={() => navigate('/live')}
            className="rounded-md px-3 py-1.5 text-sm font-semibold hover:bg-blue-50 hover:text-blue-600 dark:hover:bg-gray-800">
            Live workspace
          </button>
        )}
      />
      <main className="mx-auto grid w-full max-w-[1600px] flex-1 gap-5 p-6 lg:grid-cols-[minmax(0,1.4fr)_minmax(360px,.6fr)]">
        <section className="space-y-5">
          <div className="grid gap-3 sm:grid-cols-3">
            <MetricCard label="Active items" value={summary?.activeItems} />
            <MetricCard label="Running conveyors" value={summary ? `${summary.activeConveyors}/${summary.conveyors}` : undefined} />
            <MetricCard label="Stopping alarms" value={summary?.stoppingAlarms} warning={Boolean(summary?.stoppingAlarms)} />
          </div>
          <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-800 dark:bg-gray-900">
            <h2 className="mb-4 text-lg font-semibold">Alarm timeline</h2>
            {latestByAlarm.length === 0 ? (
              <p className="text-sm text-gray-500">No alarm evidence in this scope and time window.</p>
            ) : latestByAlarm.map(alarm => (
              <div key={`${alarm.alarmId}-${alarm.eventType}`} className="mb-3 flex gap-4 border-l-2 border-amber-500 pl-4">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-semibold">{alarm.typology}</span>
                    <span className="rounded bg-gray-100 px-2 py-0.5 text-xs dark:bg-gray-800">{alarm.severity}</span>
                    {alarm.stopsConveyor && <span className="text-xs font-semibold text-red-600">STOPPING</span>}
                  </div>
                  <p className="text-sm text-gray-500">{alarm.conveyorId} · {alarm.eventType}</p>
                </div>
                <time className="text-xs text-gray-500">{new Date(alarm.timestamp).toLocaleString()}</time>
              </div>
            ))}
          </div>
          <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm dark:border-gray-800 dark:bg-gray-900">
            <div className="border-b border-gray-200 px-5 py-4 font-semibold dark:border-gray-800">Evidence table</div>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="bg-gray-50 text-xs uppercase text-gray-500 dark:bg-gray-950">
                  <tr><th className="px-5 py-3">Alarm</th><th className="px-5 py-3">Conveyor</th><th className="px-5 py-3">Evidence</th></tr>
                </thead>
                <tbody>{latestByAlarm.map(alarm => (
                  <tr key={alarm.alarmId} className="border-t border-gray-100 dark:border-gray-800">
                    <td className="px-5 py-3 font-mono text-xs">{alarm.alarmId}</td>
                    <td className="px-5 py-3">{alarm.conveyorId}</td>
                    <td className="px-5 py-3">{alarm.typology} is reported typology, not an asserted root cause.</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </div>
        </section>
        <aside className="flex min-h-[620px] flex-col rounded-xl border border-gray-200 bg-white shadow-sm dark:border-gray-800 dark:bg-gray-900">
          <div className="border-b border-gray-200 p-5 dark:border-gray-800">
            <h2 className="font-semibold">Investigation chat</h2>
            {!servicesAvailable && (
              <div className="mt-3 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
                Assistant services unavailable. Core Flumen analytics remain available.
              </div>
            )}
          </div>
          <div className="flex-1 space-y-3 overflow-y-auto p-5">
            {conversation.streamCursor && triggerPublicUrl && (
              <RealtimeRunStatus
                runId={conversation.streamCursor}
                accessToken={conversation.publicAccessToken}
                baseURL={triggerPublicUrl}
                onFinished={finishRun}
              />
            )}
            {conversation.messages.map((message, index) => (
              <div key={index} className={`rounded-lg p-3 text-sm ${
                message.role === 'user' ? 'ml-8 bg-blue-600 text-white' : 'mr-8 bg-gray-100 dark:bg-gray-800'
              }`}>{message.content}</div>
            ))}
          </div>
          <div className="border-t border-gray-200 p-4 dark:border-gray-800">
            <div className="flex gap-2">
              <input value={question} onChange={event => setQuestion(event.target.value)}
                onKeyDown={event => { if (event.key === 'Enter') void submitQuestion(); }}
                disabled={!servicesAvailable || Boolean(conversation.streamCursor)}
                placeholder="Ask about an alarm, item, or component…"
                className="min-w-0 flex-1 rounded-lg border border-gray-300 bg-transparent px-3 py-2 text-sm disabled:opacity-50 dark:border-gray-700" />
              <button type="button" onClick={() => void submitQuestion()}
                disabled={!servicesAvailable || !question.trim() || Boolean(conversation.streamCursor)}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40">Send</button>
            </div>
          </div>
        </aside>
      </main>
    </div>
  );
};

const MetricCard = ({ label, value, warning = false }: {
  label: string;
  value?: string | number;
  warning?: boolean;
}) => (
  <div className="rounded-xl border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-800 dark:bg-gray-900">
    <div className="text-xs font-semibold uppercase tracking-wide text-gray-500">{label}</div>
    <div className={`mt-2 text-3xl font-bold ${warning ? 'text-red-600' : ''}`}>{value ?? '—'}</div>
  </div>
);

const TERMINAL_RUN_STATUSES = new Set([
  'COMPLETED', 'FAILED', 'CRASHED', 'CANCELED', 'SYSTEM_FAILURE', 'INTERRUPTED', 'EXPIRED',
]);

const RealtimeRunStatus = ({ runId, accessToken, baseURL, onFinished }: {
  runId: string;
  accessToken?: string;
  baseURL: string;
  onFinished: (status: string, output: unknown) => void;
}) => {
  const completedRunRef = useRef<string>();
  const { run, error } = useRealtimeRun(runId, {
    accessToken,
    baseURL,
    enabled: Boolean(accessToken),
  });
  const status = String(run?.status ?? 'CONNECTING');
  const progress = run?.metadata && typeof run.metadata.progress === 'number'
    ? run.metadata.progress
    : undefined;

  useEffect(() => {
    if (!run || !TERMINAL_RUN_STATUSES.has(status) || completedRunRef.current === runId) return;
    completedRunRef.current = runId;
    onFinished(status, run.output);
  }, [onFinished, run, runId, status]);

  return (
    <div className="rounded-lg border border-blue-200 bg-blue-50 p-3 text-xs text-blue-900 dark:border-blue-900 dark:bg-blue-950/40 dark:text-blue-100">
      <div className="flex items-center justify-between gap-3">
        <span>{error ? 'Realtime stream unavailable' : String(run?.metadata?.status ?? 'Connecting to investigation…')}</span>
        <span>{progress !== undefined ? `${progress}%` : status.toLowerCase()}</span>
      </div>
      {progress !== undefined && (
        <div className="mt-2 h-1.5 rounded bg-blue-100 dark:bg-blue-950">
          <div className="h-1.5 rounded bg-blue-600" style={{ width: `${Math.max(0, Math.min(100, progress))}%` }} />
        </div>
      )}
    </div>
  );
};
