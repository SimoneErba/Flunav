package flunav.simulator;

interface Simulation {
    void setup() throws Exception;

    void run() throws Exception;

    void destroy() throws Exception;
}
