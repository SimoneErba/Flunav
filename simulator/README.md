# Installation
 
remeber to "mvn clean install" on the commons

mvn exec:java -Dexec.mainClass="flunav.simulator.App"


SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="line"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="line"

SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="loop"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="loop"

SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="multi"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="multi"


SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="large"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="large"

SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="stress"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="stress"

SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="future"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="flunav.simulator.App" -Dexec.args="future"

Aliases: future, long, future-long
