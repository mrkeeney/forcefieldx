// REALSPACE MOLECULAR DYNAMICS

// Apache Imports
import org.apache.commons.io.FilenameUtils;

// Groovy Imports
import groovy.util.CliBuilder;

// Force Field X Imports
import ffx.algorithms.MolecularDynamics;
import ffx.algorithms.Thermostat.Thermostats;
import ffx.xray.CrystalReciprocalSpace.SolventModel;
import ffx.xray.RealSpaceData;
import ffx.xray.RealSpaceFile;
import ffx.xray.RefinementEnergy;
import ffx.xray.RefinementMinimize.RefinementMode;

// Number of molecular dynamics steps
int nSteps = 1000000;

// Time step in femtoseconds.
double timeStep = 1.0;

// Frequency to print out thermodynamics information in picoseconds.
double printInterval = 0.01;

// Frequency to save out coordinates in picoseconds.
double saveInterval = 0.1;

// Temperature in degrees Kelvin.
double temperature = 100.0;

// Thermostats [ ADIABATIC, BERENDSEN, BUSSI ]
Thermostats thermostat = Thermostats.BERENDSEN;

// Reset velocities (ignored if a restart file is given)
boolean initVelocities = true;

// Things below this line normally do not need to be changed.
// ===============================================================================================

// Create the command line parser.
def cli = new CliBuilder(usage:' ffxc realspace.md [options] <pdbfilename> [datafilename]');
cli.h(longOpt:'help', 'Print this help message.');
cli.d(longOpt:'data', args:2, valueSeparator:',', argName:'data.map,1.0', 'specify input data filename (or simply provide the datafilename argument after the PDB file) and weight to apply to the data (wA)');
cli.p(longOpt:'polarization', args:1, argName:'mutual', 'polarization model: [none / direct / mutual]');
cli.n(longOpt:'steps', args:1, argName:'1000000', 'Number of molecular dynamics steps.');
cli.f(longOpt:'dt', args:1, argName:'1.0', 'Time step in femtoseconds.');
cli.i(longOpt:'print', args:1, argName:'0.01', 'Interval to print out thermodyanamics in picoseconds.');
cli.w(longOpt:'save', args:1, argName:'0.1', 'Interval to write out coordinates in picoseconds.');
cli.t(longOpt:'temperature', args:1, argName:'100.0', 'Temperature in degrees Kelvin.');
cli.b(longOpt:'thermostat', args:1, argName:'Berendsen', 'Thermostat: [Adiabatic/Berendsen/Bussi]')
def options = cli.parse(args);
List<String> arguments = options.arguments();
if (options.h || arguments == null || arguments.size() < 1) {
    return cli.usage();
}

// Name of the file (PDB or XYZ).
String modelfilename = arguments.get(0);

// set up real space map data (can be multiple files)
List mapfiles = new ArrayList();
if (arguments.size() > 1) {
    RealSpaceFile realspacefile = new RealSpaceFile(arguments.get(1), 1.0);
    mapfiles.add(realspacefile);
}
if (options.d) {
    for (int i=0; i<options.ds.size(); i+=2) {
	double wA = Double.parseDouble(options.ds[i+1]);
	RealSpaceFile realspacefile = new RealSpaceFile(options.ds[i], wA);
	mapfiles.add(realspacefile);
    }
}

if (options.p) {
    System.setProperty("polarization", options.p);
}

// Load the number of molecular dynamics steps.
if (options.n) {
    nSteps = Integer.parseInt(options.n);
}

// Load the time steps in femtoseconds.
if (options.f) {
    timeStep = Double.parseDouble(options.f);
}

// Print interval in picoseconds.
if (options.i) {
    printInterval = Double.parseDouble(options.i);
}

// Write interval in picoseconds.
if (options.w) {
    saveInterval = Double.parseDouble(options.w);
}

// Temperature in degrees Kelvin.
if (options.t) {
    temperature = Double.parseDouble(options.t);
}

// Thermostat.
if (options.b) {
    try {
        thermostat = Thermostats.valueOf(options.b.toUpperCase());
    } catch (Exception e) {
        thermostat = Thermostats.BERENDSEN;
    }
}


logger.info("\n Running molecular dynmaics on " + modelfilename);
open(modelfilename);

if (mapfiles.size() == 0) {
    RealSpaceFile realspacefile = new RealSpaceFile(active, 1.0);
    mapfiles.add(realspacefile);
}

RealSpaceData realspacedata = new RealSpaceData(active, active.getProperties(), mapfiles.toArray(new RealSpaceFile[mapfiles.size()]));

energy();

RefinementEnergy refinementEnergy = new RefinementEnergy(realspacedata, RefinementMode.COORDINATES);

// Restart File
File dyn = new File(FilenameUtils.removeExtension(modelfilename) + ".dyn");
if (!dyn.exists()) {
    dyn = null;
}
MolecularDynamics molDyn = new MolecularDynamics(active, refinementEnergy, active.getProperties(), refinementEnergy, thermostat);
refinementEnergy.setThermostat(molDyn.getThermostat());

molDyn.dynamic(nSteps, timeStep, printInterval, saveInterval, temperature, initVelocities, dyn);
