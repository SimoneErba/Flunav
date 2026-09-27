import { useCallback, useLayoutEffect, useRef } from "react";

const resolveAnchorTime = (initialTimeISO: string | undefined) => {
    const parsedTime = initialTimeISO ? new Date(initialTimeISO).getTime() : Number.NaN;
    return Number.isFinite(parsedTime) ? parsedTime : Date.now();
};

export type ClockReader = () => number;

export const useSimulationClock = (
    initialTimeISO: string | undefined,
    speed: number,
    isPaused: boolean,
    timelineId?: string
): ClockReader => {
    const clock = useRef({
        virtualTime: resolveAnchorTime(initialTimeISO),
        physicalTime: Date.now(),
        speed,
        isPaused,
        checkpoint: initialTimeISO,
        timelineId,
    });

    // Sampling the clock does not schedule a React render. Only the playback
    // display owns a timer; Sigma samples this reader on its own animation frame.
    const now = useCallback(() => {
        const state = clock.current;
        return state.virtualTime + (state.isPaused ? 0 : Math.max(0, Date.now() - state.physicalTime) * state.speed);
    }, []);

    useLayoutEffect(() => {
        const state = clock.current;
        const currentTime = now();
        const switchedTimeline = state.timelineId !== timelineId;
        const checkpointChanged = state.checkpoint !== initialTimeISO;
        const targetTime = resolveAnchorTime(initialTimeISO);
        state.virtualTime = switchedTimeline || (checkpointChanged && isPaused)
            ? targetTime
            : checkpointChanged ? Math.max(currentTime, targetTime) : currentTime;
        state.physicalTime = Date.now();
        state.speed = speed;
        state.isPaused = isPaused;
        state.checkpoint = initialTimeISO;
        state.timelineId = timelineId;
    }, [initialTimeISO, speed, isPaused, timelineId, now]);

    return now;
};
