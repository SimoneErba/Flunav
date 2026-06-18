import { useState, useRef, useEffect } from "react";

export const useSimulationClock = (initialTimeISO: string | undefined, speed: number, isPaused: boolean) => {
    // The frontend clock mirrors backend virtual time for rendering only; backend
    // replay remains authoritative for event timestamps and state changes.
    const [simTime, setSimTime] = useState<number>(() => 
        initialTimeISO ? new Date(initialTimeISO).getTime() : Date.now()
    );
    
    const lastFrameTime = useRef<number>(Date.now());

    // Reset the local clock when switching live/simulation views so animation does
    // not carry elapsed time from the previous timeline into the next one.
    useEffect(() => {
        const targetTime = initialTimeISO ? new Date(initialTimeISO).getTime() : Date.now();
        setSimTime(targetTime);
        
        // Reset the frame timer to prevent a huge "jump" in the next animation frame
        lastFrameTime.current = Date.now();
    }, [initialTimeISO]);

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

        // Reset frame time on mount or when speed/pause changes
        lastFrameTime.current = Date.now();
        frameId = requestAnimationFrame(loop);

        return () => cancelAnimationFrame(frameId);
    }, [speed, isPaused]);

    return simTime;
};
