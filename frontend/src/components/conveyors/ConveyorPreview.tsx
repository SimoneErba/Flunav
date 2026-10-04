import type { ConveyorPreset } from '../../api-client';

export const ConveyorPreview = ({ type }: { type: ConveyorPreset['type'] }) => (
  <svg viewBox="0 0 240 64" aria-hidden="true" className="h-16 w-full text-gray-400 dark:text-gray-500">
    <path d="M30 48v10m180-10v10" stroke="currentColor" strokeWidth="3" />
    <rect x="16" y="14" width="208" height="34" rx="5" fill="none" stroke="currentColor" strokeWidth="2" />
    {type === 'ROLLER' ? Array.from({ length: 13 }, (_, index) => <rect key={index} x={24 + index * 15} y="20" width="7" height="22" rx="3" fill="currentColor" opacity=".55" />)
      : <><path d="M26 22h188M26 40h188" stroke="currentColor" strokeWidth="2" />
        {Array.from({ length: 12 }, (_, index) => <path key={index} d={`M${31 + index * 16} 24l-5 14`} stroke="currentColor" opacity=".35" />)}</>}
    <rect x="101" y="8" width="34" height="22" rx="2" className="fill-blue-100 stroke-blue-500 dark:fill-blue-950 dark:stroke-blue-400" strokeWidth="1.5" />
    <path d="M163 8h32m-6-5l6 5-6 5" className="stroke-blue-500 dark:stroke-blue-400" fill="none" strokeWidth="2" />
    {type === 'STAGING' && <path d="M149 15v28" className="stroke-amber-500" strokeWidth="4" />}
    {type === 'CHUTE' && <path d="M37 25l158 13" stroke="currentColor" strokeWidth="2" />}
  </svg>
);
