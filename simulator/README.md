# Installation
 
remeber to "mvn clean install" on the commons

mvn exec:java -Dexec.mainClass="fiumen.simulator.App"


SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="fiumen.simulator.App" -Dexec.args="line"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="fiumen.simulator.App" -Dexec.args="line"

SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="fiumen.simulator.App" -Dexec.args="loop"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="fiumen.simulator.App" -Dexec.args="loop"

SIMULATION_MODE="rabbit" mvn exec:java -Dexec.mainClass="fiumen.simulator.App" -Dexec.args="multi"
SIMULATION_MODE="rabbit" SIMULATION_ACTION=destroy mvn exec:java -Dexec.mainClass="fiumen.simulator.App" -Dexec.args="multi"

