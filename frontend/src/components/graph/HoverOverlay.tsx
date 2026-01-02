import React, { useEffect, useState } from 'react';

interface HoverOverlayProps {
    position: { x: number, y: number };
    onCancel: () => void;
    onLock: () => void;
}

export const HoverOverlay: React.FC<HoverOverlayProps> = ({ position, onCancel, onLock }) => {
    const [progress, setProgress] = useState(0);
    // We use a ref to ensure we don't call onLock multiple times
    const hasLockedRef = React.useRef(false);

    // 1. Handle the Timer (Pure State Update)
    useEffect(() => {
        const duration = 600; 
        const interval = 10;
        const step = 100 / (duration / interval);

        const timer = setInterval(() => {
            setProgress((prev) => {
                if (prev >= 100) {
                    return 100;
                }
                return prev + step;
            });
        }, interval);

        return () => clearInterval(timer);
    }, []);

    // 2. Handle the Side Effect (Locking)
    useEffect(() => {
        if (progress >= 100 && !hasLockedRef.current) {
            hasLockedRef.current = true;
            // This is now safe because it happens in an Effect, 
            // after the render cycle is complete.
            onLock(); 
        }
    }, [progress, onLock]);

    return (
        <div 
            style={{ 
                position: 'fixed', 
                left: position.x, 
                top: position.y, 
                transform: 'translate(-50%, -50%)',
                zIndex: 2000,
                pointerEvents: 'none'
            }}
        >
            <div style={{ width: 60, height: 60, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <svg width="60" height="60" viewBox="0 0 60 60">
                    <circle cx="30" cy="30" r="26" fill="rgba(0,0,0,0.6)" stroke="#333" strokeWidth="2" />
                    <circle 
                        cx="30" cy="30" r="26" 
                        fill="none" 
                        stroke="#28a745" 
                        strokeWidth="4"
                        strokeDasharray="163"
                        strokeDashoffset={163 - (163 * progress) / 100}
                        transform="rotate(-90 30 30)"
                    />
                </svg>
            </div>
        </div>
    );
};