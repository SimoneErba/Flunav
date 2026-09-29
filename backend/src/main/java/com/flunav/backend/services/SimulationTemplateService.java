package com.flunav.backend.services;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.flunav.backend.models.scenario.ScenarioDocument;
import com.flunav.backend.models.scenario.SavedScenario;
import com.flunav.backend.models.response.SimulationStateResponse;

@Service
public class SimulationTemplateService {
    private static final List<String> IDS = List.of("simple-line", "merge-bottleneck", "sorting-loop", "failure-recovery");
    private final ScenarioService scenarios;
    public SimulationTemplateService(ScenarioService scenarios) { this.scenarios = scenarios; }
    public List<ScenarioDocument> list() {
        var templates = new java.util.ArrayList<>(IDS.stream().map(this::get).toList());
        scenarios.list().stream().filter(SavedScenario::reusableTemplate).forEach(saved -> templates.add(saved.document()));
        return List.copyOf(templates);
    }
    public ScenarioDocument get(String id) {
        if (id.startsWith("custom:")) {
            var saved = scenarios.get(id.substring("custom:".length()), null);
            if (!saved.reusableTemplate()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
            return scenarios.validate(saved.document());
        }
        if (!IDS.contains(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
        try (var input = new ClassPathResource("simulation-templates/" + id + ".json").getInputStream()) {
            return scenarios.parse(input.readAllBytes());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot load template " + id, failure);
        }
    }
    public TemplateInstance instantiate(String id) {
        SavedScenario saved = scenarios.save(get(id), null);
        return new TemplateInstance(saved, new SimulationStateResponse(scenarios.open(saved.id(), saved.revision())));
    }
    public record TemplateInstance(SavedScenario scenario, SimulationStateResponse runtime) {}
}
