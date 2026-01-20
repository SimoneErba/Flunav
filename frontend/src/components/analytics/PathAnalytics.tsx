import { useState, useEffect } from 'react';
import {
    LineChart,
    Line,
    XAxis,
    YAxis,
    CartesianGrid,
    Tooltip,
    Legend,
    ResponsiveContainer
} from 'recharts';

interface PathAnalyticsData {
    timestamp: string;
    movementsCount: number;
    itemsEntered: number;
    itemsExited: number;
}

interface PathAnalyticsProps {
    className?: string;
}

export const PathAnalytics = ({ className = '' }: PathAnalyticsProps) => {
    const [data, setData] = useState<PathAnalyticsData[]>([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        // TODO: Fetch from backend analytics API
        // Mock data for now
        const mockData: PathAnalyticsData[] = [
            { timestamp: '2026-01-18 10:00', movementsCount: 15, itemsEntered: 3, itemsExited: 2 },
            { timestamp: '2026-01-18 11:00', movementsCount: 22, itemsEntered: 5, itemsExited: 3 },
            { timestamp: '2026-01-18 12:00', movementsCount: 18, itemsEntered: 4, itemsExited: 4 },
            { timestamp: '2026-01-18 13:00', movementsCount: 28, itemsEntered: 6, itemsExited: 5 },
            { timestamp: '2026-01-18 14:00', movementsCount: 35, itemsEntered: 8, itemsExited: 6 },
            { timestamp: '2026-01-18 15:00', movementsCount: 42, itemsEntered: 10, itemsExited: 7 },
        ];
        setData(mockData);
        setLoading(false);
    }, []);

    if (loading) {
        return <div className={`p-4 bg-white dark:bg-gray-800 rounded-lg shadow ${className}`}>Loading analytics...</div>;
    }

    return (
        <div className={`p-4 bg-white dark:bg-gray-800 rounded-lg shadow ${className}`}>
            <h2 className="text-xl font-bold mb-4 dark:text-white">Path Analytics</h2>
            <ResponsiveContainer width="100%" height={300}>
                <LineChart data={data}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis
                        dataKey="timestamp"
                        stroke="#8884d8"
                        tick={{ fill: 'transparent', stroke: '#8884d8', strokeWidth: 1 }}
                        tickFormatter={(value) => new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                    />
                    <YAxis stroke="#8884d8" />
                    <Tooltip
                        labelFormatter={(value) => new Date(value).toLocaleString()}
                        formatter={(value, name, props) => {
                            if (name === 'movementsCount') return [`Movements: ${value}`];
                            if (name === 'itemsEntered') return [`Items Entered: ${value}`];
                            if (name === 'itemsExited') return [`Items Exited: ${value}`];
                            return [name, value];
                        }}
                    />
                    <Legend />
                    <Line
                        type="monotone"
                        dataKey="movementsCount"
                        stroke="#3b82f6"
                        strokeWidth={2}
                        dot={false}
                        name="Movements (PATH_TRAVERSED)"
                    />
                    <Line
                        type="monotone"
                        dataKey="itemsEntered"
                        stroke="#10b981"
                        strokeWidth={2}
                        dot={false}
                        name="Items Entered (ITEM_CREATED)"
                    />
                    <Line
                        type="monotone"
                        dataKey="itemsExited"
                        stroke="#f43f5e"
                        strokeWidth={2}
                        dot={false}
                        name="Items Exited (ITEM_DELETED)"
                    />
                </LineChart>
            </ResponsiveContainer>
        </div>
    );
};
