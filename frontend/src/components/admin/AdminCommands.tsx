import React, { useState } from "react";
import type { AxiosError } from "axios";
import toast from "react-hot-toast";
import { v4 as uuidv4 } from "uuid";
import { useApi } from "../../hooks/useApi";
import { LocationTypeEnum } from "../../api-client";
import { axiosInstance } from "../../api/axiosInstance";
import { useSimulationContext } from "../../context/simulation.context";

type CommandAction = "START_CONVEYOR" | "STOP_CONVEYOR" | "RELEASE_STAGING" | "EMPTY_CHUTE";

const COMMAND_OPTIONS: Array<{ value: CommandAction; label: string }> = [
  { value: "START_CONVEYOR", label: "Start conveyor" },
  { value: "STOP_CONVEYOR", label: "Stop conveyor" },
  { value: "RELEASE_STAGING", label: "Release staging conveyor" },
  { value: "EMPTY_CHUTE", label: "Empty chute" },
];

type ConveyorLookup = { id?: string; name?: string };
type LocationLookup = { id?: string; type?: LocationTypeEnum };

const matchesTarget = (value: string | undefined, target: string) => {
  return value !== undefined && value.trim().toLowerCase() === target.trim().toLowerCase();
};

const persistentDemoToast = (message: string, id: string) => {
  toast((notification) => (
    <div className="flex max-w-lg items-center gap-3">
      <span aria-hidden="true">▶️</span>
      <span className="flex-1 text-sm font-medium">{message}</span>
      <button
        type="button"
        onClick={() => toast.dismiss(notification.id)}
        className="rounded px-2 py-1 text-xs font-semibold text-gray-500 hover:bg-gray-100 hover:text-gray-900"
      >
        Dismiss
      </button>
    </div>
  ), { id, duration: Infinity });
};

