import { useState, useRef, useEffect } from "react";

export const useSimulationClock = (initialTimeISO: string | undefined, speed: number, isPaused: boolean) => {
    const [simTime, setSimTime] = useState<number>(() => 
        initialTimeISO ? new Date(initialTimeISO).getTime() : Date.now()
    );
    
    const lastFrameTime = useRef<number>(Date.now());

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