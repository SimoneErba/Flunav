package com.flumen.backend.services;

import com.flumen.backend.models.response.ItemResponse;
import com.flumen.backend.models.Location;
import com.flumen.backend.entities.ConnectedTo;
import com.flumen.backend.models.graph.GraphData;
import com.flumen.backend.models.response.ConnectionResponse;
import com.flumen.backend.models.response.LocationResponse;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.record.OVertex;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GraphService {
    private final OrientDBService orientDBService;
    private final ModelMapper modelMapper;

    public GraphService(OrientDBService orientDBService, ClickHouseService clickHouseService, @Lazy EventProcessor eventProcessor) {
        this.orientDBService = orientDBService;
        this.modelMapper = new ModelMapper();
        modelMapper.getConfiguration().setMatchingStrategy(MatchingStrategies.STRICT);
        
        // Configure custom mappings if needed
        modelMapper.createTypeMap(Location.class, LocationResponse.class);
        modelMapper.createTypeMap(ConnectedTo.class, ConnectionResponse.class);
    }

    public GraphData getGraphData() {
        GraphData graphData = new GraphData();
        List<LocationResponse> locations = new ArrayList<>();
        
        try (ODatabaseSession session = orientDBService.getSession()) {
            String query = "SELECT *, in('HasPosition'):{*, @rid} as items, in('ConnectedTo'):{@rid, *} as in, out('ConnectedTo'):{*, @rid} as out FROM Location";
            
            try (OResultSet rs = session.query(query)) {
                while (rs.hasNext()) {
                    OVertex result = rs.next().toElement().asVertex().get();
                    LocationResponse location = new LocationResponse();
                    location.setId(result.getProperty("customId"));
                    location.setName(result.getProperty("name"));
                    location.setLatitude(result.getProperty("latitude"));
                    location.setLongitude(result.getProperty("longitude"));
                    location.setLength(((Number) result.getProperty("length")).doubleValue());
                    location.setSpeed(((Number) result.getProperty("speed")).doubleValue());
                    location.setActive(result.getProperty("active"));
                    
                    location.setProperties(result.getProperty("properties"));
                    
                    // Process items
                    List<ODocument> itemResults = result.getProperty("items");
                    if (itemResults != null) {
                        List<ItemResponse> locationItems = itemResults.stream()
                            .map(itemResult -> {
                                ItemResponse item = new ItemResponse();
                                item.setId(itemResult.field("customId"));
                                item.setName(itemResult.field("name"));
                                item.setSpeed(itemResult.field("speed"));
                                item.setActive(itemResult.field("active"));
                                item.setProperties(itemResult.field("properties"));
                                return item;
                            })
                            .collect(Collectors.toList());
                        location.setItems(locationItems);
                    }
                    
                    List<ConnectionResponse> locationConnections = new ArrayList<>();
                    
                    // Process outgoing connections
                    List<ODocument> outResults = result.getProperty("out");
                    if (outResults != null) {
                        outResults.forEach(outResult -> {
                            ConnectionResponse conn = new ConnectionResponse();
                            conn.setSourceId(location.getId());
                            conn.setTargetId(outResult.field("customId"));
                            conn.setDirection("out");
                        
                            conn.setProperties(outResult.field("properties"));
  
                            locationConnections.add(conn);
                        });
                    }
                    
                    // Process incoming connections
                    List<ODocument> inResults = result.getProperty("in");
                    if (inResults != null) {
                        inResults.forEach(inResult -> {
                            ConnectionResponse conn = new ConnectionResponse();
                            conn.setSourceId(inResult.field("customId"));
                            conn.setTargetId(location.getId());
                            conn.setDirection("in");

                            conn.setProperties(inResult.field("properties"));
                            locationConnections.add(conn);
                        });
                    }
                    
                    location.setConnections(locationConnections);
                    locations.add(location);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Error fetching graph data", e);
        }
        
        // Convert locations to responses using ModelMapper
        List<LocationResponse> locationResponses = locations.stream()
            .map(location -> modelMapper.map(location, LocationResponse.class))
            .collect(Collectors.toList());
        graphData.setLocations(locationResponses);
        
        // Collect all connections and convert using ModelMapper
        List<ConnectionResponse> allConnections = locations.stream()
            .flatMap(location -> location.getConnections().stream())
            .filter(connection -> connection.getDirection().equals("in"))
            .map(connection -> {
                ConnectionResponse response = modelMapper.map(connection, ConnectionResponse.class);
                // Ensure IDs are properly set and not Optional.empty
                response.setSourceId(connection.getSourceId());
                response.setTargetId(connection.getTargetId());
                return response;
            })
            .collect(Collectors.toList());
        graphData.setConnections(allConnections);
        
        return graphData;
    }
}