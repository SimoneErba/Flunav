import { useState, useEffect, useCallback } from 'react';
import { useApi } from './useApi';
import { useWebSocket, PositionUpdate } from './useWebSocket';
import { GraphData, Location, Item } from "../api-client/api";

const emptyGraphData: GraphData = {
    locations: [],
    connections: []
};

export const useGraph = () => {
    const { simulationApi, graphApi } = useApi();
    const { connected, subscribeToPositionUpdates, subscribeToLocationUpdates } = useWebSocket();
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [graphData, setGraphData] = useState<GraphData>(emptyGraphData);

    const refetchGraphData = useCallback(async (simulationId: string | undefined = undefined) => {
        try {
            console.log("Refetching graph data...");
            setLoading(true);
            let response;
            if (simulationId) {
                response = await simulationApi.getSimulationGraphData(simulationId);
            } else {
                response = await graphApi.getGraphData();
            }
            if (response?.data) {
                setGraphData(response.data);
                setError(null);
            } else {
                throw new Error('Invalid response data');
            }
        } catch (err) {
            setError('Failed to fetch graph data');
            console.error('Error fetching graph data:', err);
            setGraphData(emptyGraphData);
        } finally {
            setLoading(false);
        }
    }, [graphApi]);

    useEffect(() => {
        refetchGraphData();
    }, [refetchGraphData]); 

    // Handle position updates
    useEffect(() => {
        if (!connected) return;

        const handlePositionUpdate = (update: PositionUpdate) => {
            setGraphData((current: GraphData) => {
                // Find the old location of the item
                const updatedLocations = current.locations.map((location: Location) => {
                    const updatedItems = (location.items || []).filter((item: Item) => 
                        item.id !== update.itemId
                    );
                    return {
                        ...location,
                        items: updatedItems
                    };
                });

                // Add item to new location
                if (update.locationId) {
                    const newLocation = updatedLocations.find(loc => loc.id === update.locationId);
                    if (newLocation) {
                        const item = current.locations
                            .flatMap(loc => loc.items || [])
                            .find(item => item.id === update.itemId);
                        
                        if (item) {
                            const locationIndex = updatedLocations.findIndex(loc => loc.id === update.locationId);
                            if (locationIndex !== -1) {
                                updatedLocations[locationIndex] = {
                                    ...newLocation,
                                    items: [...(newLocation.items || []), item]
                                };
                            }
                        }
                    }
                }

                return {
                    ...current,
                    locations: updatedLocations
                };
            });
        };

        const unsubscribe = subscribeToPositionUpdates(handlePositionUpdate);
        return () => unsubscribe();
    }, [connected, subscribeToPositionUpdates]);

    // Handle node updates
    // useEffect(() => {
    //     if (!connected) return;

    //     const handleNodeUpdate = (update: NodeUpdate) => {
    //         setGraphData((current: GraphData) => {
    //             // Update location properties
    //             const updatedLocations = current.locations.map((location: Location) =>
    //                 location.id === update.id
    //                     ? { ...location, ...update.properties }
    //                     : location
    //             );

    //             // Update item properties
    //             const locationsWithUpdatedItems = updatedLocations.map((location: Location) => {
    //                 if (!location.items) return location;

    //                 const updatedItems = location.items.map((item: Item) =>
    //                     item.id === update.id
    //                         ? { ...item, ...update.properties }
    //                         : item
    //                 );

    //                 return {
    //                     ...location,
    //                     items: updatedItems
    //                 };
    //             });

    //             return {
    //                 ...current,
    //                 locations: locationsWithUpdatedItems
    //             };
    //         });
    //     };

    //     const unsubscribe = subscribeToLocationUpdates(handleNodeUpdate);
    //     return () => unsubscribe();
    // }, [connected, subscribeToLocationUpdates]);

    return {
        graphData,
        loading,
        error,
        connected,
        refetchGraphData
    };
}; 