export const AdminCommands = ({
  embedded = false,
  dockSide = "right",
}: {
  embedded?: boolean;
  dockSide?: "left" | "right" | "bottom";
}) => {
  const { conveyorsApi, locationApi, clientDemoApi } = useApi();
  const { activeSimulation, setActiveSimulation } = useSimulationContext();
  const [action, setAction] = useState<CommandAction>("START_CONVEYOR");
  const [target, setTarget] = useState("");
  const [loading, setLoading] = useState(false);
  const [demoLoading, setDemoLoading] = useState(false);
  const [airportDemoLoading, setAirportDemoLoading] = useState(false);
  const [spacingDemoLoading, setSpacingDemoLoading] = useState(false);
  const demoStarting = demoLoading || airportDemoLoading || spacingDemoLoading;
  const isBottomEmbedded = embedded && dockSide === "bottom";

  const resolveConveyorId = async (value: string) => {
    const response = await conveyorsApi.getAllConveyors();
    const conveyors = response.data as ConveyorLookup[];
    return conveyors.find((conveyor) => matchesTarget(conveyor.id, value));
  };

  const resolveChuteId = async (value: string) => {
    const response = await locationApi.getAllLocations();
    const locations = response.data as LocationLookup[];
    return locations.find((location) => location.type === LocationTypeEnum.Chute && matchesTarget(location.id, value));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const normalizedTarget = target.trim();
    if (!normalizedTarget) return;

    setLoading(true);

    // Generate a random sender ID for each request to ensure we receive WebSocket notifications.
    // The backend suppresses notifications for the sender ID that initiated the action.
    const requestHeaders = {
      "X-Sender-ID": uuidv4(),
      ...(activeSimulation?.id ? { "X-Simulation-ID": activeSimulation.id } : {}),
    };

    try {
      if (action === "EMPTY_CHUTE") {
        const chute = await resolveChuteId(normalizedTarget);
        if (!chute?.id) {
          toast.error("No chute found for that ID");
          return;
        }
        await axiosInstance.put(`/api/locations/${encodeURIComponent(chute.id)}/empty`, undefined, {
          headers: requestHeaders,
        });
      } else {
        const conveyor = await resolveConveyorId(normalizedTarget);
        if (!conveyor?.id) {
          toast.error("No conveyor found for that ID");
          return;
        }

        if (action === "START_CONVEYOR") {
          await axiosInstance.put(`/api/conveyors/${encodeURIComponent(conveyor.id)}/activate`, undefined, {
            headers: requestHeaders,
          });
        } else if (action === "STOP_CONVEYOR") {
          await axiosInstance.put(`/api/conveyors/${encodeURIComponent(conveyor.id)}/deactivate`, undefined, {
            headers: requestHeaders,
          });
        } else {
          await conveyorsApi.releaseStagingConveyor(conveyor.id, {
            headers: requestHeaders,
          });
        }
      }

      toast.success("Command sent");
      setAction("START_CONVEYOR");
      setTarget("");
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      console.error("Failed to send command", error);
      toast.error("Failed to send command. " + (axiosError.response?.data?.message || ""));
    } finally {
      setLoading(false);
    }
  };

  const startClientDemo = async () => {
    if (activeSimulation?.id) {
      toast.error("Exit the simulation before starting the live client demo");
      return;
    }
    setDemoLoading(true);
    try {
      const response = await clientDemoApi.start();
      if (!response.data.simulation) throw new Error("Demo response did not include a simulation");
      setActiveSimulation(response.data.simulation);
      persistentDemoToast(
        "Client demo started. The flow anomaly will appear in about 10 seconds.",
        "client-flow-demo-started",
      );
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || "Unable to start the client demo");
    } finally {
      setDemoLoading(false);
    }
  };

  const startAirportRoutingDemo = async () => {
    if (activeSimulation?.id) {
      toast.error("Exit the simulation before starting the airport routing demo");
      return;
    }
    setAirportDemoLoading(true);
    try {
      const response = await clientDemoApi.startAirportRouting();
      if (!response.data.simulation) throw new Error("Demo response did not include a simulation");
      setActiveSimulation(response.data.simulation);
      persistentDemoToast(
        "Airport routing demo started. Gate A's second conveyor stops after 10 seconds.",
        "airport-routing-demo-started",
      );
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || "Unable to start the airport routing demo");
    } finally {
      setAirportDemoLoading(false);
    }
  };

  // Projected motion inherits this sender, so it must differ from the browser's echo filter.
  const startConveyorSpacingDemo = async () => {
    if (activeSimulation?.id || demoStarting) return;
    setSpacingDemoLoading(true);
    try {
      const response = await clientDemoApi.startConveyorSpacing({
        headers: { "X-Sender-ID": uuidv4() },
      });
      setActiveSimulation(response.data);
      persistentDemoToast(
        "Merge demo started. Watch the belt stop as a whole while rollers let following items queue at the merge.",
        "conveyor-spacing-demo-started",
      );
    } catch (error) {
      const axiosError = error as AxiosError<{ message?: string }>;
      toast.error(axiosError.response?.data?.message || "Unable to start the merge demo");
    } finally {
      setSpacingDemoLoading(false);
    }
  };

  return (
    <div className={embedded ? "p-1" : "bg-white dark:bg-gray-800 p-6 rounded-lg shadow border border-gray-200 dark:border-gray-700 max-w-2xl"}>
      <div className={isBottomEmbedded ? "mb-4 flex items-baseline gap-3" : ""}>
        <h2 className={`text-lg font-bold ${embedded ? 'mb-2' : 'mb-4 border-b border-gray-200 dark:border-gray-700 pb-2'} ${isBottomEmbedded ? '!mb-0' : ''}`}>Commands</h2>
        <p className={`text-sm text-gray-500 dark:text-gray-400 ${embedded ? 'mb-4' : 'mb-6'} ${isBottomEmbedded ? '!mb-0' : ''}`}>
          Send operational commands to conveyors and chutes.
        </p>
      </div>

      <form
        onSubmit={handleSubmit}
        className={isBottomEmbedded ? "grid grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)_auto] gap-3 items-end" : "flex flex-col gap-4"}
      >
        <div className={isBottomEmbedded ? "min-w-0" : ""}>
          <label className="block text-sm font-medium mb-1 text-gray-600 dark:text-gray-400">Action</label>
          <select
            value={action}
            onChange={(e) => setAction(e.target.value as CommandAction)}
            className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none"
          >
            {COMMAND_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        <div className={isBottomEmbedded ? "min-w-0" : ""}>
          <label className="block text-sm font-medium mb-1 text-gray-600 dark:text-gray-400">Target ID</label>
          <input
            type="text"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            className="w-full p-2 rounded border bg-gray-50 dark:bg-gray-900 border-gray-300 dark:border-gray-600 focus:ring-2 focus:ring-blue-500 outline-none"
            placeholder="e.g. conveyor-01"
            required
          />
        </div>

        <button
          type="submit"
          disabled={loading}
          className={`${isBottomEmbedded ? "px-4 py-2 whitespace-nowrap" : "mt-2 w-full py-2"} bg-blue-600 hover:bg-blue-700 text-white font-bold rounded transition-colors disabled:opacity-50`}
        >
          {loading ? "Sending..." : "Send Command"}
        </button>
      </form>

      <div className="mt-5 border-t border-gray-200 pt-4 dark:border-gray-700">
        <h3 className="text-sm font-semibold text-gray-800 dark:text-gray-100">Rollers and belt merge</h3>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
          Opens an isolated simulation with two feeders and a slow shared belt. At the merge, the belt stops all its items together; rollers stop individual items while followers continue to queue. Flow resumes as space opens.
        </p>
        <button
          type="button"
          onClick={() => void startConveyorSpacingDemo()}
          disabled={demoStarting || Boolean(activeSimulation?.id)}
          className="mt-3 rounded bg-teal-600 px-3 py-2 text-sm font-bold text-white transition-colors hover:bg-teal-700 disabled:opacity-50"
        >
          {spacingDemoLoading ? "Starting merge demo..." : "Run rollers and belt merge"}
        </button>
        <h3 className="mt-4 border-t border-gray-200 pt-4 text-sm font-semibold text-gray-800 dark:border-gray-700 dark:text-gray-100">Client demo</h3>
        <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
          Opens an isolated simulation, generates one minute of reduced flow, and keeps the result open until you exit What If. The turbulence alarm appears after about 10 seconds.
        </p>
        <button
          type="button"
          onClick={() => void startClientDemo()}
          disabled={demoStarting || Boolean(activeSimulation?.id)}
          className="mt-3 rounded bg-violet-600 px-3 py-2 text-sm font-bold text-white transition-colors hover:bg-violet-700 disabled:opacity-50"
        >
          {demoLoading ? "Starting demo..." : "Run 1-minute client demo"}
        </button>
        <div className="mt-4 border-t border-gray-200 pt-4 dark:border-gray-700">
          <h3 className="text-sm font-semibold text-gray-800 dark:text-gray-100">Airport routing demo</h3>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">
            Bags dwell at induction, then cross two conveyors per exit path. After 10 seconds Gate A's second conveyor stops; flexible bags reroute to Gate B while Gate-A-only bags recirculate.
          </p>
          <button
            type="button"
            onClick={() => void startAirportRoutingDemo()}
            disabled={demoStarting || Boolean(activeSimulation?.id)}
            className="mt-3 rounded bg-sky-600 px-3 py-2 text-sm font-bold text-white transition-colors hover:bg-sky-700 disabled:opacity-50"
          >
            {airportDemoLoading ? "Starting airport demo..." : "Run airport routing demo"}
          </button>
        </div>
      </div>
    </div>
  );
};
