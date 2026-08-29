import type { ReactNode } from 'react';

export const WidgetFrame = ({ title, children, className = '' }: { title: string; children: ReactNode; className?: string }) => (
  <section className={`min-w-0 rounded-lg border border-gray-200 bg-white p-4 shadow-sm dark:border-gray-800 dark:bg-gray-900 ${className}`}>
    <h3 className="mb-3 text-sm font-semibold text-gray-900 dark:text-white">{title}</h3>
    {children}
  </section>
);

export const EmptyWidget = ({ title, message }: { title: string; message: string }) => (
  <WidgetFrame title={title}>
    <p className="text-sm text-gray-500 dark:text-gray-400">{message}</p>
  </WidgetFrame>
);
