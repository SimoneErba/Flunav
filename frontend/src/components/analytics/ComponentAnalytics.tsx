import { useState, useEffect } from 'react';
import {
    BarChart,
    Bar,
    XAxis,
    YAxis,
    CartesianGrid,
    Tooltip,
    Legend,
    ResponsiveContainer
} from 'recharts';

interface ComponentAnalyticsData {
    locationId: string;
    totalItemsPassed: number;
}

interface ComponentAnalyticsProps {
    className?: string;
}

export const ComponentAnalytics = ({ className = '' }: ComponentAnalyticsProps) => {
    const [data, setData] = useState<ComponentAnalyticsData[]>([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        // TODO: Fetch from backend analytics API
        // Mock data for now
        const mockData: ComponentAnalyticsData[] = [
            { locationId: 'LOC-001', totalItemsPassed: 125 },
            { locationId: 'LOC-002', totalItemsPassed: 89 },
            { locationId: 'LOC-003', totalItemsPassed: 156 },
            { locationId: 'LOC-004', totalItemsPassed: 67 },
            { locationId: 'LOC-005', totalItemsPassed: 203 },
            { locationId: 'LOC-006', totalItemsPassed: 142 },
            { locationId: 'LOC-007', totalItemsPassed: 98 },
            { locationId: 'LOC-008', totalItemsPassed: 175 },
            { locationId: 'LOC-009', totalItemsPassed: 134 },
            { locationId: 'LOC-010', totalItemsPassed: 189 },
        ];
        setData(mockData);
        setLoading(false);
    }, []);

    if (loading) {
        return <div className={`p-4 bg-white dark:bg-gray-800 rounded-lg shadow ${className}`}>Loading component analytics...</div>;
    }

    return (
        <div className={`p-4 bg-white dark:bg-gray-800 rounded-lg shadow ${className}`}>
            <h2 className="text-xl font-bold mb-4 dark:text-white">Component Analytics</h2>
            <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
                Total items passing through each location (from PATH_TRAVERSED events)
            </p>
            <ResponsiveContainer width="100%" height={400}>
                <BarChart data={data}>
                    <CartesianGrid strokeDasharray="3 3" />
                    <XAxis
                        dataKey="locationId"
                        stroke="#8884d8"
                        tick={{ fill: 'transparent', stroke: '#8884d8', strokeWidth: 1 }}
                    />
                    <YAxis stroke="#8884d8" />
                    <Tooltip
                        cursor={{ fill: '#3b82f6', fillOpacity: 0.8 }}
                        formatter={(value, name) => {
                            return [`Location: ${name}`, `Items: ${value}`];
                        }}
                    />
                    <Legend />
                    <Bar
                        dataKey="totalItemsPassed"
                        fill="#3b82f6"
                        name="Items Passed"
                    />
                </BarChart>
            </ResponsiveContainer>
        </div>
    );
};
