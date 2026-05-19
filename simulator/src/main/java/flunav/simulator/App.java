package flunav.simulator;

import java.util.logging.Level;

public class App {

    public static void main(String[] args) {
        if (args.length == 0) {
            SimulatorUtils.logger.severe("Please specify a simulation to run. Usage: java App <line|loop|multi|large|perf>");
            return;
        }

        try {
            if (SimulatorUtils.MODE.equalsIgnoreCase("rabbit")) {
                SimulatorUtils.setupRabbit();
            }

            Simulation simulation;
            String simulationType = args[0].toLowerCase();

            switch (simulationType) {
                case "line":
                    SimulatorUtils.logger.info("--- Selected: LINE Simulation ---");
                    simulation = new LineSimulation();
                    break;
                case "loop":
                    SimulatorUtils.logger.info("--- Selected: CONVEYOR LOOP Simulation ---");
                    simulation = new ConveyorLoopSimulation();
                    break;
                case "multi":
                    SimulatorUtils.logger.info("--- Selected: MULTI-PATH SORTING Simulation ---");
                    simulation = new MultiPath();
                    break;
                case "large":
                    SimulatorUtils.logger.info("--- Selected: LARGE SCALE REALISTIC Simulation ---");
                    simulation = new LargeLoopSimulation();
                    break;
                case "jump":
                    SimulatorUtils.logger.info("--- Selected: ITEM JUMP / POSITION UPDATE Simulation ---");
                    simulation = new PositionUpdateSimulation();
                    break;
                case "perf":
                case "performance":
                case "stress":
                    SimulatorUtils.logger.info("--- Selected: PERFORMANCE STRESS Simulation ---");
                    SimulatorUtils.quietEventLogs = true;
                    simulation = new PerformanceStressSimulation();
                    break;
                default:
                    SimulatorUtils.logger.severe("Unknown simulation type: " + simulationType);
                    return;
            }

            if ("destroy".equalsIgnoreCase(SimulatorUtils.ACTION)) {
                SimulatorUtils.logger.info(">>> DESTROY MODE ACTIVATED <<<");
                simulation.destroy();
                SimulatorUtils.logger.info(">>> Destruction Complete. Exiting. <<<");
            } else {
                SimulatorUtils.logger.info(">>> SETUP & RUN MODE <<<");
                simulation.setup();
                simulation.run();
            }
        } catch (Exception e) {
            SimulatorUtils.logger.log(Level.SEVERE, "A critical error occurred in the simulation", e);
        } finally {
            if ("destroy".equalsIgnoreCase(SimulatorUtils.ACTION)) {
                System.exit(0);
            }
        }
    }
}
