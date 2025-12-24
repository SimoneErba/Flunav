import { useState, useRef, useEffect } from "react";

export const useSimulationClock = (initialTimeISO: string | undefined, speed: number, isPaused: boolean) => {
    // 1. Initialize State (Runs once on mount)
    const [simTime, setSimTime] = useState<number>(() => 
        initialTimeISO ? new Date(initialTimeISO).getTime() : Date.now()
    );
    
    const lastFrameTime = useRef<number>(Date.now());

    // 2. FIX: Sync internal state when the prop changes
    // This handles switching from Live -> Sim, or Sim A -> Sim B
    useEffect(() => {
        const targetTime = initialTimeISO ? new Date(initialTimeISO).getTime() : Date.now();
        setSimTime(targetTime);
        
        // Reset the frame timer to prevent a huge "jump" in the next animation frame
        lastFrameTime.current = Date.now();
    }, [initialTimeISO]);

    // 3. Animation Loop
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