// XRAY SIMULATED ANNEALING

// Apache Imports
import org.apache.commons.io.FilenameUtils;

// Groovy Imports
import groovy.util.CliBuilder;

// Force Field X Imports
import ffx.algorithms.SimulatedAnnealing;
import ffx.xray.CrystalReciprocalSpace.SolventModel;
import ffx.xray.DiffractionData;
import ffx.xray.DiffractionFile;
import ffx.xray.RefinementEnergy;
import ffx.xray.RefinementMinimize.RefinementMode;


// type of refinement
RefinementMode refinementmode = RefinementMode.COORDINATES;

// suffix to append to output data
String suffix = "_anneal";

// starting temp
double high = 1000.0;

// ending temp
double low = 100.0;

// Number of annealing steps.
int windows = 10;

// Number of molecular dynamics steps at each temperature.
int steps = 1000;

// Reset velocities (ignored if a restart file is given)
boolean initVelocities = true;


// Things below this line normally do not need to be changed.
// ===============================================================================================

// Create the command line parser.
def cli = new CliBuilder(usage:' ffxc xray.anneal [options] <pdbfilename> [datafilename]');
cli.h(longOpt:'help', 'Print this help message.');
cli.d(longOpt:'data', args:3, valueSeparator:',', argName:'data.mtz,1.0,false', 'specify input data filename (or simply provide the datafilename argument after the PDB file), weight applied to the data (wA) and if the data is from a neutron experiment');
cli.r(longOpt:'mode', args:1, argName:'coordinates', 'type of refinement: [coordinates / bfactors / coordinates_and_bfactors / occupancies / bfactors_and_occupancies / coordinates_and_occupancies / coordinates_and_bfactors_and_occupancies]');
cli.p(longOpt:'polarization', args:1, argName:'mutual', 'polarization model: [none / direct / mutual]');
cli.s(longOpt:'suffix', args:1, argName:'_anneal', 'output suffix');
cli.l(longOpt:'low', args:1, argName:'100.0', 'Low temperature limit in degrees Kelvin.');
cli.t(longOpt:'high', args:1, argName:'1000.0', 'High temperature limit in degrees Kelvin.');
cli.w(longOpt:'windows', args:1, argName:'10', 'Number of annealing windows.');
cli.n(longOpt:'steps', args:1, argName:'1000', 'Number of molecular dynamics steps at each temperature.');
def options = cli.parse(args);
List<String> arguments = options.arguments();
if (options.h || arguments == null || arguments.size() < 1) {
    return cli.usage();
}

// Name of the file (PDB or XYZ).
String modelfilename = arguments.get(0);

// set up diffraction data (can be multiple files)
List diffractionfiles = new ArrayList();
if (arguments.size() > 1) {
    DiffractionFile diffractionfile = new DiffractionFile(arguments.get(1), 1.0, false);
    diffractionfiles.add(diffractionfile);
}
if (options.d) {
    for (int i=0; i<options.ds.size(); i+=3) {
	double wA = Double.parseDouble(options.ds[i+1]);
	boolean neutron = Boolean.parseBoolean(options.ds[i+2]);
	DiffractionFile diffractionfile = new DiffractionFile(options.ds[i], wA, neutron);
	diffractionfiles.add(diffractionfile);
    }
}

if (options.r) {
    try {
	refinementmode = RefinementMode.valueOf(options.r.toUpperCase());
    } catch (Exception e) {
	refinementmode = RefinementMode.COORDINATES;
    }
}

if (options.p) {
    System.setProperty("polarization", options.p);
}

if (options.s) {
    suffix = options.s;
}

if (options.t) {
    high = Double.parseDouble(options.t);
}

if (options.l) {
    low = Double.parseDouble(options.l);
}

if (options.w) {
    windows = Integer.parseInteger(options.w);
}

if (options.n) {
    steps = Integer.parseInteger(options.n);
}

logger.info("\n Running simulated annealing on " + modelfilename);
open(modelfilename);

if (diffractionfiles.size() == 0) {
    DiffractionFile diffractionfile = new DiffractionFile(active, 1.0, false);
    diffractionfiles.add(diffractionfile);
}

DiffractionData diffractiondata = new DiffractionData(active, active.getProperties(), SolventModel.POLYNOMIAL, diffractionfiles.toArray(new DiffractionFile[diffractionfiles.size()]));

diffractiondata.scaleBulkFit();
diffractiondata.printStats();
energy();

RefinementEnergy refinementEnergy = new RefinementEnergy(diffractiondata, refinementmode);
SimulatedAnnealing simulatedAnnealing = new SimulatedAnnealing(active, refinementEnergy, active.getProperties(), refinementEnergy);
simulatedAnnealing.anneal(high, low, windows, steps);
diffractiondata.scaleBulkFit();
diffractiondata.printStats();
energy();

diffractiondata.writeModel(FilenameUtils.removeExtension(modelfilename) + suffix + ".pdb");
diffractiondata.writeData(FilenameUtils.removeExtension(modelfilename) + suffix + ".mtz");
