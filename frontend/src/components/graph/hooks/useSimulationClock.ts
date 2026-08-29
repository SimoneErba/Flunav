import { useState, useRef, useEffect } from "react";

const resolveAnchorTime = (initialTimeISO: string | undefined) => {
    const parsedTime = initialTimeISO ? new Date(initialTimeISO).getTime() : Number.NaN;
    return Number.isFinite(parsedTime) ? parsedTime : Date.now();
};

export const useSimulationClock = (
    initialTimeISO: string | undefined,
    speed: number,
    isPaused: boolean,
    timelineId?: string
) => {
    // The frontend clock mirrors backend virtual time for rendering only; backend
    // replay remains authoritative for event timestamps and state changes.
    const [simTime, setSimTime] = useState<number>(() => resolveAnchorTime(initialTimeISO));
    
    const lastFrameTime = useRef<number>(Date.now());
    const activeTimelineId = useRef<string | undefined>(timelineId);

    /**
     * Reconciles backend checkpoints without rewinding an actively playing clock.
     * Checkpoints describe when the backend sent an update, so resetting to them
     * after transport delay would repeatedly move accelerated playback backward.
     */
    useEffect(() => {
        const targetTime = resolveAnchorTime(initialTimeISO);
        const switchedTimeline = activeTimelineId.current !== timelineId;
        activeTimelineId.current = timelineId;

        setSimTime(currentTime => {
            if (switchedTimeline || isPaused) return targetTime;
            return Math.max(currentTime, targetTime);
        });

        lastFrameTime.current = Date.now();
    }, [initialTimeISO, isPaused, timelineId]);

    // Advance virtual time by real frame delta scaled by playback speed; pause
    // freezes rendering without changing the backend simulation timestamp.
    useEffect(() => {
        let frameId: number;

        const loop = () => {
            const now = Date.now();
            const deltaReal = now - lastFrameTime.current;
            
            if (!isPaused) {
                setSimTime(prev => prev + (deltaReal * speed));
            }

            lastFrameTime.current = now;
            frameId = requestAnimationFrame(loop);
        };

        lastFrameTime.current = Date.now();
        frameId = requestAnimationFrame(loop);

        return () => cancelAnimationFrame(frameId);
    }, [speed, isPaused]);

    return simTime;
};
