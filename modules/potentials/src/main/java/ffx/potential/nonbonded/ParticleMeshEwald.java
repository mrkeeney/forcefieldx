/**
 * Title: Force Field X
 * Description: Force Field X - Software for Molecular Biophysics.
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2011
 *
 * This file is part of Force Field X.
 *
 * Force Field X is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as published
 * by the Free Software Foundation.
 *
 * Force Field X is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Force Field X; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
 */
package ffx.potential.nonbonded;

import static java.lang.Math.*;
import static java.lang.String.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.rit.pj.IntegerForLoop;
import edu.rit.pj.IntegerSchedule;
import edu.rit.pj.ParallelRegion;
import edu.rit.pj.ParallelSection;
import edu.rit.pj.ParallelTeam;
import edu.rit.pj.reduction.DoubleOp;
import edu.rit.pj.reduction.SharedDouble;
import edu.rit.pj.reduction.SharedDoubleArray;
import edu.rit.pj.reduction.SharedInteger;

import ffx.crystal.Crystal;
import ffx.crystal.SymOp;
import static ffx.numerics.Erf.erfc;
import ffx.numerics.TensorRecursion;
import static ffx.numerics.VectorMath.*;
import ffx.potential.LambdaInterface;
import ffx.potential.bonded.Angle;
import ffx.potential.bonded.Atom;
import ffx.potential.bonded.Bond;
import ffx.potential.bonded.Torsion;
import ffx.potential.parameters.AtomType;
import ffx.potential.parameters.ForceField;
import ffx.potential.parameters.ForceField.ForceFieldBoolean;
import ffx.potential.parameters.ForceField.ForceFieldDouble;
import ffx.potential.parameters.ForceField.ForceFieldString;
import ffx.potential.parameters.ForceField.ForceFieldType;
import ffx.potential.parameters.MultipoleType;
import static ffx.potential.parameters.MultipoleType.*;
import ffx.potential.parameters.PolarizeType;

/**
 * This Particle Mesh Ewald class implements PME for the AMOEBA polarizable
 * mutlipole force field in parallel using a {@link NeighborList} for any
 * {@link Crystal}. The real space contribution is contained within this
 * class, but the reciprocal space contribution is delegated to the
 * {@link ReciprocalSpace} class.
 *
 * @author Michael J. Schnieders<br>
 *         derived from:<br>
 *         TINKER code by Jay Ponder, Pengyu Ren and Tom Darden.<br>
 * @see <a href="http://dx.doi.org/10.1063/1.1630791" target="_blank"> C. Sagui,
 *      L. G. Pedersen, and T. A. Darden, Journal of Chemical Physics 120 (1),
 *      73 (2004)</a><br>
 *      <a href="http://link.aip.org/link/?JCPSA6/98/10089/1" target="_blank">
 *      T. Darden, D. York, and L. Pedersen, Journal of Chemical Physics 98
 *      (12), 10089 (1993)</a><br>
 *      <a href="http://www.ccp5.org" target="_blank"> W. Smith,
 *      "Point Multipoles in the Ewald Summation (Revisited)", CCP5 Newsletter,
 *      46, 18-30, 1998</a><br>
 */
public class ParticleMeshEwald implements LambdaInterface {

    private static final Logger logger = Logger.getLogger(ParticleMeshEwald.class.getName());

    /**
     * Polarization modes include "direct", in which induced dipoles do not
     * interact, and "mutual" that converges the self-consistent field to a
     * tolerance specified by the "polar-eps" keyword.
     */
    public enum Polarization {

        DIRECT, MUTUAL, NONE
    }
    private int interactions;
    private int gkInteractions;
    private double multipoleEnergy;
    private double polarizationEnergy;
    private double gkEnergy;
    private boolean gradient = false;
    /**
     * Reference to the force field being used.
     */
    private final ForceField forceField;
    /**
     * Unit cell and spacegroup information.
     */
    private final Crystal crystal;
    /**
     * Number of symmetry operators.
     */
    private final int nSymm;
    /**
     * An ordered array of atoms in the system.
     */
    private final Atom atoms[];
    /**
     * The number of atoms in the system.
     */
    private final int nAtoms;
    /**
     * Dimensions of [nsymm][3][nAtoms].
     */
    protected final double coordinates[][][];
    /***************************************************************************
     * Neighbor list variables.
     */
    protected final int neighborLists[][][];
    private final int[][][] realSpaceLists;
    private final int[][] ewaldCounts;
    /***************************************************************************
     * State variables.
     */
    /**
     * If lambdaTerm is true, some ligand atom interactions with the
     * environment are being turned on/off.
     */
    private boolean lambdaTerm = false;
    /**
     * Current state.
     */
    private double lambda = 1.0;
    /**
     * The polarization Lambda value goes from 0.0 .. 1.0 as the global
     * lambda value varies between polarizationLambdaStart .. 1.0. 
     */
    private double polarizationLambda = 1.0;
    /**
     * Constant α in:
     * r' = sqrt(r^2 + α*(1 - L)^2) 
     */
    private double permanentLambdaAlpha = 1.0;
    /**
     * Power on L in front of the pairwise multipole potential.
     */
    private double permanentLambdaExponent = 2.0;
    /**
     * Start turning on polarization later in the Lambda path to prevent
     * SCF convergence problems when atoms nearly overlap.
     */
    private double polarizationLambdaStart = 0.5;
    private double polarizationLambdaEnd = 1.0;
    /**
     * Power on L in front of the polarization energy.
     */
    private double polarizationLambdaExponent = 2.0;
    /**
     * lAlpha = α*(1 - L)^2
     */
    private double lAlpha = 0.0;
    private double dlAlpha = 0.0;
    private double d2lAlpha = 0.0;
    private double dEdLSign = 1.0;
    /**
     * lPowPerm = L^permanentLambdaExponent
     */
    private double lPowPerm = 1.0;
    private double dlPowPerm = 0.0;
    private double d2lPowPerm = 0.0;
    private boolean doPermanentRealSpace;
    private double permanentScale = 1.0;
    /**
     * lPowPol = L^polarizationLambdaExponent
     */
    private double lPowPol = 1.0;
    private double dlPowPol = 0.0;
    private double d2lPowPol = 0.0;
    private boolean doPolarization;
    /**
     * When computing the polarization energy at L there are 3 pieces.
     * 1.) Upol(1) = The polarization energy computed normally (ie. system with ligand).
     * 2.) Uenv    = The polarization energy of the system without the ligand.
     * 3.) Uligand = The polarization energy of the ligand by itself.
     * 
     * Upol(L) = L*Upol(1) + (1-L)*(Uenv + Uligand)
     * 
     * Set polarizationScale to L for part 1.
     * Set polarizationScale to (1-L) for parts 2 & 3.
     * 
     */
    private double polarizationScale = 1.0;
    /**
     * Flag for ligand atoms.
     */
    private final boolean isSoft[];
    /**
     * Apply softCore interactions between the ligand and environment.
     */
    private final boolean softCore[][];
    /**
     * When computing the polarization energy at L there are 3 pieces.
     * 1.) Upol(1) = The polarization energy computed normally (ie. system with ligand).
     * 2.) Uenv    = The polarization energy of the system without the ligand.
     * 3.) Uligand = The polarization energy of the ligand by itself.
     * 
     * Upol(L) = L*Upol(1) + (1-L)*(Uenv + Uligand)
     * 
     * Set the "use" array to true for all atoms for part 1.
     * Set the "use" array to true for all atoms except the ligand for part 2.
     * Set the "use" array to true only for the ligand atoms for part 3.
     */
    private final boolean use[];
    private boolean useSymmetry = true;
    /***************************************************************************
     * Permanent multipole variables.
     */
    /**
     * Permanent multipoles in their local frame.
     */
    private final double localMultipole[][];
    private final MultipoleType.MultipoleFrameDefinition frame[];
    private final int axisAtom[][];
    /**
     * Dimensions of [nsymm][nAtoms][10]
     */
    protected final double globalMultipole[][][];
    private final double cartMultipolePhi[][];
    /**
     * The interaction energy between 1-2 multipoles is scaled by m12scale.
     */
    private final double m12scale;
    /**
     * The interaction energy between 1-3 multipoles is scaled by m13scale.
     */
    private final double m13scale;
    /**
     * The interaction energy between 1-4 multipoles is scaled by m14scale.
     */
    private final double m14scale;
    /**
     * The interaction energy between 1-5 multipoles is scaled by m15scale.
     */
    private final double m15scale;
    /***************************************************************************
     * Induced dipole variables.
     */
    /**
     * Polarization mode.
     */
    protected final Polarization polarization;
    private final boolean generalizedKirkwoodTerm;
    private final double polsor;
    private final double poleps;
    /**
     * Direct polarization field due to permanent multipoles at polarizable
     * sites within their group are scaled. The scaling is 0.0 in AMOEBA.
     */
    private final double d11scale;
    /**
     * The interaction energy between a permanent multipole and polarizable site
     * that are 1-2 is scaled by p12scale.
     */
    private final double p12scale;
    /**
     * The interaction energy between a permanent multipole and polarizable site
     * that are 1-3 is scaled by p13scale.
     */
    private final double p13scale;
    private final double pdamp[];
    private final double thole[];
    private final double polarizability[];
    /**
     * Dimensions of [nsymm][nAtoms][3]
     */
    protected final double inducedDipole[][][];
    protected final double inducedDipoleCR[][][];
    private final double directDipole[][];
    private final double directDipoleCR[][];
    private final double cartesianDipolePhi[][];
    private final double cartesianDipolePhiCR[][];
    /**
     * Dimensions of [nsymm][nAtoms][3]
     */
    private final int ip11[][];
    private final int ip12[][];
    private final int ip13[][];
    /***************************************************************************
     * Mutable Particle Mesh Ewald constants.
     */
    private double aewald;
    private double alsq2;
    private double an0;
    private double an1;
    private double an2;
    private double an3;
    private double an4;
    private double an5;
    private double piEwald;
    private double aewald3;
    private double off;
    private double off2;
    /***************************************************************************
     * Parallel variables.
     */
    /**
     * By default, maxThreads is set to the number of available SMP cores.
     */
    private final int maxThreads;
    /**
     * Either 1 or 2; see description below.
     */
    private final int sectionThreads;
    /**
     * If real and reciprocal space are done sequentially or OpenCL is used,
     * then realSpaceThreads == maxThreads.
     * Otherwise the number of realSpaceThreads is set to ffx.realSpaceThreads.
     */
    private final int realSpaceThreads;
    /**
     * If real and reciprocal space are done sequentially then
     * reciprocalThreads == maxThreads
     * If CUDA is used, reciprocalThreads == 1
     * Otherwise, reciprocalThreads = maxThreads - realSpaceThreads
     */
    private final int reciprocalThreads;
    /**
     * The default ParallelTeam encapsulates the maximum number of theads
     * used to parallelize the electrostatics calculation.
     */
    private final ParallelTeam parallelTeam;
    /**
     * The sectionTeam encapsulates 1 or 2 threads.
     *
     * If it contains 1 thread, the real and reciprocal space calculations
     * are done sequentially.
     *
     * If it contains 2 threads, the real and reciprocal space calculations
     * will be done concurrently.
     */
    private final ParallelTeam sectionTeam;
    /**
     * If the real and reciprocal space parts of PME are done sequentially, then
     * the realSpaceTeam is equal parallalTeam.
     *
     * If the real and reciprocal space parts of PME are done concurrently, then
     * the realSpaceTeam will have fewer threads than the default parallelTeam.
     */
    private final ParallelTeam realSpaceTeam;
    /**
     * If the real and reciprocal space parts of PME are done sequentially, then
     * the reciprocalSpaceTeam is equal parallalTeam.
     *
     * If the real and reciprocal space parts of PME are done concurrently, then
     * the reciprocalSpaceTeam will have fewer threads than the default parallelTeam.
     */
    private final ParallelTeam fftTeam;
    private final boolean cudaFFT;
    private final IntegerSchedule pairWiseSchedule;
    private final InitializationRegion initializationRegion;
    private final RotateMultipolesRegion rotateMultipolesRegion;
    private final PermanentFieldRegion permanentFieldRegion;
    private final ExpandInducedDipolesRegion expandInducedDipolesRegion;
    private final InducedDipoleFieldRegion inducedDipoleFieldRegion;
    private final ReciprocalSpace reciprocalSpace;
    private final RealSpaceEnergyRegion realSpaceEnergyRegion;
    private final GeneralizedKirkwood generalizedKirkwood;
    private final ReduceRegion torqueRegion;
    private final SharedDoubleArray sharedGrad[];
    private final SharedDoubleArray sharedTorque[];
    private final SharedDoubleArray sharedPermanentField[];
    private final SharedDoubleArray sharedPermanentFieldCR[];
    private final SharedDoubleArray sharedMutualField[];
    private final SharedDoubleArray sharedMutualFieldCR[];
    private final SharedDouble shareddEdLambda;
    private final SharedDouble sharedd2EdLambda2;
    private final SharedDoubleArray shareddEdLdX[];
    private final SharedDoubleArray shareddEdLTorque[];
    /**
     * Timing variables.
     */
    private long realSpaceTime, reciprocalSpaceTime;
    private long bsplineTime, densityTime, realAndFFTTime, phiTime;
    private long bornTime, gkTime;
    private static double toSeconds = 1.0e-9;
    /**
     * The sqrt of PI.
     */
    private static final double sqrtPi = sqrt(Math.PI);

    /**
     * ParticleMeshEwald constructor.
     *
     * @param forceField The forceField parameters to use.
     *
     * @param atoms An ordered array of Atoms.
     *
     * @param crystal The definition of the unit cell, space group symmetry and,
     *                if necessary, replicates symmetry.
     *
     * @param parallelTeam A ParallelTeam that delegates parallelization.
     *
     * @param neighborLists The NeighborLists for both van der Waals and PME.
     */
    public ParticleMeshEwald(ForceField forceField, Atom[] atoms,
            Crystal crystal, ParallelTeam parallelTeam, int neighborLists[][][]) {
        this.forceField = forceField;
        this.atoms = atoms;
        this.crystal = crystal;
        this.parallelTeam = parallelTeam;
        this.neighborLists = neighborLists;
        nAtoms = atoms.length;
        nSymm = crystal.spaceGroup.getNumberOfSymOps();
        maxThreads = parallelTeam.getThreadCount();

        coordinates = new double[nSymm][3][nAtoms];
        inducedDipole = new double[nSymm][nAtoms][3];
        inducedDipoleCR = new double[nSymm][nAtoms][3];
        double x[] = coordinates[0][0];
        double y[] = coordinates[0][1];
        double z[] = coordinates[0][2];
        for (int i = 0; i < nAtoms; i++) {
            Atom ai = atoms[i];
            double xyz[] = ai.getXYZ();
            x[i] = xyz[0];
            y[i] = xyz[1];
            z[i] = xyz[2];
        }

        /**
         * The size of reduced neighbor list and cache depend on the size of
         * the real space cutoff.
         */
        realSpaceLists = new int[nSymm][nAtoms][];
        ewaldCounts = new int[nSymm][nAtoms];

        polsor = forceField.getDouble(ForceFieldDouble.POLAR_SOR, 0.70);
        poleps = forceField.getDouble(ForceFieldDouble.POLAR_EPS, 1e-6);
        m12scale = forceField.getDouble(ForceFieldDouble.MPOLE_12_SCALE, 0.0);
        m13scale = forceField.getDouble(ForceFieldDouble.MPOLE_13_SCALE, 0.0);
        m14scale = forceField.getDouble(ForceFieldDouble.MPOLE_14_SCALE, 0.4);
        m15scale = forceField.getDouble(ForceFieldDouble.MPOLE_15_SCALE, 0.8);
        d11scale = forceField.getDouble(ForceFieldDouble.DIRECT_11_SCALE, 0.0);
        p12scale = forceField.getDouble(ForceFieldDouble.POLAR_12_SCALE, 0.0);
        p13scale = forceField.getDouble(ForceFieldDouble.POLAR_13_SCALE, 0.0);
        lambdaTerm = forceField.getBoolean(ForceFieldBoolean.LAMBDATERM, false);

        permanentLambdaAlpha = forceField.getDouble(ForceFieldDouble.PERMANENT_LAMBDA_ALPHA, 1.0);
        if (permanentLambdaAlpha < 0.0 || permanentLambdaAlpha > 2.0) {
            permanentLambdaAlpha = 1.0;
        }

        permanentLambdaExponent = forceField.getDouble(ForceFieldDouble.PERMANENT_LAMBDA_EXPONENT, 1.0);
        if (permanentLambdaExponent < 1.0) {
            permanentLambdaExponent = 2.0;
        }

        polarizationLambdaExponent = forceField.getDouble(ForceFieldDouble.POLARIZATION_LAMBDA_EXPONENT, 2.0);
        if (polarizationLambdaExponent < 1.0) {
            polarizationLambdaExponent = 2.0;
        }

        polarizationLambdaStart = forceField.getDouble(ForceFieldDouble.POLARIZATION_LAMBDA_START, 0.5);
        if (polarizationLambdaStart < 0.0 || polarizationLambdaStart > 0.9) {
            polarizationLambdaStart = 0.5;
        }

        polarizationLambdaEnd = forceField.getDouble(ForceFieldDouble.POLARIZATION_LAMBDA_END, 1.0);
        if (polarizationLambdaEnd < polarizationLambdaStart
                || polarizationLambdaEnd > 1.0
                || polarizationLambdaEnd - polarizationLambdaStart < 0.3) {
            polarizationLambdaEnd = 1.0;
        }

        String polar = forceField.getString(ForceFieldString.POLARIZATION, "MUTUAL");
        boolean polarizationTerm = forceField.getBoolean(ForceFieldBoolean.POLARIZETERM, true);

        if (polarizationTerm == false) {
            polarization = Polarization.NONE;
        } else if (polar.equalsIgnoreCase("DIRECT")) {
            polarization = Polarization.DIRECT;
        } else {
            polarization = Polarization.MUTUAL;
        }

        cudaFFT = forceField.getBoolean(ForceField.ForceFieldBoolean.CUDAFFT, false);

        localMultipole = new double[nAtoms][10];
        frame = new MultipoleType.MultipoleFrameDefinition[nAtoms];
        axisAtom = new int[nAtoms][];
        assignMultipoles();
        globalMultipole = new double[nSymm][nAtoms][10];
        cartMultipolePhi = new double[nAtoms][tensorCount];
        directDipole = new double[nAtoms][3];
        directDipoleCR = new double[nAtoms][3];
        cartesianDipolePhi = new double[nAtoms][tensorCount];
        cartesianDipolePhiCR = new double[nAtoms][tensorCount];
        ip11 = new int[nAtoms][];
        ip12 = new int[nAtoms][];
        ip13 = new int[nAtoms][];
        assignPolarizationGroups();
        thole = new double[nAtoms];
        pdamp = new double[nAtoms];
        polarizability = new double[nAtoms];
        for (Atom ai : atoms) {
            PolarizeType polarizeType = ai.getPolarizeType();
            int index = ai.xyzIndex - 1;
            thole[index] = polarizeType.thole;
            pdamp[index] = polarizeType.pdamp;
            polarizability[index] = polarizeType.polarizability;
        }

        sharedGrad = new SharedDoubleArray[3];
        sharedTorque = new SharedDoubleArray[3];
        sharedPermanentField = new SharedDoubleArray[3];
        sharedPermanentFieldCR = new SharedDoubleArray[3];
        sharedMutualField = new SharedDoubleArray[3];
        sharedMutualFieldCR = new SharedDoubleArray[3];
        for (int i = 0; i < 3; i++) {
            sharedGrad[i] = new SharedDoubleArray(nAtoms);
            sharedTorque[i] = new SharedDoubleArray(nAtoms);
            sharedPermanentField[i] = new SharedDoubleArray(nAtoms);
            sharedPermanentFieldCR[i] = new SharedDoubleArray(nAtoms);
            sharedMutualField[i] = new SharedDoubleArray(nAtoms);
            sharedMutualFieldCR[i] = new SharedDoubleArray(nAtoms);
        }

        if (lambdaTerm) {
            shareddEdLambda = new SharedDouble();
            sharedd2EdLambda2 = new SharedDouble();
            shareddEdLdX = new SharedDoubleArray[3];
            shareddEdLTorque = new SharedDoubleArray[3];
            for (int i = 0; i < 3; i++) {
                shareddEdLdX[i] = new SharedDoubleArray(nAtoms);
                shareddEdLTorque[i] = new SharedDoubleArray(nAtoms);
            }
        } else {
            shareddEdLambda = null;
            sharedd2EdLambda2 = null;
            shareddEdLdX = null;
            shareddEdLTorque = null;
        }

        /**
         * Initialize the soft core lambda masks.
         */
        isSoft = new boolean[nAtoms];
        softCore = new boolean[2][nAtoms];
        use = new boolean[nAtoms];
        for (int i = 0; i < nAtoms; i++) {
            use[i] = true;
            isSoft[i] = false;
            softCore[0][i] = false;
            softCore[1][i] = false;
        }

        if (!crystal.aperiodic()) {
            off = forceField.getDouble(ForceFieldDouble.EWALD_CUTOFF, 7.0);
        } else {
            off = forceField.getDouble(ForceFieldDouble.EWALD_CUTOFF, 100.0);
        }
        double ewald_precision = forceField.getDouble(ForceFieldDouble.EWALD_PRECISION, 1.0e-8);
        aewald = forceField.getDouble(ForceFieldDouble.EWALD_ALPHA, ewaldCoefficient(off, ewald_precision));
        setEwaldParameters(off, aewald);

        if (logger.isLoggable(Level.INFO)) {
            StringBuilder sb = new StringBuilder("\n Electrostatics\n");
            sb.append(format(" Polarization:                         %8s\n", polarization.toString()));
            if (polarization == Polarization.MUTUAL) {
                sb.append(format(" SCF convergence criteria:            %8.3e\n", poleps));
                sb.append(format(" SOR parameter                         %8.3f\n", polsor));
            }
            if (aewald > 0.0) {
                sb.append(format(" Real space cut-off:                   %8.3f (A)\n", off));
                sb.append(format(" Ewald coefficient:                    %8.3f", aewald));
            } else {
                sb.append(format(" Electrostatics cut-off:               %8.3f (A)\n", off));
            }
            logger.info(sb.toString());
        }

        if (cudaFFT) {
            sectionThreads = 2;
            realSpaceThreads = parallelTeam.getThreadCount();
            reciprocalThreads = 1;
            sectionTeam = new ParallelTeam(sectionThreads);
            realSpaceTeam = parallelTeam;
            fftTeam = new ParallelTeam(reciprocalThreads);
        } else {
            boolean concurrent;
            int realThreads = 1;
            try {
                realThreads = forceField.getInteger(ForceField.ForceFieldInteger.PME_REAL_THREADS);
                if (realThreads >= maxThreads || realThreads < 1) {
                    throw new Exception("pme-real-threads must be < ffx.nt and greater than 0");
                }
                concurrent = true;
            } catch (Exception e) {
                concurrent = false;
            }
            if (concurrent) {
                sectionThreads = 2;
                realSpaceThreads = realThreads;
                reciprocalThreads = maxThreads - realThreads;
                sectionTeam = new ParallelTeam(sectionThreads);
                realSpaceTeam = new ParallelTeam(realSpaceThreads);
                fftTeam = new ParallelTeam(reciprocalThreads);
            } else {
                /**
                 * If pme-real-threads is not defined, then do real and reciprocal
                 * space parts sequentially.
                 */
                sectionThreads = 1;
                realSpaceThreads = maxThreads;
                reciprocalThreads = maxThreads;
                sectionTeam = new ParallelTeam(sectionThreads);
                realSpaceTeam = parallelTeam;
                fftTeam = parallelTeam;
            }
        }

        boolean available = false;
        String pairWiseStrategy = null;
        try {
            pairWiseStrategy = forceField.getString(ForceField.ForceFieldString.REAL_SCHEDULE);
            IntegerSchedule.parse(pairWiseStrategy);
            available = true;
        } catch (Exception e) {
            available = false;
        }
        if (available) {
            pairWiseSchedule = IntegerSchedule.parse(pairWiseStrategy);
            logger.info(format(" Electrostatics pairwise schedule: %s", pairWiseStrategy));
        } else {
            pairWiseSchedule = IntegerSchedule.fixed();
        }

        rotateMultipolesRegion = new RotateMultipolesRegion(maxThreads);
        initializationRegion = new InitializationRegion(maxThreads);
        expandInducedDipolesRegion = new ExpandInducedDipolesRegion(maxThreads);
        /**
         * Note that we always pass on the unit cell crystal to ReciprocalSpace
         * instance even if the real space calculations require
         * a ReplicatesCrystal.
         */
        if (aewald > 0.0) {
            reciprocalSpace = new ReciprocalSpace(crystal.getUnitCell(), forceField,
                    coordinates, atoms, aewald, fftTeam, parallelTeam);
        } else {
            reciprocalSpace = null;
        }
        permanentFieldRegion = new PermanentFieldRegion(realSpaceTeam);
        inducedDipoleFieldRegion = new InducedDipoleFieldRegion(realSpaceTeam);
        realSpaceEnergyRegion = new RealSpaceEnergyRegion(maxThreads);
        torqueRegion = new ReduceRegion(maxThreads);

        /**
         * Generalized Kirkwood currently requires aperiodic Ewald. The GK
         * reaction field is added to the intra-molecular to give a
         * self-consistent reaction field.
         */
        generalizedKirkwoodTerm = forceField.getBoolean(ForceFieldBoolean.GKTERM, false);
        if (generalizedKirkwoodTerm) {
            generalizedKirkwood = new GeneralizedKirkwood(forceField, atoms, this, parallelTeam);
        } else {
            generalizedKirkwood = null;
        }
    }

    /**
     * Calculate the PME electrostatic energy.
     *
     * @param gradient If <code>true</code>, the gradient will be calculated.
     * @param print If <code>true</code>, extra logging is enabled.
     * @return return the total electrostatic energy (permanent + polarization).
     */
    public double energy(boolean gradient, boolean print) {
        this.gradient = gradient;

        /**
         * Initialize energy/timing variables.
         */
        multipoleEnergy = 0.0;
        polarizationEnergy = 0.0;
        gkEnergy = 0.0;
        interactions = 0;
        realSpaceTime = 0;
        reciprocalSpaceTime = 0;
        gkTime = 0;

        /**
         * Initialize Lambda variables.
         */
        if (lambdaTerm) {
            shareddEdLambda.set(0.0);
            sharedd2EdLambda2.set(0.0);
        }
        doPermanentRealSpace = true;
        permanentScale = 1.0;
        doPolarization = true;
        polarizationScale = 1.0;

        /**
         * Make sure we have enough space for the real space neighbor lists.
         */
        allocateRealSpaceNeighborLists();

        /**
         * Total permanent + polarization energy.
         */
        double energy = 0.0;

        /**
         * Expand the coordinates and rotate multipoles into the global frame.
         */
        try {
            parallelTeam.execute(initializationRegion);
            parallelTeam.execute(rotateMultipolesRegion);
        } catch (Exception e) {
            String message = "Fatal exception expanding coordinates and rotating multipoles.\n";
            logger.log(Level.SEVERE, message, e);
        }

        if (!lambdaTerm) {
            energy = computeEnergy(print);
        } else {

            /**
             * 1.) Total system under PBC.
             *   A.) Softcore real space for Ligand-Protein and Ligand-Ligand.
             *   B.) Reciprocal space scaled by lambda.
             *   C.) Polarization scaled by lambda.
             */
            if (lambda < polarizationLambdaStart) {
                /**
                 * If the polarization has been completely decoupled,
                 * the contribution of the complete system is zero.
                 * 
                 * We can skip the SCF of this piece for efficiency.
                 */
                doPolarization = false;
            } else if (lambda <= polarizationLambdaEnd) {
                polarizationScale = lPowPol;
            } else {
                polarizationScale = 1.0;
                dlPowPol = 0.0;
                d2lPowPol = 0.0;
            }

            permanentScale = lPowPerm;
            dEdLSign = 1.0;
            energy = computeEnergy(print);

            /** 
             * 2.) Condensed phase system without the ligand. 
             *   A.) No permanent real space electrostatics.
             *   B.) Permanent reciprocal space scaled by (1 - lambda).
             *   C.) Polarization scaled by (1 - lambda).
             */
            for (int i = 0; i < nAtoms; i++) {
                if (atoms[i].applyLambda()) {
                    use[i] = false;
                } else {
                    use[i] = true;
                }
            }
            doPermanentRealSpace = false;
            permanentScale = 1.0 - lPowPerm;
            dEdLSign = -1.0;

            if (lambda <= polarizationLambdaEnd) {
                doPolarization = true;
                polarizationScale = 1.0 - lPowPol;
            } else {
                doPolarization = false;
            }
            energy = computeEnergy(print);

            /**
             * 3.) Ligand in vacuum
             *   A.) Softcore real space with an Ewald coefficient of 0.0 (no reciprocal space).
             *   B.) Polarization scaled by (1 - lambda).
             */
            for (int i = 0; i < nAtoms; i++) {
                if (atoms[i].applyLambda()) {
                    use[i] = true;
                } else {
                    use[i] = false;
                }
            }
            doPermanentRealSpace = true;

            /**
             * Save the current real space Ewald parameters.
             */
            double off_back = off;
            double aewald_back = aewald;
            off = 12.0;
            aewald = 0.0;
            setEwaldParameters(off, aewald);

            /**
             * Turn off replicates / symmetry mates.
             */
            boolean useSymmetry_back = useSymmetry;
            useSymmetry = false;

            energy = computeEnergy(print);

            /**
             * Revert to the saved parameters.
             */
            off = off_back;
            aewald = aewald_back;
            setEwaldParameters(off, aewald);
            useSymmetry = useSymmetry_back;
        }

        /**
         * Convert torques to gradients on multipole frame defining atoms.
         * Add to electrostatic gradient to the total XYZ gradient.
         */
        if (gradient || lambdaTerm) {
            try {
                parallelTeam.execute(torqueRegion);
            } catch (Exception e) {
                String message = "Exception calculating torques.";
                logger.log(Level.SEVERE, message, e);
            }
        }

        return energy;
    }

    /**
     * Calculate the PME electrostatic energy for a Lambda state.
     *
     * @param print If <code>true</code>, extra logging is enabled.
     * @return return the total electrostatic energy (permanent + polarization).
     */
    private double computeEnergy(boolean print) {

        /**
         * Initialize the energy components to zero.
         */
        double eself = 0.0;
        double erecip = 0.0;
        double ereal = 0.0;
        double eselfi = 0.0;
        double erecipi = 0.0;
        double ereali = 0.0;

        /**
         * Find the permanent multipole potential, field, etc.
         */
        try {
            /**
             * Compute b-Splines and permanent density.
             */
            if (aewald > 0.0) {
                bsplineTime = -System.nanoTime();
                reciprocalSpace.computeBSplines();
                bsplineTime += System.nanoTime();
                densityTime = -System.nanoTime();
                reciprocalSpace.splinePermanentMultipoles(globalMultipole, use);
                densityTime += System.nanoTime();
            }

            /**
             * The real space contribution can be calculated at the same time 
             * the reciprocal space convolution is being done.
             */
            realAndFFTTime = -System.nanoTime();
            sectionTeam.execute(permanentFieldRegion);
            realAndFFTTime += System.nanoTime();

            /**
             * Collect the reciprocal space field.
             */
            if (aewald > 0.0) {
                phiTime = -System.nanoTime();
                reciprocalSpace.computePermanentPhi(cartMultipolePhi);
                phiTime += System.nanoTime();
            }
        } catch (Exception e) {
            String message = "Fatal exception computing the permanent multipole field.\n";
            logger.log(Level.SEVERE, message, e);
        }

        /**
         * Compute Born radii if necessary.
         */
        if (generalizedKirkwoodTerm) {
            bornTime = -System.nanoTime();
            generalizedKirkwood.computeBornRadii();
            bornTime += System.nanoTime();
        }

        /**
         * Do the self-consistent field calculation. If polarization is turned
         * off then the induced dipoles are initialized to zero and the method
         * returns.
         */
        if (polarization != Polarization.NONE && doPolarization) {
            selfConsistentField(logger.isLoggable(Level.FINE));
            if (aewald > 0.0) {
                /**
                 * The induced dipole self energy due to interaction with the
                 * permanent dipole. The energy and gradient are scaled
                 * by "polarizationScale".
                 */
                eselfi = inducedDipoleSelfEnergy(gradient);
                /**
                 * The energy of the permanent multipoles in the induced dipole
                 * reciprocal potential. The energy and gradient are scaled
                 * by "polarizationScale".
                 */
                reciprocalSpaceTime -= System.nanoTime();
                erecipi = inducedDipoleReciprocalSpaceEnergy(gradient);
                reciprocalSpaceTime += System.nanoTime();
            }
        }

        if (aewald > 0.0) {
            /**
             * The self energy of the permanent multipoles is independent
             * of coordinates, but may be scaled by Lambda.
             */
            eself = permanentSelfEnergy();
            interactions += nAtoms;
            erecip = permanentReciprocalSpaceEnergy(gradient);
        }

        /**
         * Find the total real space energy. This includes the permanent
         * multipoles in their own real space potential and the interaction of
         * permanent multipoles with induced dipoles.
         */
        try {
            realSpaceTime -= System.nanoTime();
            parallelTeam.execute(realSpaceEnergyRegion);
            realSpaceTime += System.nanoTime();
            ereal = realSpaceEnergyRegion.getPermanentEnergy();
            ereali = realSpaceEnergyRegion.getPolarizationEnergy();
            interactions += realSpaceEnergyRegion.getInteractions();
        } catch (Exception e) {
            String message = "Exception computing the real space energy.\n";
            logger.log(Level.SEVERE, message, e);
        }

        /**
         * Compute the generalized Kirkwood solvation free energy.
         */
        if (generalizedKirkwoodTerm) {
            if (lambdaTerm) {
                logger.severe("Use of generalized Kirkwood with Lambda dynamics is not yet supported.");
            }
            gkTime -= System.nanoTime();
            gkEnergy += generalizedKirkwood.solvationEnergy(gradient, print);
            gkInteractions += generalizedKirkwood.getInteractions();
            gkTime += System.nanoTime();
        }

        /**
         * Collect energy terms.
         */
        multipoleEnergy += eself + erecip + ereal;
        polarizationEnergy += eselfi + erecipi + ereali;

        /**
         * Log some timings.
         */
        if (logger.isLoggable(Level.FINE)) {
            StringBuilder sb = new StringBuilder();
            sb.append(format("\n b-Spline:   %8.3f (sec)\n", bsplineTime * toSeconds));
            sb.append(format(" Density:    %8.3f (sec)\n", densityTime * toSeconds));
            sb.append(format(" Real + FFT: %8.3f (sec)\n", realAndFFTTime * toSeconds));
            sb.append(format(" Phi:        %8.3f (sec)\n", phiTime * toSeconds));
            logger.fine(sb.toString());
        }

        /**
         * Log some info.
         */
        if (logger.isLoggable(Level.FINE)) {
            StringBuilder sb = new StringBuilder();
            sb.append(format("\n Total Time =    Real +   Recip (sec)\n"));
            sb.append(format("   %8.3f =%8.3f +%8.3f\n", toSeconds * (realSpaceTime + reciprocalSpaceTime), toSeconds * realSpaceTime,
                    toSeconds * reciprocalSpaceTime));
            sb.append(format(" Multipole Self-Energy:   %16.8f\n", eself));
            sb.append(format(" Multipole Reciprocal:    %16.8f\n", erecip));
            sb.append(format(" Multipole Real Space:    %16.8f\n", ereal));
            sb.append(format(" Polarization Self-Energy:%16.8f\n", eselfi));
            sb.append(format(" Polarization Reciprocal: %16.8f\n", erecipi));
            sb.append(format(" Polarization Real Space: %16.8f\n", ereali));
            if (generalizedKirkwoodTerm) {
                sb.append(format(" Generalized Kirkwood:    %16.8f\n", gkEnergy));
            }
            logger.fine(sb.toString());
        }

        return multipoleEnergy + polarizationEnergy;
    }

    public int getInteractions() {
        return interactions;
    }

    public double getPermanentEnergy() {
        return multipoleEnergy;
    }

    public double getPolarizationEnergy() {
        return polarizationEnergy;
    }

    public double getGKEnergy() {
        return gkEnergy;
    }

    public int getGKInteractions() {
        return gkInteractions;
    }

    public void getGradients(double grad[][]) {
        for (int i = 0; i < nAtoms; i++) {
            grad[0][i] = sharedGrad[0].get(i);
            grad[1][i] = sharedGrad[1].get(i);
            grad[2][i] = sharedGrad[2].get(i);
        }
    }

    protected SharedDoubleArray[] getSharedGradient() {
        return sharedGrad;
    }
    
    protected SharedDoubleArray[] getSharedTorque() {
        return sharedTorque;
    }
    
    private void selfConsistentField(boolean print) {

        /*
        if (polarization == Polarization.NONE || !doPolarization) {
        for (int i = 0; i < nAtoms; i++) {
        inducedDipole[0][i][0] = 0.0;
        inducedDipole[0][i][1] = 0.0;
        inducedDipole[0][i][2] = 0.0;
        inducedDipoleCR[0][i][0] = 0.0;
        inducedDipoleCR[0][i][1] = 0.0;
        inducedDipoleCR[0][i][2] = 0.0;
        }
        try {
        parallelTeam.execute(expandInducedDipolesRegion);
        } catch (Exception e) {
        String message = "Exception expanding induced dipoles.";
        logger.log(Level.SEVERE, message, e);
        }
        return;
        } */

        long startTime = System.nanoTime();
        if (aewald > 0.0) {
            /**
             * Add the self and reciprocal contributions to the direct field.
             */
            for (int i = 0; i < nAtoms; i++) {
                double mpolei[] = globalMultipole[0][i];
                double phii[] = cartMultipolePhi[i];
                double fx = aewald3 * mpolei[t100] - phii[t100];
                double fy = aewald3 * mpolei[t010] - phii[t010];
                double fz = aewald3 * mpolei[t001] - phii[t001];
                sharedPermanentField[0].addAndGet(i, fx);
                sharedPermanentField[1].addAndGet(i, fy);
                sharedPermanentField[2].addAndGet(i, fz);
                sharedPermanentFieldCR[0].addAndGet(i, fx);
                sharedPermanentFieldCR[1].addAndGet(i, fy);
                sharedPermanentFieldCR[2].addAndGet(i, fz);
            }
        }
        if (generalizedKirkwoodTerm) {
            /**
             * Initialize the electric field to the direct field plus
             * the permanent GK reaction field.
             */
            gkTime = -System.nanoTime();
            generalizedKirkwood.computePermanentGKField();
            gkTime += System.nanoTime();
            logger.fine(String.format(" Computed GK permanent field %8.3f (sec)", gkTime * 1.0e-9));
            SharedDoubleArray gkField[] = generalizedKirkwood.sharedGKField;
            for (int i = 0; i < nAtoms; i++) {
                double fx = gkField[0].get(i);
                double fy = gkField[1].get(i);
                double fz = gkField[2].get(i);
                sharedPermanentField[0].addAndGet(i, fx);
                sharedPermanentField[1].addAndGet(i, fy);
                sharedPermanentField[2].addAndGet(i, fz);
                sharedPermanentFieldCR[0].addAndGet(i, fx);
                sharedPermanentFieldCR[1].addAndGet(i, fy);
                sharedPermanentFieldCR[2].addAndGet(i, fz);
            }
        }

        /**
         * Set the induced dipoles to the polarizability times the direct field.
         */
        final double induced0[][] = inducedDipole[0];
        final double inducedCR0[][] = inducedDipoleCR[0];
        for (int i = 0; i < nAtoms; i++) {
            final double polar = polarizability[i];
            final double ind[] = induced0[i];
            final double directi[] = directDipole[i];
            ind[0] = polar * sharedPermanentField[0].get(i);
            ind[1] = polar * sharedPermanentField[1].get(i);
            ind[2] = polar * sharedPermanentField[2].get(i);
            directi[0] = ind[0];
            directi[1] = ind[1];
            directi[2] = ind[2];
            final double inp[] = inducedCR0[i];
            final double directCRi[] = directDipoleCR[i];
            inp[0] = polar * sharedPermanentFieldCR[0].get(i);
            inp[1] = polar * sharedPermanentFieldCR[1].get(i);
            inp[2] = polar * sharedPermanentFieldCR[2].get(i);
            directCRi[0] = inp[0];
            directCRi[1] = inp[1];
            directCRi[2] = inp[2];
        }
        try {
            parallelTeam.execute(expandInducedDipolesRegion);
        } catch (Exception e) {
            String message = "Exception expanding induced dipoles.";
            logger.log(Level.SEVERE, message, e);
        }

        if (polarization == Polarization.MUTUAL) {
            StringBuilder sb = null;
            long directTime = System.nanoTime() - startTime;
            if (print) {
                sb = new StringBuilder(
                        "\n SELF-CONSISTENT FIELD\n"
                        + " Iter     RMS Change (Debyes)   Time\n");
            }
            boolean done = false;
            int maxiter = 1000;
            int iter = 0;
            double eps = 100.0;
            double epsold;
            while (!done) {
                long cycleTime = -System.nanoTime();
                /**
                 * Find the induced dipole field.
                 */
                try {
                    if (aewald > 0.0) {
                        densityTime -= System.nanoTime();
                        reciprocalSpace.splineInducedDipoles(inducedDipole, inducedDipoleCR, use);
                        densityTime += System.nanoTime();
                    }
                    realAndFFTTime -= System.nanoTime();
                    sectionTeam.execute(inducedDipoleFieldRegion);
                    realAndFFTTime += System.nanoTime();
                    if (aewald > 0.0) {
                        phiTime -= System.nanoTime();
                        reciprocalSpace.computeInducedPhi(cartesianDipolePhi, cartesianDipolePhiCR);
                        phiTime += System.nanoTime();
                    }
                } catch (Exception e) {
                    String message = "Fatal exception computing the induced dipole field.\n";
                    logger.log(Level.SEVERE, message, e);
                }
                if (aewald > 0.0) {
                    /**
                     * Add the self and reciprocal space fields 
                     * to the real space field.
                     */
                    for (int i = 0; i < nAtoms; i++) {
                        double dipolei[] = induced0[i];
                        double dipoleCRi[] = inducedCR0[i];
                        final double phii[] = cartesianDipolePhi[i];
                        final double phiCRi[] = cartesianDipolePhiCR[i];
                        sharedMutualField[0].addAndGet(i, aewald3 * dipolei[0] - phii[t100]);
                        sharedMutualField[1].addAndGet(i, aewald3 * dipolei[1] - phii[t010]);
                        sharedMutualField[2].addAndGet(i, aewald3 * dipolei[2] - phii[t001]);
                        sharedMutualFieldCR[0].addAndGet(i, aewald3 * dipoleCRi[0] - phiCRi[t100]);
                        sharedMutualFieldCR[1].addAndGet(i, aewald3 * dipoleCRi[1] - phiCRi[t010]);
                        sharedMutualFieldCR[2].addAndGet(i, aewald3 * dipoleCRi[2] - phiCRi[t001]);
                    }
                }
                if (generalizedKirkwoodTerm) {
                    /**
                     * GK field.
                     */
                    gkTime = -System.nanoTime();
                    generalizedKirkwood.computeInducedGKField();
                    gkTime += System.nanoTime();
                    logger.fine(String.format(" Computed GK induced field %8.3f (sec)", gkTime * 1.0e-9));
                    SharedDoubleArray gkField[] = generalizedKirkwood.sharedGKField;
                    SharedDoubleArray gkFieldCR[] = generalizedKirkwood.sharedGKFieldCR;
                    /**
                     * Add the GK reaction field to the intramolecular field.
                     */
                    for (int i = 0; i < nAtoms; i++) {
                        sharedMutualField[0].addAndGet(i, gkField[0].get(i));
                        sharedMutualField[1].addAndGet(i, gkField[1].get(i));
                        sharedMutualField[2].addAndGet(i, gkField[2].get(i));
                        sharedMutualFieldCR[0].addAndGet(i, gkFieldCR[0].get(i));
                        sharedMutualFieldCR[1].addAndGet(i, gkFieldCR[1].get(i));
                        sharedMutualFieldCR[2].addAndGet(i, gkFieldCR[2].get(i));
                    }
                }

                /**
                 * Apply Successive Over-Relaxation (SOR) and check for 
                 * convergence of the SCF.
                 */
                iter++;
                epsold = eps;
                eps = 0.0;
                double epsp = 0.0;
                for (int i = 0; i < nAtoms; i++) {
                    final double ind[] = induced0[i];
                    final double indCR[] = inducedCR0[i];
                    final double direct[] = directDipole[i];
                    final double directCR[] = directDipoleCR[i];
                    final double polar = polarizability[i];
                    for (int j = 0; j < 3; j++) {
                        double previous = ind[j];
                        double mutual = polar * sharedMutualField[j].get(i);
                        sharedMutualField[j].set(i, 0.0);
                        ind[j] = direct[j] + mutual;
                        double delta = polsor * (ind[j] - previous);
                        ind[j] = previous + delta;
                        eps += delta * delta;
                        previous = indCR[j];
                        mutual = polar * sharedMutualFieldCR[j].get(i);
                        sharedMutualFieldCR[j].set(i, 0.0);
                        indCR[j] = directCR[j] + mutual;
                        delta = polsor * (indCR[j] - previous);
                        indCR[j] = previous + delta;
                        epsp += delta * delta;
                    }
                }
                try {
                    parallelTeam.execute(expandInducedDipolesRegion);
                } catch (Exception e) {
                    String message = "Exception expanding induced dipoles.";
                    logger.log(Level.SEVERE, message, e);
                }
                eps = max(eps, epsp);
                eps = MultipoleType.DEBYE * sqrt(eps / (double) nAtoms);
                cycleTime += System.nanoTime();

                if (print) {
                    sb.append(format(
                            " %4d  %15.10f      %8.3f\n", iter, eps, cycleTime * toSeconds));
                }
                if (eps < poleps) {
                    done = true;
                }
                if (eps > epsold) {
                    if (sb != null) {
                        logger.warning(sb.toString());
                    }
                    String message = format("Fatal convergence failure: (%10.5f > %10.5f)\n", eps, epsold);
                    logger.severe(message);
                }
                if (iter >= maxiter) {
                    if (sb != null) {
                        logger.warning(sb.toString());
                    }
                    String message = format("Maximum iterations reached: (%d)\n", iter);
                    logger.severe(message);
                }
            }
            if (print) {
                sb.append(format("\n Direct:                    %8.3f\n",
                        toSeconds * directTime));
                startTime = System.nanoTime() - startTime;
                sb.append(format(" SCF Total:                 %8.3f\n",
                        startTime * toSeconds));
                logger.info(sb.toString());
            }
        }

        /*
        if (false) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nAtoms; i++) {
        sb.append(format(
        " Induced Dipole  %d %15.8f %15.8f %15.8f\n", i + 1,
        MultipoleType.DEBYE * inducedDipole[0][i][0],
        MultipoleType.DEBYE * inducedDipole[0][i][1],
        MultipoleType.DEBYE * inducedDipole[0][i][2]));
        }
        logger.info(sb.toString());
        } */
    }

    private double permanentSelfEnergy() {
        if (aewald <= 0.0) {
            return 0.0;
        }
        double e = 0.0;
        double term = 2.0 * aewald * aewald;
        double fterm = -ELECTRIC * aewald / sqrtPi;
        for (int i = 0; i < nAtoms; i++) {
            if (use[i]) {
                double in[] = localMultipole[i];
                double cii = in[t000] * in[t000];
                double dii = in[t100] * in[t100] + in[t010] * in[t010] + in[t001] * in[t001];
                double qii = in[t200] * in[t200] + in[t020] * in[t020] + in[t002] * in[t002] + 2.0 * (in[t110] * in[t110] + in[t101] * in[t101] + in[t011] * in[t011]);
                e += fterm * (cii + term * (dii / 3.0 + 2.0 * term * qii / 45.0));
            }
        }
        if (lambdaTerm) {
            shareddEdLambda.addAndGet(e * dlPowPerm * dEdLSign);
            sharedd2EdLambda2.addAndGet(e * d2lPowPerm * dEdLSign);
        }
        return permanentScale * e;
    }

    /**
     * The Permanent Field Region should be executed by a ParallelTeam with
     * exactly 2 threads. The Real Space and Reciprocal Space Sections will be
     * run concurrently, each with the number of threads defined by their
     * respective ParallelTeam instances.
     */
    private class PermanentFieldRegion extends ParallelRegion {

        private PermanentRealSpaceFieldSection permanentRealSpaceFieldSection;
        private PermanentReciprocalSection permanentReciprocalSection;

        public PermanentFieldRegion(ParallelTeam pt) {
            permanentRealSpaceFieldSection = new PermanentRealSpaceFieldSection(pt);
            permanentReciprocalSection = new PermanentReciprocalSection();
        }

        @Override
        public void run() {
            try {
                execute(permanentRealSpaceFieldSection, permanentReciprocalSection);
            } catch (Exception e) {
                String message = "Fatal exception computing the permanent multipole field.\n";
                logger.log(Level.SEVERE, message, e);
            }
        }
    }

    /**
     * Computes the Permanent Multipole Real Space Field.
     */
    private class PermanentRealSpaceFieldSection extends ParallelSection {

        private final PermanentRealSpaceFieldRegion permanentRealSpaceFieldRegion;
        private final ParallelTeam pt;

        public PermanentRealSpaceFieldSection(ParallelTeam pt) {
            this.pt = pt;
            int nt = pt.getThreadCount();
            permanentRealSpaceFieldRegion = new PermanentRealSpaceFieldRegion(nt);
        }

        @Override
        public void run() {
            try {
                realSpaceTime -= System.nanoTime();
                pt.execute(permanentRealSpaceFieldRegion);
                realSpaceTime += System.nanoTime();
            } catch (Exception e) {
                String message = "Fatal exception computing the real space field.\n";
                logger.log(Level.SEVERE, message, e);
            }
        }
    }

    /**
     * Compute the permanent multipole reciprocal space contribution to the
     * electric potential, field, etc. using the number of threads specified
     * by the ParallelTeam used to construct the ReciprocalSpace instance.
     */
    private class PermanentReciprocalSection extends ParallelSection {

        @Override
        public void run() {
            if (aewald > 0.0) {
                reciprocalSpace.permanentMultipoleConvolution();
            }
        }
    }

    /**
     * The Induced Dipole Field Region should be executed by a ParallelTeam with
     * exactly 2 threads. The Real Space and Reciprocal Space Sections will be
     * run concurrently, each with the number of threads defined by their
     * respective ParallelTeam instances.
     */
    private class InducedDipoleFieldRegion extends ParallelRegion {

        private InducedDipoleRealSpaceFieldSection inducedRealSpaceFieldSection;
        private InducedDipoleReciprocalFieldSection inducedReciprocalFieldSection;

        public InducedDipoleFieldRegion(ParallelTeam pt) {
            inducedRealSpaceFieldSection = new InducedDipoleRealSpaceFieldSection(pt);
            inducedReciprocalFieldSection = new InducedDipoleReciprocalFieldSection();
        }

        @Override
        public void run() {
            try {
                execute(inducedRealSpaceFieldSection, inducedReciprocalFieldSection);
            } catch (Exception e) {
                String message = "Fatal exception computing the induced dipole field.\n";
                logger.log(Level.SEVERE, message, e);
            }
        }
    }

    private class InducedDipoleRealSpaceFieldSection extends ParallelSection {

        private final InducedDipoleRealSpaceFieldRegion polarizationRealSpaceFieldRegion;
        private final ParallelTeam pt;

        public InducedDipoleRealSpaceFieldSection(ParallelTeam pt) {
            this.pt = pt;
            int nt = pt.getThreadCount();
            polarizationRealSpaceFieldRegion = new InducedDipoleRealSpaceFieldRegion(nt);
        }

        @Override
        public void run() {
            try {
                long time = System.nanoTime();
                pt.execute(polarizationRealSpaceFieldRegion);
                //polarizationRealSpaceFieldRegion.setField(field, fieldCR);
                time = System.nanoTime() - time;
                realSpaceTime += time;
            } catch (Exception e) {
                String message = "Fatal exception computing the real space field.\n";
                logger.log(Level.SEVERE, message, e);
            }
        }
    }

    private class InducedDipoleReciprocalFieldSection extends ParallelSection {

        @Override
        public void run() {
            if (aewald > 0.0) {
                reciprocalSpace.inducedDipoleConvolution();
            }
        }
    }

    private double permanentReciprocalSpaceEnergy(boolean gradient) {
        if (aewald <= 0.0) {
            return 0.0;
        }
        double erecip = 0.0;
        double dUdL = 0.0;
        double d2UdL2 = 0.0;
        final double pole[][] = globalMultipole[0];
        final double fracMultipoles[][] = reciprocalSpace.getFracMultipoles();
        final double fracMultipolePhi[][] = reciprocalSpace.getFracMultipolePhi();
        final double nfftX = reciprocalSpace.getXDim();
        final double nfftY = reciprocalSpace.getYDim();
        final double nfftZ = reciprocalSpace.getZDim();
        for (int i = 0; i < nAtoms; i++) {
            if (use[i]) {
                final double phi[] = cartMultipolePhi[i];
                final double mpole[] = pole[i];
                final double fmpole[] = fracMultipoles[i];
                double e = mpole[t000] * phi[t000] + mpole[t100] * phi[t100]
                        + mpole[t010] * phi[t010] + mpole[t001] * phi[t001]
                        + oneThird * (mpole[t200] * phi[t200]
                        + mpole[t020] * phi[t020]
                        + mpole[t002] * phi[t002]
                        + 2.0 * (mpole[t110] * phi[t110]
                        + mpole[t101] * phi[t101]
                        + mpole[t011] * phi[t011]));
                erecip += e;
                if (gradient || lambdaTerm) {
                    final double fPhi[] = fracMultipolePhi[i];
                    double gx = fmpole[t000] * fPhi[t100] + fmpole[t100] * fPhi[t200] + fmpole[t010] * fPhi[t110]
                            + fmpole[t001] * fPhi[t101]
                            + fmpole[t200] * fPhi[t300] + fmpole[t020] * fPhi[t120]
                            + fmpole[t002] * fPhi[t102] + fmpole[t110] * fPhi[t210]
                            + fmpole[t101] * fPhi[t201] + fmpole[t011] * fPhi[t111];
                    double gy = fmpole[t000] * fPhi[t010] + fmpole[t100] * fPhi[t110] + fmpole[t010] * fPhi[t020]
                            + fmpole[t001] * fPhi[t011] + fmpole[t200] * fPhi[t210] + fmpole[t020] * fPhi[t030]
                            + fmpole[t002] * fPhi[t012] + fmpole[t110] * fPhi[t120] + fmpole[t101] * fPhi[t111]
                            + fmpole[t011] * fPhi[t021];
                    double gz = fmpole[t000] * fPhi[t001] + fmpole[t100] * fPhi[t101] + fmpole[t010] * fPhi[t011]
                            + fmpole[t001] * fPhi[t002] + fmpole[t200] * fPhi[t201] + fmpole[t020] * fPhi[t021]
                            + fmpole[t002] * fPhi[t003] + fmpole[t110] * fPhi[t111] + fmpole[t101] * fPhi[t102]
                            + fmpole[t011] * fPhi[t012];
                    gx *= nfftX;
                    gy *= nfftY;
                    gz *= nfftZ;
                    final double recip[][] = crystal.getUnitCell().A;
                    final double dfx = recip[0][0] * gx + recip[0][1] * gy + recip[0][2] * gz;
                    final double dfy = recip[1][0] * gx + recip[1][1] * gy + recip[1][2] * gz;
                    final double dfz = recip[2][0] * gx + recip[2][1] * gy + recip[2][2] * gz;
                    // Compute dipole torques
                    double tqx = -mpole[t010] * phi[t001] + mpole[t001] * phi[t010];
                    double tqy = -mpole[t001] * phi[t100] + mpole[t100] * phi[t001];
                    double tqz = -mpole[t100] * phi[t010] + mpole[t010] * phi[t100];
                    // Compute quadrupole torques
                    tqx -= 2.0 / 3.0 * (mpole[t110] * phi[t101] + mpole[t020] * phi[t011] + mpole[t011] * phi[t002] - mpole[t101] * phi[t110] - mpole[t011] * phi[t020] - mpole[t002] * phi[t011]);
                    tqy -= 2.0 / 3.0 * (mpole[t101] * phi[t200] + mpole[t011] * phi[t110] + mpole[t002] * phi[t101] - mpole[t200] * phi[t101] - mpole[t110] * phi[t011] - mpole[t101] * phi[t002]);
                    tqz -= 2.0 / 3.0 * (mpole[t200] * phi[t110] + mpole[t110] * phi[t020] + mpole[t101] * phi[t011] - mpole[t110] * phi[t200] - mpole[t020] * phi[t110] - mpole[t011] * phi[t101]);
                    if (gradient) {
                        sharedGrad[0].addAndGet(i, permanentScale * ELECTRIC * dfx);
                        sharedGrad[1].addAndGet(i, permanentScale * ELECTRIC * dfy);
                        sharedGrad[2].addAndGet(i, permanentScale * ELECTRIC * dfz);
                        sharedTorque[0].addAndGet(i, permanentScale * ELECTRIC * tqx);
                        sharedTorque[1].addAndGet(i, permanentScale * ELECTRIC * tqy);
                        sharedTorque[2].addAndGet(i, permanentScale * ELECTRIC * tqz);
                    }
                    if (lambdaTerm) {
                        dUdL += dEdLSign * dlPowPerm * e;
                        d2UdL2 += dEdLSign * d2lPowPerm * e;
                        shareddEdLdX[0].addAndGet(i, dEdLSign * dlPowPerm * ELECTRIC * dfx);
                        shareddEdLdX[1].addAndGet(i, dEdLSign * dlPowPerm * ELECTRIC * dfy);
                        shareddEdLdX[2].addAndGet(i, dEdLSign * dlPowPerm * ELECTRIC * dfz);
                        shareddEdLTorque[0].addAndGet(i, dEdLSign * dlPowPerm * ELECTRIC * tqx);
                        shareddEdLTorque[1].addAndGet(i, dEdLSign * dlPowPerm * ELECTRIC * tqy);
                        shareddEdLTorque[2].addAndGet(i, dEdLSign * dlPowPerm * ELECTRIC * tqz);
                    }
                }
            }
        }
        if (lambdaTerm) {
            shareddEdLambda.addAndGet(0.5 * dUdL * ELECTRIC);
            sharedd2EdLambda2.addAndGet(0.5 * d2UdL2 * ELECTRIC);
        }
        erecip = permanentScale * 0.5 * ELECTRIC * erecip;
        return erecip;
    }

    private double inducedDipoleSelfEnergy(boolean gradient) {
        double e = 0.0;
        final double term = -2.0 / 3.0 * ELECTRIC * aewald * aewald * aewald / sqrtPi;
        final double ind[][] = inducedDipole[0];
        final double indp[][] = inducedDipoleCR[0];
        final double mpole[][] = globalMultipole[0];
        for (int i = 0; i < nAtoms; i++) {
            if (use[i]) {
                final double indi[] = ind[i];
                final double multipolei[] = mpole[i];
                final double dix = multipolei[t100];
                final double diy = multipolei[t010];
                final double diz = multipolei[t001];
                final double dii = indi[0] * dix + indi[1] * diy + indi[2] * diz;
                e += term * dii;
            }
        }
        if (lambdaTerm) {
            shareddEdLambda.addAndGet(dEdLSign * dlPowPol * e);
            sharedd2EdLambda2.addAndGet(dEdLSign * d2lPowPol * e);
        }
        if (gradient) {
            final double fterm = -2.0 * term;
            for (int i = 0; i < nAtoms; i++) {
                if (use[i]) {
                    final double indi[] = ind[i];
                    final double indpi[] = indp[i];
                    final double multipolei[] = mpole[i];
                    final double dix = multipolei[t100];
                    final double diy = multipolei[t010];
                    final double diz = multipolei[t001];
                    final double uix = 0.5 * (indi[0] + indpi[0]);
                    final double uiy = 0.5 * (indi[1] + indpi[1]);
                    final double uiz = 0.5 * (indi[2] + indpi[2]);
                    final double tix = fterm * (diy * uiz - diz * uiy);
                    final double tiy = fterm * (diz * uix - dix * uiz);
                    final double tiz = fterm * (dix * uiy - diy * uix);
                    sharedTorque[0].addAndGet(i, polarizationScale * tix);
                    sharedTorque[1].addAndGet(i, polarizationScale * tiy);
                    sharedTorque[2].addAndGet(i, polarizationScale * tiz);
                    if (lambdaTerm) {
                        shareddEdLTorque[0].addAndGet(i, dEdLSign * dlPowPol * tix);
                        shareddEdLTorque[1].addAndGet(i, dEdLSign * dlPowPol * tiy);
                        shareddEdLTorque[2].addAndGet(i, dEdLSign * dlPowPol * tiz);
                    }
                }
            }
        }
        return polarizationScale * e;
    }

    private double inducedDipoleReciprocalSpaceEnergy(boolean gradient) {
        if (aewald <= 0.0) {
            return 0.0;
        }
        double e = 0.0;
        if (gradient && polarization == Polarization.DIRECT) {
            try {
                reciprocalSpace.splineInducedDipoles(inducedDipole, inducedDipoleCR, use);
                sectionTeam.execute(inducedDipoleFieldRegion);
                reciprocalSpace.computeInducedPhi(cartesianDipolePhi, cartesianDipolePhiCR);
            } catch (Exception ex) {
                String message = "Fatal exception computing the induced reciprocal space field.\n";
                logger.log(Level.SEVERE, message, ex);
            }
        } else {
            reciprocalSpace.cartToFracInducedDipoles(inducedDipole, inducedDipoleCR);
        }
        final double nfftX = reciprocalSpace.getXDim();
        final double nfftY = reciprocalSpace.getYDim();
        final double nfftZ = reciprocalSpace.getZDim();
        final double mpole[][] = globalMultipole[0];
        final double fractionalMultipolePhi[][] = reciprocalSpace.getFracMultipolePhi();
        final double fractionalInducedDipolePhi[][] = reciprocalSpace.getFracInducedDipolePhi();
        final double fractionalInducedDipolepPhi[][] = reciprocalSpace.getFracInducedDipoleCRPhi();
        final double fmpole[][] = reciprocalSpace.getFracMultipoles();
        final double find[][] = reciprocalSpace.getFracInducedDipoles();
        final double finp[][] = reciprocalSpace.getFracInducedDipolesCR();
        for (int i = 0; i < nAtoms; i++) {
            if (use[i]) {
                final double fPhi[] = fractionalMultipolePhi[i];
                final double findi[] = find[i];
                final double indx = findi[0];
                final double indy = findi[1];
                final double indz = findi[2];
                e += indx * fPhi[t100] + indy * fPhi[t010] + indz * fPhi[t001];
                if (gradient) {
                    final double iPhi[] = cartesianDipolePhi[i];
                    final double ipPhi[] = cartesianDipolePhiCR[i];
                    final double fiPhi[] = fractionalInducedDipolePhi[i];
                    final double fipPhi[] = fractionalInducedDipolepPhi[i];
                    final double mpolei[] = mpole[i];
                    final double fmpolei[] = fmpole[i];
                    final double finpi[] = finp[i];
                    final double inpx = finpi[0];
                    final double inpy = finpi[1];
                    final double inpz = finpi[2];
                    final double insx = indx + inpx;
                    final double insy = indy + inpy;
                    final double insz = indz + inpz;
                    for (int t = 0; t < tensorCount; t++) {
                        sPhi[t] = 0.5 * (iPhi[t] + ipPhi[t]);
                        sfPhi[t] = fiPhi[t] + fipPhi[t];
                    }
                    double gx = insx * fPhi[t200] + insy * fPhi[t110] + insz * fPhi[t101];
                    double gy = insx * fPhi[t110] + insy * fPhi[t020] + insz * fPhi[t011];
                    double gz = insx * fPhi[t101] + insy * fPhi[t011] + insz * fPhi[t002];
                    if (polarization == Polarization.MUTUAL) {
                        gx += indx * fipPhi[t200] + inpx * fiPhi[t200] + indy * fipPhi[t110] + inpy * fiPhi[t110] + indz * fipPhi[t101] + inpz * fiPhi[t101];
                        gy += indx * fipPhi[t110] + inpx * fiPhi[t110] + indy * fipPhi[t020] + inpy * fiPhi[t020] + indz * fipPhi[t011] + inpz * fiPhi[t011];
                        gz += indx * fipPhi[t101] + inpx * fiPhi[t101] + indy * fipPhi[t011] + inpy * fiPhi[t011] + indz * fipPhi[t002] + inpz * fiPhi[t002];
                    }
                    gx += fmpolei[t000] * sfPhi[t100] + fmpolei[t100] * sfPhi[t200] + fmpolei[t010] * sfPhi[t110] + fmpolei[t001] * sfPhi[t101] + fmpolei[t200] * sfPhi[t300] + fmpolei[t020] * sfPhi[t120] + fmpolei[t002] * sfPhi[t102] + fmpolei[t110] * sfPhi[t210] + fmpolei[t101] * sfPhi[t201] + fmpolei[t011] * sfPhi[t111];
                    gy += fmpolei[t000] * sfPhi[t010] + fmpolei[t100] * sfPhi[t110] + fmpolei[t010] * sfPhi[t020] + fmpolei[t001] * sfPhi[t011] + fmpolei[t200] * sfPhi[t210] + fmpolei[t020] * sfPhi[t030] + fmpolei[t002] * sfPhi[t012] + fmpolei[t110] * sfPhi[t120] + fmpolei[t101] * sfPhi[t111] + fmpolei[t011] * sfPhi[t021];
                    gz += fmpolei[t000] * sfPhi[t001] + fmpolei[t100] * sfPhi[t101] + fmpolei[t010] * sfPhi[t011] + fmpolei[t001] * sfPhi[t002] + fmpolei[t200] * sfPhi[t201] + fmpolei[t020] * sfPhi[t021] + fmpolei[t002] * sfPhi[t003] + fmpolei[t110] * sfPhi[t111] + fmpolei[t101] * sfPhi[t102] + fmpolei[t011] * sfPhi[t012];
                    gx *= nfftX;
                    gy *= nfftY;
                    gz *= nfftZ;
                    double recip[][] = crystal.getUnitCell().A;
                    double dfx = recip[0][0] * gx + recip[0][1] * gy + recip[0][2] * gz;
                    double dfy = recip[1][0] * gx + recip[1][1] * gy + recip[1][2] * gz;
                    double dfz = recip[2][0] * gx + recip[2][1] * gy + recip[2][2] * gz;
                    dfx *= 0.5 * ELECTRIC;
                    dfy *= 0.5 * ELECTRIC;
                    dfz *= 0.5 * ELECTRIC;
                    // Compute dipole torques
                    double tqx = -mpolei[t010] * sPhi[t001] + mpolei[t001] * sPhi[t010];
                    double tqy = -mpolei[t001] * sPhi[t100] + mpolei[t100] * sPhi[t001];
                    double tqz = -mpolei[t100] * sPhi[t010] + mpolei[t010] * sPhi[t100];
                    // Compute quadrupole torques
                    tqx -= 2.0 / 3.0 * (mpolei[t110] * sPhi[t101] + mpolei[t020] * sPhi[t011] + mpolei[t011] * sPhi[t002] - mpolei[t101] * sPhi[t110] - mpolei[t011] * sPhi[t020] - mpolei[t002] * sPhi[t011]);
                    tqy -= 2.0 / 3.0 * (mpolei[t101] * sPhi[t200] + mpolei[t011] * sPhi[t110] + mpolei[t002] * sPhi[t101] - mpolei[t200] * sPhi[t101] - mpolei[t110] * sPhi[t011] - mpolei[t101] * sPhi[t002]);
                    tqz -= 2.0 / 3.0 * (mpolei[t200] * sPhi[t110] + mpolei[t110] * sPhi[t020] + mpolei[t101] * sPhi[t011] - mpolei[t110] * sPhi[t200] - mpolei[t020] * sPhi[t110] - mpolei[t011] * sPhi[t101]);
                    tqx *= ELECTRIC;
                    tqy *= ELECTRIC;
                    tqz *= ELECTRIC;
                    sharedGrad[0].addAndGet(i, polarizationScale * dfx);
                    sharedGrad[1].addAndGet(i, polarizationScale * dfy);
                    sharedGrad[2].addAndGet(i, polarizationScale * dfz);
                    sharedTorque[0].addAndGet(i, polarizationScale * tqx);
                    sharedTorque[1].addAndGet(i, polarizationScale * tqy);
                    sharedTorque[2].addAndGet(i, polarizationScale * tqz);
                    if (lambdaTerm) {
                        shareddEdLdX[0].addAndGet(i, dEdLSign * dlPowPol * dfx);
                        shareddEdLdX[1].addAndGet(i, dEdLSign * dlPowPol * dfy);
                        shareddEdLdX[2].addAndGet(i, dEdLSign * dlPowPol * dfz);
                        shareddEdLTorque[0].addAndGet(i, dEdLSign * dlPowPol * tqx);
                        shareddEdLTorque[1].addAndGet(i, dEdLSign * dlPowPol * tqy);
                        shareddEdLTorque[2].addAndGet(i, dEdLSign * dlPowPol * tqz);
                    }
                }
            }
        }
        e *= 0.5 * ELECTRIC;
        if (lambdaTerm) {
            shareddEdLambda.addAndGet(dEdLSign * dlPowPol * e);
            sharedd2EdLambda2.addAndGet(dEdLSign * d2lPowPol * e);
        }
        return polarizationScale * e;
    }

    private class PermanentRealSpaceFieldRegion extends ParallelRegion {

        private final PermanentRealSpaceFieldLoop permanentRealSpaceFieldLoop[];

        public PermanentRealSpaceFieldRegion(int nt) {
            permanentRealSpaceFieldLoop = new PermanentRealSpaceFieldLoop[nt];
            for (int i = 0; i < nt; i++) {
                permanentRealSpaceFieldLoop[i] = new PermanentRealSpaceFieldLoop();
            }
        }

        @Override
        public void run() {
            try {
                execute(0, nAtoms - 1, permanentRealSpaceFieldLoop[getThreadIndex()]);
            } catch (Exception e) {
                String message = "Fatal exception computing the real space field in thread " + getThreadIndex() + "\n";
                logger.log(Level.SEVERE, message, e);
                System.exit(-1);
            }
        }

        private class PermanentRealSpaceFieldLoop extends IntegerForLoop {

            private final double mask_local[];
            private final double maskp_local[];
            private final double dx_local[];
            private final double rot_local[][];

            public PermanentRealSpaceFieldLoop() {
                super();
                mask_local = new double[nAtoms];
                maskp_local = new double[nAtoms];
                dx_local = new double[3];
                rot_local = new double[3][3];
                for (int i = 0; i < nAtoms; i++) {
                    mask_local[i] = 1.0;
                    maskp_local[i] = 1.0;
                }
            }

            @Override
            public IntegerSchedule schedule() {
                return pairWiseSchedule;
            }

            @Override
            public void run(int lb, int ub) {
                int lists[][] = neighborLists[0];
                int ewalds[][] = realSpaceLists[0];
                int counts[] = ewaldCounts[0];
                final double x[] = coordinates[0][0];
                final double y[] = coordinates[0][1];
                final double z[] = coordinates[0][2];
                final double mpole[][] = globalMultipole[0];
                /**
                 * Loop over atom chunk.
                 */
                for (int i = lb; i <= ub; i++) {
                    if (!use[i]) {
                        continue;
                    }
                    final double pdi = pdamp[i];
                    final double pti = thole[i];
                    final double xi = x[i];
                    final double yi = y[i];
                    final double zi = z[i];
                    final double globalMultipolei[] = mpole[i];
                    final double ci = globalMultipolei[0];
                    final double dix = globalMultipolei[t100];
                    final double diy = globalMultipolei[t010];
                    final double diz = globalMultipolei[t001];
                    final double qixx = globalMultipolei[t200] * oneThird;
                    final double qiyy = globalMultipolei[t020] * oneThird;
                    final double qizz = globalMultipolei[t002] * oneThird;
                    final double qixy = globalMultipolei[t110] * oneThird;
                    final double qixz = globalMultipolei[t101] * oneThird;
                    final double qiyz = globalMultipolei[t011] * oneThird;
                    /**
                     * Apply energy masking rules.
                     */
                    Atom ai = atoms[i];
                    for (Torsion torsion : ai.getTorsions()) {
                        Atom ak = torsion.get1_4(ai);
                        if (ak != null) {
                            int index = ak.xyzIndex - 1;
                            for (int k : ip11[i]) {
                                if (k == index) {
                                    maskp_local[index] = 0.5;
                                }
                            }
                        }
                    }
                    for (Angle angle : ai.getAngles()) {
                        Atom ak = angle.get1_3(ai);
                        if (ak != null) {
                            int index = ak.xyzIndex - 1;
                            maskp_local[index] = p13scale;
                        }
                    }
                    for (Bond bond : ai.getBonds()) {
                        int index = bond.get1_2(ai).xyzIndex - 1;
                        maskp_local[index] = p12scale;
                    }
                    /**
                     * Apply group based polarization masking rule.
                     */
                    for (int index : ip11[i]) {
                        mask_local[index] = d11scale;
                    }
                    /**
                     * Loop over the neighbor list.
                     */
                    final int list[] = lists[i];
                    int npair = list.length;
                    counts[i] = 0;
                    final int ewald[] = ewalds[i];
                    for (int j = 0; j < npair; j++) {
                        int k = list[j];
                        if (!use[k]) {
                            continue;
                        }
                        final double xk = x[k];
                        final double yk = y[k];
                        final double zk = z[k];
                        dx_local[0] = xk - xi;
                        dx_local[1] = yk - yi;
                        dx_local[2] = zk - zi;
                        final double r2 = crystal.image(dx_local);
                        if (r2 <= off2) {
                            ewald[counts[i]++] = k;
                            final double xr = dx_local[0];
                            final double yr = dx_local[1];
                            final double zr = dx_local[2];
                            final double pdk = pdamp[k];
                            final double ptk = thole[k];
                            final double globalMultipolek[] = mpole[k];
                            final double ck = globalMultipolek[t000];
                            final double dkx = globalMultipolek[t100];
                            final double dky = globalMultipolek[t010];
                            final double dkz = globalMultipolek[t001];
                            final double qkxx = globalMultipolek[t200] * oneThird;
                            final double qkyy = globalMultipolek[t020] * oneThird;
                            final double qkzz = globalMultipolek[t002] * oneThird;
                            final double qkxy = globalMultipolek[t110] * oneThird;
                            final double qkxz = globalMultipolek[t101] * oneThird;
                            final double qkyz = globalMultipolek[t011] * oneThird;
                            double r = sqrt(r2);
                            /**
                             * Calculate the error function damping terms.
                             */
                            final double ralpha = aewald * r;
                            final double exp2a = exp(-ralpha * ralpha);
                            final double rr1 = 1.0 / r;
                            final double rr2 = rr1 * rr1;
                            final double bn0 = erfc(ralpha) * rr1;
                            final double bn1 = (bn0 + an0 * exp2a) * rr2;
                            final double bn2 = (3.0 * bn1 + an1 * exp2a) * rr2;
                            final double bn3 = (5.0 * bn2 + an2 * exp2a) * rr2;
                            /**
                             * Compute the error function scaled and unscaled
                             * terms.
                             */
                            double scale3 = 1.0;
                            double scale5 = 1.0;
                            double scale7 = 1.0;
                            double damp = pdi * pdk;
                            double expdamp = 0.0;
                            if (damp != 0.0) {
                                r = sqrt(r2);
                                final double pgamma = min(pti, ptk);
                                final double rdamp = r / damp;
                                damp = -pgamma * rdamp * rdamp * rdamp;
                                if (damp > -50.0) {
                                    expdamp = exp(damp);
                                    scale3 = 1.0 - expdamp;
                                    scale5 = 1.0 - expdamp * (1.0 - damp);
                                    scale7 = 1.0 - expdamp * (1.0 - damp + 0.6 * damp * damp);
                                }
                            }
                            final double scale = mask_local[k];
                            final double scalep = maskp_local[k];
                            final double dsc3 = scale3 * scale;
                            final double dsc5 = scale5 * scale;
                            final double dsc7 = scale7 * scale;
                            final double psc3 = scale3 * scalep;
                            final double psc5 = scale5 * scalep;
                            final double psc7 = scale7 * scalep;
                            final double rr3 = rr1 * rr2;
                            final double rr5 = 3.0 * rr3 * rr2;
                            final double rr7 = 5.0 * rr5 * rr2;
                            final double drr3 = (1.0 - dsc3) * rr3;
                            final double drr5 = (1.0 - dsc5) * rr5;
                            final double drr7 = (1.0 - dsc7) * rr7;
                            final double prr3 = (1.0 - psc3) * rr3;
                            final double prr5 = (1.0 - psc5) * rr5;
                            final double prr7 = (1.0 - psc7) * rr7;
                            final double dir = dix * xr + diy * yr + diz * zr;
                            final double qix = 2.0 * (qixx * xr + qixy * yr + qixz * zr);
                            final double qiy = 2.0 * (qixy * xr + qiyy * yr + qiyz * zr);
                            final double qiz = 2.0 * (qixz * xr + qiyz * yr + qizz * zr);
                            final double qir = (qix * xr + qiy * yr + qiz * zr) * 0.5;
                            final double dkr = dkx * xr + dky * yr + dkz * zr;
                            final double qkx = 2.0 * (qkxx * xr + qkxy * yr + qkxz * zr);
                            final double qky = 2.0 * (qkxy * xr + qkyy * yr + qkyz * zr);
                            final double qkz = 2.0 * (qkxz * xr + qkyz * yr + qkzz * zr);
                            final double qkr = (qkx * xr + qky * yr + qkz * zr) * 0.5;
                            final double fimx = -xr * (bn1 * ck - bn2 * dkr + bn3 * qkr) - bn1 * dkx + bn2 * qkx;
                            final double fimy = -yr * (bn1 * ck - bn2 * dkr + bn3 * qkr) - bn1 * dky + bn2 * qky;
                            final double fimz = -zr * (bn1 * ck - bn2 * dkr + bn3 * qkr) - bn1 * dkz + bn2 * qkz;
                            final double fkmx = xr * (bn1 * ci + bn2 * dir + bn3 * qir) - bn1 * dix - bn2 * qix;
                            final double fkmy = yr * (bn1 * ci + bn2 * dir + bn3 * qir) - bn1 * diy - bn2 * qiy;
                            final double fkmz = zr * (bn1 * ci + bn2 * dir + bn3 * qir) - bn1 * diz - bn2 * qiz;
                            final double fidx = -xr * (drr3 * ck - drr5 * dkr + drr7 * qkr) - drr3 * dkx + drr5 * qkx;
                            final double fidy = -yr * (drr3 * ck - drr5 * dkr + drr7 * qkr) - drr3 * dky + drr5 * qky;
                            final double fidz = -zr * (drr3 * ck - drr5 * dkr + drr7 * qkr) - drr3 * dkz + drr5 * qkz;
                            final double fkdx = xr * (drr3 * ci + drr5 * dir + drr7 * qir) - drr3 * dix - drr5 * qix;
                            final double fkdy = yr * (drr3 * ci + drr5 * dir + drr7 * qir) - drr3 * diy - drr5 * qiy;
                            final double fkdz = zr * (drr3 * ci + drr5 * dir + drr7 * qir) - drr3 * diz - drr5 * qiz;
                            final double fipx = -xr * (prr3 * ck - prr5 * dkr + prr7 * qkr) - prr3 * dkx + prr5 * qkx;
                            final double fipy = -yr * (prr3 * ck - prr5 * dkr + prr7 * qkr) - prr3 * dky + prr5 * qky;
                            final double fipz = -zr * (prr3 * ck - prr5 * dkr + prr7 * qkr) - prr3 * dkz + prr5 * qkz;
                            final double fkpx = xr * (prr3 * ci + prr5 * dir + prr7 * qir) - prr3 * dix - prr5 * qix;
                            final double fkpy = yr * (prr3 * ci + prr5 * dir + prr7 * qir) - prr3 * diy - prr5 * qiy;
                            final double fkpz = zr * (prr3 * ci + prr5 * dir + prr7 * qir) - prr3 * diz - prr5 * qiz;
                            sharedPermanentField[0].addAndGet(i, fimx - fidx);
                            sharedPermanentField[1].addAndGet(i, fimy - fidy);
                            sharedPermanentField[2].addAndGet(i, fimz - fidz);
                            sharedPermanentField[0].addAndGet(k, fkmx - fkdx);
                            sharedPermanentField[1].addAndGet(k, fkmy - fkdy);
                            sharedPermanentField[2].addAndGet(k, fkmz - fkdz);
                            sharedPermanentFieldCR[0].addAndGet(i, fimx - fipx);
                            sharedPermanentFieldCR[1].addAndGet(i, fimy - fipy);
                            sharedPermanentFieldCR[2].addAndGet(i, fimz - fipz);
                            sharedPermanentFieldCR[0].addAndGet(k, fkmx - fkpx);
                            sharedPermanentFieldCR[1].addAndGet(k, fkmy - fkpy);
                            sharedPermanentFieldCR[2].addAndGet(k, fkmz - fkpz);
                        }
                    }
                    for (Torsion torsion : ai.getTorsions()) {
                        Atom ak = torsion.get1_4(ai);
                        if (ak != null) {
                            int index = ak.xyzIndex - 1;
                            maskp_local[index] = 1.0;
                        }
                    }
                    for (Angle angle : ai.getAngles()) {
                        Atom ak = angle.get1_3(ai);
                        if (ak != null) {
                            int index = ak.xyzIndex - 1;
                            maskp_local[index] = 1.0;
                        }
                    }
                    for (Bond bond : ai.getBonds()) {
                        int index = bond.get1_2(ai).xyzIndex - 1;
                        maskp_local[index] = 1.0;
                    }
                    for (int index : ip11[i]) {
                        mask_local[index] = 1.0;
                    }
                }
                /**
                 * Loop over symmetry mates.
                 */
                for (int iSymm = 1; iSymm < nSymm; iSymm++) {
                    SymOp symOp = crystal.spaceGroup.getSymOp(iSymm);
                    lists = neighborLists[iSymm];
                    ewalds = realSpaceLists[iSymm];
                    counts = ewaldCounts[iSymm];
                    double xs[] = coordinates[iSymm][0];
                    double ys[] = coordinates[iSymm][1];
                    double zs[] = coordinates[iSymm][2];
                    double mpoles[][] = globalMultipole[iSymm];
                    /**
                     * Loop over atoms in a chunk of the asymmetric unit.
                     */
                    for (int i = lb; i <= ub; i++) {
                        if (!use[i]) {
                            continue;
                        }
                        final double pdi = pdamp[i];
                        final double pti = thole[i];
                        final double multipolei[] = mpole[i];
                        final double ci = multipolei[t000];
                        final double dix = multipolei[t100];
                        final double diy = multipolei[t010];
                        final double diz = multipolei[t001];
                        final double qixx = multipolei[t200] * oneThird;
                        final double qiyy = multipolei[t020] * oneThird;
                        final double qizz = multipolei[t002] * oneThird;
                        final double qixy = multipolei[t110] * oneThird;
                        final double qixz = multipolei[t101] * oneThird;
                        final double qiyz = multipolei[t011] * oneThird;
                        final double xi = x[i];
                        final double yi = y[i];
                        final double zi = z[i];
                        /**
                         * Loop over the neighbor list.
                         */
                        final int list[] = lists[i];
                        final int npair = list.length;
                        counts[i] = 0;
                        final int ewald[] = ewalds[i];
                        for (int j = 0; j < npair; j++) {
                            int k = list[j];
                            if (!use[k]) {
                                continue;
                            }
                            final double xk = xs[k];
                            final double yk = ys[k];
                            final double zk = zs[k];
                            dx_local[0] = xk - xi;
                            dx_local[1] = yk - yi;
                            dx_local[2] = zk - zi;
                            final double r2 = crystal.image(dx_local);
                            if (r2 <= off2) {
                                ewald[counts[i]++] = k;
                                double selfScale = 1.0;
                                if (i == k) {
                                    selfScale = 0.5;
                                }
                                final double xr = dx_local[0];
                                final double yr = dx_local[1];
                                final double zr = dx_local[2];
                                final double pdk = pdamp[k];
                                final double ptk = thole[k];
                                final double multipolek[] = mpoles[k];
                                final double ck = multipolek[t000];
                                final double dkx = multipolek[t100];
                                final double dky = multipolek[t010];
                                final double dkz = multipolek[t001];
                                final double qkxx = multipolek[t200] * oneThird;
                                final double qkyy = multipolek[t020] * oneThird;
                                final double qkzz = multipolek[t002] * oneThird;
                                final double qkxy = multipolek[t110] * oneThird;
                                final double qkxz = multipolek[t101] * oneThird;
                                final double qkyz = multipolek[t011] * oneThird;
                                final double r = sqrt(r2);
                                /**
                                 * Calculate the error function damping terms.
                                 */
                                final double ralpha = aewald * r;
                                final double exp2a = exp(-ralpha * ralpha);
                                final double rr1 = 1.0 / r;
                                final double rr2 = rr1 * rr1;
                                final double bn0 = erfc(ralpha) * rr1;
                                final double bn1 = (bn0 + an0 * exp2a) * rr2;
                                final double bn2 = (3.0 * bn1 + an1 * exp2a) * rr2;
                                final double bn3 = (5.0 * bn2 + an2 * exp2a) * rr2;
                                /**
                                 * Compute the error function scaled and
                                 * unscaled terms.
                                 */
                                double scale3 = 1.0;
                                double scale5 = 1.0;
                                double scale7 = 1.0;
                                double damp = pdi * pdk;
                                double expdamp = 0.0;
                                if (damp != 0.0) {
                                    final double pgamma = min(pti, ptk);
                                    final double rdamp = r / damp;
                                    damp = -pgamma * rdamp * rdamp * rdamp;
                                    if (damp > -50.0) {
                                        expdamp = exp(damp);
                                        scale3 = 1.0 - expdamp;
                                        scale5 = 1.0 - expdamp * (1.0 - damp);
                                        scale7 = 1.0 - expdamp * (1.0 - damp + 0.6 * damp * damp);
                                    }
                                }
                                final double dsc3 = scale3;
                                final double dsc5 = scale5;
                                final double dsc7 = scale7;
                                final double rr3 = rr1 * rr2;
                                final double rr5 = 3.0 * rr3 * rr2;
                                final double rr7 = 5.0 * rr5 * rr2;
                                final double drr3 = (1.0 - dsc3) * rr3;
                                final double drr5 = (1.0 - dsc5) * rr5;
                                final double drr7 = (1.0 - dsc7) * rr7;
                                final double dkr = dkx * xr + dky * yr + dkz * zr;
                                final double qkx = 2.0 * (qkxx * xr + qkxy * yr + qkxz * zr);
                                final double qky = 2.0 * (qkxy * xr + qkyy * yr + qkyz * zr);
                                final double qkz = 2.0 * (qkxz * xr + qkyz * yr + qkzz * zr);
                                final double qkr = (qkx * xr + qky * yr + qkz * zr) * 0.5;
                                final double dir = dix * xr + diy * yr + diz * zr;
                                final double qix = 2.0 * (qixx * xr + qixy * yr + qixz * zr);
                                final double qiy = 2.0 * (qixy * xr + qiyy * yr + qiyz * zr);
                                final double qiz = 2.0 * (qixz * xr + qiyz * yr + qizz * zr);
                                final double qir = (qix * xr + qiy * yr + qiz * zr) * 0.5;
                                final double fimx = -xr * (bn1 * ck - bn2 * dkr + bn3 * qkr) - bn1 * dkx + bn2 * qkx;
                                final double fimy = -yr * (bn1 * ck - bn2 * dkr + bn3 * qkr) - bn1 * dky + bn2 * qky;
                                final double fimz = -zr * (bn1 * ck - bn2 * dkr + bn3 * qkr) - bn1 * dkz + bn2 * qkz;
                                final double fkmx = xr * (bn1 * ci + bn2 * dir + bn3 * qir) - bn1 * dix - bn2 * qix;
                                final double fkmy = yr * (bn1 * ci + bn2 * dir + bn3 * qir) - bn1 * diy - bn2 * qiy;
                                final double fkmz = zr * (bn1 * ci + bn2 * dir + bn3 * qir) - bn1 * diz - bn2 * qiz;
                                final double fidx = -xr * (drr3 * ck - drr5 * dkr + drr7 * qkr) - drr3 * dkx + drr5 * qkx;
                                final double fidy = -yr * (drr3 * ck - drr5 * dkr + drr7 * qkr) - drr3 * dky + drr5 * qky;
                                final double fidz = -zr * (drr3 * ck - drr5 * dkr + drr7 * qkr) - drr3 * dkz + drr5 * qkz;
                                final double fkdx = xr * (drr3 * ci + drr5 * dir + drr7 * qir) - drr3 * dix - drr5 * qix;
                                final double fkdy = yr * (drr3 * ci + drr5 * dir + drr7 * qir) - drr3 * diy - drr5 * qiy;
                                final double fkdz = zr * (drr3 * ci + drr5 * dir + drr7 * qir) - drr3 * diz - drr5 * qiz;
                                final double fix = selfScale * (fimx - fidx);
                                final double fiy = selfScale * (fimy - fidy);
                                final double fiz = selfScale * (fimz - fidz);
                                sharedPermanentField[0].addAndGet(i, fix);
                                sharedPermanentField[1].addAndGet(i, fiy);
                                sharedPermanentField[2].addAndGet(i, fiz);
                                sharedPermanentFieldCR[0].addAndGet(i, fix);
                                sharedPermanentFieldCR[1].addAndGet(i, fiy);
                                sharedPermanentFieldCR[2].addAndGet(i, fiz);
                                dx_local[0] = selfScale * (fkmx - fkdx);
                                dx_local[1] = selfScale * (fkmy - fkdy);
                                dx_local[2] = selfScale * (fkmz - fkdz);
                                crystal.applyTransSymRot(dx_local, dx_local, symOp, rot_local);
                                sharedPermanentField[0].addAndGet(k, dx_local[0]);
                                sharedPermanentField[1].addAndGet(k, dx_local[1]);
                                sharedPermanentField[2].addAndGet(k, dx_local[2]);
                                sharedPermanentFieldCR[0].addAndGet(k, dx_local[0]);
                                sharedPermanentFieldCR[1].addAndGet(k, dx_local[1]);
                                sharedPermanentFieldCR[2].addAndGet(k, dx_local[2]);
                            }
                        }
                    }
                }
            }
        }
    }

    private class InducedDipoleRealSpaceFieldRegion extends ParallelRegion {

        private final PolarizationRealSpaceFieldLoop polarizationRealSpaceFieldLoop[];

        public InducedDipoleRealSpaceFieldRegion(int nt) {
            polarizationRealSpaceFieldLoop = new PolarizationRealSpaceFieldLoop[nt];
            for (int i = 0; i < nt; i++) {
                polarizationRealSpaceFieldLoop[i] = new PolarizationRealSpaceFieldLoop();
            }
        }

        @Override
        public void run() {
            try {
                int ti = getThreadIndex();
                execute(0, nAtoms - 1, polarizationRealSpaceFieldLoop[ti]);
            } catch (Exception e) {
                String message = "Fatal exception computing the induced real space field in thread " + getThreadIndex() + "\n";
                logger.log(Level.SEVERE, message, e);
            }
        }

        private class PolarizationRealSpaceFieldLoop extends IntegerForLoop {

            private int list[], lists[][], counts[];
            private int npair, i, j, k, iSymm;
            private double fx, fy, fz;
            private double px, py, pz;
            private double xi, yi, zi;
            private double pdi, pdk, pti, ptk;
            private double dipolei[], dipolepi[];
            private double uix, uiy, uiz;
            private double pix, piy, piz;
            private double xr, yr, zr;
            private double dipolek[], dipolepk[];
            private double ukx, uky, ukz;
            private double pkx, pky, pkz;
            private double bn0, bn1, bn2;
            private double scale3, scale5, damp, expdamp, pgamma, rdamp;
            private double r, ralpha, exp2a, r2, rr1, rr2, rr3, rr5;
            private double uir, ukr, pir, pkr;
            private double bn2ukr, bn2uir, bn2pkr, bn2pir;
            private double rr5ukr, rr5uir, rr5pkr, rr5pir;
            private double fimx, fimy, fimz;
            private double fkmx, fkmy, fkmz;
            private double fidx, fidy, fidz;
            private double fkdx, fkdy, fkdz;
            private double pimx, pimy, pimz;
            private double pkmx, pkmy, pkmz;
            private double pidx, pidy, pidz;
            private double pkdx, pkdy, pkdz;
            private double xs[], ys[], zs[];
            private double inds[][], indps[][];
            private final double dx_local[];
            private final double rot_local[][];
            private final double x[] = coordinates[0][0];
            private final double y[] = coordinates[0][1];
            private final double z[] = coordinates[0][2];
            private final double ind[][] = inducedDipole[0];
            private final double inp[][] = inducedDipoleCR[0];

            public PolarizationRealSpaceFieldLoop() {
                dx_local = new double[3];
                rot_local = new double[3][3];
            }

            @Override
            public IntegerSchedule schedule() {
                return pairWiseSchedule;
            }

            @Override
            public void run(int lb, int ub) {
                /**
                 * Loop over a chunk of atoms.
                 */
                lists = realSpaceLists[0];
                counts = ewaldCounts[0];
                for (i = lb; i <= ub; i++) {
                    if (!use[i]) {
                        continue;
                    }
                    fx = 0.0;
                    fy = 0.0;
                    fz = 0.0;
                    px = 0.0;
                    py = 0.0;
                    pz = 0.0;
                    xi = x[i];
                    yi = y[i];
                    zi = z[i];
                    dipolei = ind[i];
                    uix = dipolei[0];
                    uiy = dipolei[1];
                    uiz = dipolei[2];
                    dipolepi = inp[i];
                    pix = dipolepi[0];
                    piy = dipolepi[1];
                    piz = dipolepi[2];
                    pdi = pdamp[i];
                    pti = thole[i];
                    boolean softCorei[] = softCore[0];
                    if (isSoft[i]) {
                        softCorei = softCore[1];
                    }
                    /**
                     * Loop over the neighbor list.
                     */
                    list = lists[i];
                    npair = counts[i];
                    for (j = 0; j < npair; j++) {
                        k = list[j];
                        if (!use[k]) {
                            continue;
                        }
                        pdk = pdamp[k];
                        ptk = thole[k];
                        dx_local[0] = x[k] - xi;
                        dx_local[1] = y[k] - yi;
                        dx_local[2] = z[k] - zi;
                        r2 = crystal.image(dx_local);
                        xr = dx_local[0];
                        yr = dx_local[1];
                        zr = dx_local[2];
                        dipolek = ind[k];
                        ukx = dipolek[0];
                        uky = dipolek[1];
                        ukz = dipolek[2];
                        dipolepk = inp[k];
                        pkx = dipolepk[0];
                        pky = dipolepk[1];
                        pkz = dipolepk[2];
                        uir = uix * xr + uiy * yr + uiz * zr;
                        ukr = ukx * xr + uky * yr + ukz * zr;
                        pir = pix * xr + piy * yr + piz * zr;
                        pkr = pkx * xr + pky * yr + pkz * zr;
                        /**
                         * Calculate the error function damping terms.
                         */
                        r = sqrt(r2);
                        rr1 = 1.0 / r;
                        rr2 = rr1 * rr1;
                        ralpha = aewald * r;
                        exp2a = exp(-ralpha * ralpha);
                        bn0 = erfc(ralpha) * rr1;
                        bn1 = (bn0 + an0 * exp2a) * rr2;
                        bn2 = (3.0 * bn1 + an1 * exp2a) * rr2;
                        scale3 = 1.0;
                        scale5 = 1.0;
                        damp = pdi * pdk;
                        expdamp = 0.0;
                        if (damp != 0.0) {
                            pgamma = min(pti, ptk);
                            rdamp = r / damp;
                            damp = -pgamma * rdamp * rdamp * rdamp;
                            if (damp > -50.0) {
                                expdamp = exp(damp);
                                scale3 = 1.0 - expdamp;
                                scale5 = 1.0 - expdamp * (1.0 - damp);
                            }
                        }
                        rr3 = rr1 * rr2;
                        rr5 = 3.0 * rr3 * rr2;
                        rr3 *= (1.0 - scale3);
                        rr5 *= (1.0 - scale5);
                        bn2ukr = bn2 * ukr;
                        bn2uir = bn2 * uir;
                        bn2pkr = bn2 * pkr;
                        bn2pir = bn2 * pir;
                        rr5ukr = rr5 * ukr;
                        rr5uir = rr5 * uir;
                        rr5pkr = rr5 * pkr;
                        rr5pir = rr5 * pir;
                        fimx = -bn1 * ukx + bn2ukr * xr;
                        fimy = -bn1 * uky + bn2ukr * yr;
                        fimz = -bn1 * ukz + bn2ukr * zr;
                        fkmx = -bn1 * uix + bn2uir * xr;
                        fkmy = -bn1 * uiy + bn2uir * yr;
                        fkmz = -bn1 * uiz + bn2uir * zr;
                        fidx = -rr3 * ukx + rr5ukr * xr;
                        fidy = -rr3 * uky + rr5ukr * yr;
                        fidz = -rr3 * ukz + rr5ukr * zr;
                        fkdx = -rr3 * uix + rr5uir * xr;
                        fkdy = -rr3 * uiy + rr5uir * yr;
                        fkdz = -rr3 * uiz + rr5uir * zr;
                        pimx = -bn1 * pkx + bn2pkr * xr;
                        pimy = -bn1 * pky + bn2pkr * yr;
                        pimz = -bn1 * pkz + bn2pkr * zr;
                        pkmx = -bn1 * pix + bn2pir * xr;
                        pkmy = -bn1 * piy + bn2pir * yr;
                        pkmz = -bn1 * piz + bn2pir * zr;
                        pidx = -rr3 * pkx + rr5pkr * xr;
                        pidy = -rr3 * pky + rr5pkr * yr;
                        pidz = -rr3 * pkz + rr5pkr * zr;
                        pkdx = -rr3 * pix + rr5pir * xr;
                        pkdy = -rr3 * piy + rr5pir * yr;
                        pkdz = -rr3 * piz + rr5pir * zr;
                        fx += (fimx - fidx);
                        fy += (fimy - fidy);
                        fz += (fimz - fidz);
                        px += (pimx - pidx);
                        py += (pimy - pidy);
                        pz += (pimz - pidz);
                        sharedMutualField[0].addAndGet(k, fkmx - fkdx);
                        sharedMutualField[1].addAndGet(k, fkmy - fkdy);
                        sharedMutualField[2].addAndGet(k, fkmz - fkdz);
                        sharedMutualFieldCR[0].addAndGet(k, pkmx - pkdx);
                        sharedMutualFieldCR[1].addAndGet(k, pkmy - pkdy);
                        sharedMutualFieldCR[2].addAndGet(k, pkmz - pkdz);
                    }
                    sharedMutualField[0].addAndGet(i, fx);
                    sharedMutualField[1].addAndGet(i, fy);
                    sharedMutualField[2].addAndGet(i, fz);
                    sharedMutualFieldCR[0].addAndGet(i, px);
                    sharedMutualFieldCR[1].addAndGet(i, py);
                    sharedMutualFieldCR[2].addAndGet(i, pz);
                }
                /**
                 * Loop over symmetry mates.
                 */
                for (iSymm = 1; iSymm < nSymm; iSymm++) {
                    SymOp symOp = crystal.spaceGroup.getSymOp(iSymm);
                    lists = realSpaceLists[iSymm];
                    counts = ewaldCounts[iSymm];
                    xs = coordinates[iSymm][0];
                    ys = coordinates[iSymm][1];
                    zs = coordinates[iSymm][2];
                    inds = inducedDipole[iSymm];
                    indps = inducedDipoleCR[iSymm];
                    /**
                     * Loop over a chunk of atoms.
                     */
                    for (i = lb; i <= ub; i++) {
                        if (!use[i]) {
                            continue;
                        }
                        fx = 0.0;
                        fy = 0.0;
                        fz = 0.0;
                        px = 0.0;
                        py = 0.0;
                        pz = 0.0;
                        xi = x[i];
                        yi = y[i];
                        zi = z[i];
                        dipolei = ind[i];
                        dipolepi = inp[i];
                        uix = dipolei[0];
                        uiy = dipolei[1];
                        uiz = dipolei[2];
                        pix = dipolepi[0];
                        piy = dipolepi[1];
                        piz = dipolepi[2];
                        pdi = pdamp[i];
                        pti = thole[i];
                        /**
                         * Loop over the neighbor list.
                         */
                        list = lists[i];
                        npair = counts[i];
                        for (j = 0; j < npair; j++) {
                            k = list[j];
                            if (!use[k]) {
                                continue;
                            }
                            double selfScale = 1.0;
                            if (i == k) {
                                selfScale = 0.5;
                            }
                            pdk = pdamp[k];
                            ptk = thole[k];
                            dx_local[0] = xs[k] - xi;
                            dx_local[1] = ys[k] - yi;
                            dx_local[2] = zs[k] - zi;
                            r2 = crystal.image(dx_local);
                            xr = dx_local[0];
                            yr = dx_local[1];
                            zr = dx_local[2];
                            dipolek = inds[k];
                            dipolepk = indps[k];
                            ukx = dipolek[0];
                            uky = dipolek[1];
                            ukz = dipolek[2];
                            pkx = dipolepk[0];
                            pky = dipolepk[1];
                            pkz = dipolepk[2];
                            uir = uix * xr + uiy * yr + uiz * zr;
                            pir = pix * xr + piy * yr + piz * zr;
                            ukr = ukx * xr + uky * yr + ukz * zr;
                            pkr = pkx * xr + pky * yr + pkz * zr;
                            /**
                             * Calculate the error function damping terms.
                             */
                            r = sqrt(r2);
                            rr1 = 1.0 / r;
                            rr2 = rr1 * rr1;
                            ralpha = aewald * r;
                            exp2a = exp(-ralpha * ralpha);
                            bn0 = erfc(ralpha) * rr1;
                            bn1 = (bn0 + an0 * exp2a) * rr2;
                            bn2 = (3.0 * bn1 + an1 * exp2a) * rr2;
                            scale3 = 1.0;
                            scale5 = 1.0;
                            damp = pdi * pdk;
                            expdamp = 0.0;
                            if (damp != 0.0) {
                                pgamma = min(pti, ptk);
                                rdamp = r / damp;
                                damp = -pgamma * rdamp * rdamp * rdamp;
                                if (damp > -50.0) {
                                    expdamp = exp(damp);
                                    scale3 = 1.0 - expdamp;
                                    scale5 = 1.0 - expdamp * (1.0 - damp);
                                }
                            }
                            rr3 = rr1 * rr2;
                            rr5 = 3.0 * rr3 * rr2;
                            rr3 *= (1.0 - scale3);
                            rr5 *= (1.0 - scale5);
                            bn2uir = bn2 * uir;
                            bn2pir = bn2 * pir;
                            rr5uir = rr5 * uir;
                            rr5pir = rr5 * pir;
                            bn2ukr = bn2 * ukr;
                            bn2pkr = bn2 * pkr;
                            rr5ukr = rr5 * ukr;
                            rr5pkr = rr5 * pkr;
                            fimx = -bn1 * ukx + bn2ukr * xr;
                            fimy = -bn1 * uky + bn2ukr * yr;
                            fimz = -bn1 * ukz + bn2ukr * zr;
                            fidx = -rr3 * ukx + rr5ukr * xr;
                            fidy = -rr3 * uky + rr5ukr * yr;
                            fidz = -rr3 * ukz + rr5ukr * zr;
                            pimx = -bn1 * pkx + bn2pkr * xr;
                            pimy = -bn1 * pky + bn2pkr * yr;
                            pimz = -bn1 * pkz + bn2pkr * zr;
                            pidx = -rr3 * pkx + rr5pkr * xr;
                            pidy = -rr3 * pky + rr5pkr * yr;
                            pidz = -rr3 * pkz + rr5pkr * zr;
                            fkmx = -bn1 * uix + bn2uir * xr;
                            fkmy = -bn1 * uiy + bn2uir * yr;
                            fkmz = -bn1 * uiz + bn2uir * zr;
                            fkdx = -rr3 * uix + rr5uir * xr;
                            fkdy = -rr3 * uiy + rr5uir * yr;
                            fkdz = -rr3 * uiz + rr5uir * zr;
                            pkmx = -bn1 * pix + bn2pir * xr;
                            pkmy = -bn1 * piy + bn2pir * yr;
                            pkmz = -bn1 * piz + bn2pir * zr;
                            pkdx = -rr3 * pix + rr5pir * xr;
                            pkdy = -rr3 * piy + rr5pir * yr;
                            pkdz = -rr3 * piz + rr5pir * zr;
                            fx += selfScale * (fimx - fidx);
                            fy += selfScale * (fimy - fidy);
                            fz += selfScale * (fimz - fidz);
                            px += selfScale * (pimx - pidx);
                            py += selfScale * (pimy - pidy);
                            pz += selfScale * (pimz - pidz);
                            dx_local[0] = selfScale * (fkmx - fkdx);
                            dx_local[1] = selfScale * (fkmy - fkdy);
                            dx_local[2] = selfScale * (fkmz - fkdz);
                            crystal.applyTransSymRot(dx_local, dx_local, symOp, rot_local);
                            sharedMutualField[0].addAndGet(k, dx_local[0]);
                            sharedMutualField[1].addAndGet(k, dx_local[1]);
                            sharedMutualField[2].addAndGet(k, dx_local[2]);
                            dx_local[0] = selfScale * (pkmx - pkdx);
                            dx_local[1] = selfScale * (pkmy - pkdy);
                            dx_local[2] = selfScale * (pkmz - pkdz);
                            crystal.applyTransSymRot(dx_local, dx_local, symOp, rot_local);
                            sharedMutualFieldCR[0].addAndGet(k, dx_local[0]);
                            sharedMutualFieldCR[1].addAndGet(k, dx_local[1]);
                            sharedMutualFieldCR[2].addAndGet(k, dx_local[2]);
                        }
                        sharedMutualField[0].addAndGet(i, fx);
                        sharedMutualField[1].addAndGet(i, fy);
                        sharedMutualField[2].addAndGet(i, fz);
                        sharedMutualFieldCR[0].addAndGet(i, px);
                        sharedMutualFieldCR[1].addAndGet(i, py);
                        sharedMutualFieldCR[2].addAndGet(i, pz);
                    }
                }
            }
        }
    }

    /**
     * The Real Space Gradient Region class parallelizes evaluation of the real
     * space energy and gradients using an array of Real Space Gradient Loops.
     */
    private class RealSpaceEnergyRegion extends ParallelRegion {

        private final SharedDouble sharedPermanentEnergy;
        private final SharedDouble sharedPolarizationEnergy;
        private final SharedInteger sharedInteractions;
        private final RealSpaceEnergyLoop realSpaceEnergyLoop[];
        private long overheadTime;

        public RealSpaceEnergyRegion(int nt) {
            super();
            sharedPermanentEnergy = new SharedDouble();
            sharedPolarizationEnergy = new SharedDouble();
            sharedInteractions = new SharedInteger();
            realSpaceEnergyLoop = new RealSpaceEnergyLoop[nt];
            for (int i = 0; i < nt; i++) {
                realSpaceEnergyLoop[i] = new RealSpaceEnergyLoop();
            }
        }

        public double getPermanentEnergy() {
            return sharedPermanentEnergy.get();
        }

        public double getPolarizationEnergy() {
            return sharedPolarizationEnergy.get();
        }

        public int getInteractions() {
            return sharedInteractions.get();
        }

        @Override
        public void start() {
            overheadTime = System.nanoTime();
            sharedPermanentEnergy.set(0.0);
            sharedPolarizationEnergy.set(0.0);
            sharedInteractions.set(0);
        }

        @Override
        public void run() {
            try {
                execute(0, nAtoms - 1, realSpaceEnergyLoop[getThreadIndex()]);
            } catch (Exception e) {
                String message = "Fatal exception computing the real space energy in thread " + getThreadIndex() + "\n";
                logger.log(Level.SEVERE, message, e);
            }
        }

        @Override
        public void finish() {
            long computeTime = 0;
            for (int i = 0; i < maxThreads; i++) {
                computeTime += realSpaceEnergyLoop[i].getComputeTime();
            }
            overheadTime = System.nanoTime() - overheadTime;
            overheadTime = overheadTime - computeTime / maxThreads;
            //double compute = (double) computeTime / threadCount * toSeconds;
            //double overhead = (double) overheadTime * toSeconds;
            //double efficiency = compute / (compute + overhead) * 100;
            /*
             * logger.info(format("Real Space Energy Parallel Performance\n"
             * + "Avg. Compute Time  %10.3f (sec)\n" +
             * "Overhead Time      %10.3f (sec)\n" +
             * "Efficiency         %10.3f\n", compute, overhead, efficiency));
             */
        }

        /**
         * The Real Space Gradient Loop class contains methods and thread local
         * variables to parallelize the evaluation of the real space permanent
         * and polarization energies and gradients.
         */
        private class RealSpaceEnergyLoop extends IntegerForLoop {

            private long computeTime;
            private double ci;
            private double dix, diy, diz;
            private double qixx, qiyy, qizz, qixy, qixz, qiyz;
            private double ck;
            private double dkx, dky, dkz;
            private double qkxx, qkyy, qkzz, qkxy, qkxz, qkyz;
            private double uix, uiy, uiz;
            private double pix, piy, piz;
            private double xr, yr, zr;
            private double ukx, uky, ukz;
            private double pkx, pky, pkz;
            private double bn0, bn1, bn2, bn3, bn4, bn5, bn6;
            private double r2, rr1, rr2, rr3, rr5, rr7, rr9, rr11, rr13;
            private double scale, scale3, scale5, scale7;
            private double scalep, scaled;
            private double ddsc3x, ddsc3y, ddsc3z;
            private double ddsc5x, ddsc5y, ddsc5z;
            private double ddsc7x, ddsc7y, ddsc7z;
            private double beta, l2;
            private boolean soft;
            private double selfScale;
            private double permanentEnergy;
            private double inducedEnergy;
            private double dUdL, d2UdL2;
            private int i, k, iSymm, count;
            private SymOp symOp;
            private final double dx_local[];
            private final double gx_local[];
            private final double gy_local[];
            private final double gz_local[];
            private final double tx_local[];
            private final double ty_local[];
            private final double tz_local[];
            private final double gxk_local[];
            private final double gyk_local[];
            private final double gzk_local[];
            private final double txk_local[];
            private final double tyk_local[];
            private final double tzk_local[];
            private final double lx_local[];
            private final double ly_local[];
            private final double lz_local[];
            private final double ltx_local[];
            private final double lty_local[];
            private final double ltz_local[];
            private final double lxk_local[];
            private final double lyk_local[];
            private final double lzk_local[];
            private final double ltxk_local[];
            private final double ltyk_local[];
            private final double ltzk_local[];
            private final double masking_local[];
            private final double maskingp_local[];
            private final double maskingd_local[];
            private final double rot_local[][];
            private final double work[][];

            public RealSpaceEnergyLoop() {
                super();
                gx_local = new double[nAtoms];
                gy_local = new double[nAtoms];
                gz_local = new double[nAtoms];
                tx_local = new double[nAtoms];
                ty_local = new double[nAtoms];
                tz_local = new double[nAtoms];
                txk_local = new double[nAtoms];
                tyk_local = new double[nAtoms];
                tzk_local = new double[nAtoms];
                gxk_local = new double[nAtoms];
                gyk_local = new double[nAtoms];
                gzk_local = new double[nAtoms];

                lx_local = new double[nAtoms];
                ly_local = new double[nAtoms];
                lz_local = new double[nAtoms];
                ltx_local = new double[nAtoms];
                lty_local = new double[nAtoms];
                ltz_local = new double[nAtoms];
                lxk_local = new double[nAtoms];
                lyk_local = new double[nAtoms];
                lzk_local = new double[nAtoms];
                ltxk_local = new double[nAtoms];
                ltyk_local = new double[nAtoms];
                ltzk_local = new double[nAtoms];
                masking_local = new double[nAtoms];
                maskingp_local = new double[nAtoms];
                maskingd_local = new double[nAtoms];
                dx_local = new double[3];
                work = new double[15][3];
                rot_local = new double[3][3];
            }

            public long getComputeTime() {
                return computeTime;
            }

            @Override
            public IntegerSchedule schedule() {
                return pairWiseSchedule;
            }

            @Override
            public void start() {
                permanentEnergy = 0.0;
                inducedEnergy = 0.0;
                count = 0;
                for (int j = 0; j < nAtoms; j++) {
                    masking_local[j] = 1.0;
                    maskingp_local[j] = 1.0;
                    maskingd_local[j] = 1.0;
                }
                if (gradient) {
                    for (int j = 0; j < nAtoms; j++) {
                        gx_local[j] = 0.0;
                        gy_local[j] = 0.0;
                        gz_local[j] = 0.0;
                        tx_local[j] = 0.0;
                        ty_local[j] = 0.0;
                        tz_local[j] = 0.0;
                    }
                }
                if (lambdaTerm) {
                    dUdL = 0.0;
                    d2UdL2 = 0.0;
                    for (int j = 0; j < nAtoms; j++) {
                        lx_local[j] = 0.0;
                        ly_local[j] = 0.0;
                        lz_local[j] = 0.0;
                        ltx_local[j] = 0.0;
                        lty_local[j] = 0.0;
                        ltz_local[j] = 0.0;
                    }
                }
                computeTime = 0;
            }

            @Override
            public void run(int lb, int ub) {
                long startTime = System.nanoTime();
                List<SymOp> symOps = crystal.spaceGroup.symOps;
                int symm = nSymm;
                if (!useSymmetry) {
                    symm = 1;
                }
                for (iSymm = 0; iSymm < symm; iSymm++) {

                    symOp = symOps.get(iSymm);
                    if (gradient) {
                        for (int j = 0; j < nAtoms; j++) {
                            gxk_local[j] = 0.0;
                            gyk_local[j] = 0.0;
                            gzk_local[j] = 0.0;
                            txk_local[j] = 0.0;
                            tyk_local[j] = 0.0;
                            tzk_local[j] = 0.0;
                        }
                    }
                    if (lambdaTerm) {
                        for (int j = 0; j < nAtoms; j++) {
                            lxk_local[j] = 0.0;
                            lyk_local[j] = 0.0;
                            lzk_local[j] = 0.0;
                            ltxk_local[j] = 0.0;
                            ltyk_local[j] = 0.0;
                            ltzk_local[j] = 0.0;
                        }
                    }
                    realSpaceChunk(lb, ub);
                    if (gradient) {
                        // Turn symmetry mate torques into gradients
                        torque(iSymm, txk_local, tyk_local, tzk_local,
                                gxk_local, gyk_local, gzk_local,
                                work[0], work[1], work[2], work[3], work[4],
                                work[5], work[6], work[7], work[8], work[9],
                                work[10], work[11], work[12], work[13], work[14]);
                        // Rotate symmetry mate gradients
                        crystal.applyTransSymRot(nAtoms, gxk_local, gyk_local, gzk_local,
                                gxk_local, gyk_local, gzk_local, symOp, rot_local);
                        // Sum symmetry mate gradients into asymmetric unit gradients
                        for (int j = 0; j < nAtoms; j++) {
                            gx_local[j] += gxk_local[j];
                            gy_local[j] += gyk_local[j];
                            gz_local[j] += gzk_local[j];
                        }
                    }
                    if (lambdaTerm) {
                        // Turn symmetry mate torques into gradients
                        torque(iSymm, ltxk_local, ltyk_local, ltzk_local,
                                lxk_local, lyk_local, lzk_local,
                                work[0], work[1], work[2], work[3], work[4],
                                work[5], work[6], work[7], work[8], work[9],
                                work[10], work[11], work[12], work[13], work[14]);
                        // Rotate symmetry mate gradients
                        crystal.applyTransSymRot(nAtoms, lxk_local, lyk_local, lzk_local,
                                lxk_local, lyk_local, lzk_local, symOp, rot_local);
                        // Sum symmetry mate gradients into asymmetric unit gradients
                        for (int j = 0; j < nAtoms; j++) {
                            lx_local[j] += lxk_local[j];
                            ly_local[j] += lyk_local[j];
                            lz_local[j] += lzk_local[j];
                        }
                    }

                }
                computeTime += System.nanoTime() - startTime;
            }

            @Override
            public void finish() {
                sharedInteractions.addAndGet(count);
                sharedPermanentEnergy.addAndGet(permanentEnergy * ELECTRIC);
                sharedPolarizationEnergy.addAndGet(inducedEnergy * ELECTRIC);
                if (gradient) {
                    for (int j = 0; j < nAtoms; j++) {
                        gx_local[j] *= ELECTRIC;
                        gy_local[j] *= ELECTRIC;
                        gz_local[j] *= ELECTRIC;
                        tx_local[j] *= ELECTRIC;
                        ty_local[j] *= ELECTRIC;
                        tz_local[j] *= ELECTRIC;
                    }
                    /**
                     * Reduce the force and torque contributions computed by the
                     * current thread into the shared arrays.
                     */
                    sharedGrad[0].reduce(gx_local, DoubleOp.SUM);
                    sharedGrad[1].reduce(gy_local, DoubleOp.SUM);
                    sharedGrad[2].reduce(gz_local, DoubleOp.SUM);
                    sharedTorque[0].reduce(tx_local, DoubleOp.SUM);
                    sharedTorque[1].reduce(ty_local, DoubleOp.SUM);
                    sharedTorque[2].reduce(tz_local, DoubleOp.SUM);
                }
                if (lambdaTerm) {
                    shareddEdLambda.addAndGet(dUdL * ELECTRIC);
                    sharedd2EdLambda2.addAndGet(d2UdL2 * ELECTRIC);
                    for (int j = 0; j < nAtoms; j++) {
                        lx_local[j] *= ELECTRIC;
                        ly_local[j] *= ELECTRIC;
                        lz_local[j] *= ELECTRIC;
                        ltx_local[j] *= ELECTRIC;
                        lty_local[j] *= ELECTRIC;
                        ltz_local[j] *= ELECTRIC;
                    }
                    /**
                     * Reduce the force and torque contributions computed by the
                     * current thread into the shared arrays.
                     */
                    shareddEdLdX[0].reduce(lx_local, DoubleOp.SUM);
                    shareddEdLdX[1].reduce(ly_local, DoubleOp.SUM);
                    shareddEdLdX[2].reduce(lz_local, DoubleOp.SUM);
                    shareddEdLTorque[0].reduce(ltx_local, DoubleOp.SUM);
                    shareddEdLTorque[1].reduce(lty_local, DoubleOp.SUM);
                    shareddEdLTorque[2].reduce(ltz_local, DoubleOp.SUM);
                }
            }

            /**
             * Evaluate the real space permanent energy and polarization energy
             * for a chunk of atoms.
             *
             * @param lb
             *            The lower bound of the chunk.
             * @param ub
             *            The upper bound of the chunk.
             */
            private void realSpaceChunk(final int lb, final int ub) {
                final double x[] = coordinates[0][0];
                final double y[] = coordinates[0][1];
                final double z[] = coordinates[0][2];
                final double mpole[][] = globalMultipole[0];
                final double ind[][] = inducedDipole[0];
                final double indp[][] = inducedDipoleCR[0];
                final int lists[][] = realSpaceLists[iSymm];
                final double neighborX[] = coordinates[iSymm][0];
                final double neighborY[] = coordinates[iSymm][1];
                final double neighborZ[] = coordinates[iSymm][2];
                final double neighborMultipole[][] = globalMultipole[iSymm];
                final double neighborInducedDipole[][] = inducedDipole[iSymm];
                final double neighborInducedDipolep[][] = inducedDipoleCR[iSymm];
                for (i = lb; i <= ub; i++) {
                    if (!use[i]) {
                        continue;
                    }
                    final Atom ai = atoms[i];
                    if (iSymm == 0) {
                        for (Atom ak : ai.get1_5s()) {
                            masking_local[ak.xyzIndex - 1] = m15scale;
                        }
                        for (Torsion torsion : ai.getTorsions()) {
                            Atom ak = torsion.get1_4(ai);
                            if (ak != null) {
                                int index = ak.xyzIndex - 1;
                                masking_local[index] = m14scale;
                                for (int j : ip11[i]) {
                                    if (j == index) {
                                        maskingp_local[index] = 0.5;
                                    }
                                }
                            }
                        }
                        for (Angle angle : ai.getAngles()) {
                            Atom ak = angle.get1_3(ai);
                            if (ak != null) {
                                int index = ak.xyzIndex - 1;
                                masking_local[index] = m13scale;
                                maskingp_local[index] = p13scale;
                            }
                        }
                        for (Bond bond : ai.getBonds()) {
                            int index = bond.get1_2(ai).xyzIndex - 1;
                            masking_local[index] = m12scale;
                            maskingp_local[index] = p12scale;
                        }
                        for (int j : ip11[i]) {
                            maskingd_local[j] = d11scale;
                        }
                    }
                    final double xi = x[i];
                    final double yi = y[i];
                    final double zi = z[i];
                    final double globalMultipolei[] = mpole[i];
                    final double inducedDipolei[] = ind[i];
                    final double inducedDipolepi[] = indp[i];
                    ci = globalMultipolei[t000];
                    dix = globalMultipolei[t100];
                    diy = globalMultipolei[t010];
                    diz = globalMultipolei[t001];
                    qixx = globalMultipolei[t200] * oneThird;
                    qiyy = globalMultipolei[t020] * oneThird;
                    qizz = globalMultipolei[t002] * oneThird;
                    qixy = globalMultipolei[t110] * oneThird;
                    qixz = globalMultipolei[t101] * oneThird;
                    qiyz = globalMultipolei[t011] * oneThird;
                    uix = inducedDipolei[0];
                    uiy = inducedDipolei[1];
                    uiz = inducedDipolei[2];
                    pix = inducedDipolepi[0];
                    piy = inducedDipolepi[1];
                    piz = inducedDipolepi[2];
                    // Default is that the outer loop atom is hard.
                    boolean softCorei[] = softCore[0];
                    if (isSoft[i]) {
                        softCorei = softCore[1];
                    }
                    final double pdi = pdamp[i];
                    final double pti = thole[i];
                    final int list[] = lists[i];
                    final int npair = ewaldCounts[iSymm][i];
                    for (int j = 0; j < npair; j++) {
                        k = list[j];
                        if (!use[k]) {
                            continue;
                        }
                        selfScale = 1.0;
                        if (i == k) {
                            selfScale = 0.5;
                        }
                        beta = 0.0;
                        l2 = 1.0;
                        soft = softCorei[k];
                        if (soft && doPermanentRealSpace) {
                            beta = lAlpha;
                            l2 = permanentScale;
                        }
                        final double xk = neighborX[k];
                        final double yk = neighborY[k];
                        final double zk = neighborZ[k];
                        dx_local[0] = xk - xi;
                        dx_local[1] = yk - yi;
                        dx_local[2] = zk - zi;
                        r2 = crystal.image(dx_local);
                        xr = dx_local[0];
                        yr = dx_local[1];
                        zr = dx_local[2];
                        final double globalMultipolek[] = neighborMultipole[k];
                        final double inducedDipolek[] = neighborInducedDipole[k];
                        final double inducedDipolepk[] = neighborInducedDipolep[k];
                        ck = globalMultipolek[t000];
                        dkx = globalMultipolek[t100];
                        dky = globalMultipolek[t010];
                        dkz = globalMultipolek[t001];
                        qkxx = globalMultipolek[t200] * oneThird;
                        qkyy = globalMultipolek[t020] * oneThird;
                        qkzz = globalMultipolek[t002] * oneThird;
                        qkxy = globalMultipolek[t110] * oneThird;
                        qkxz = globalMultipolek[t101] * oneThird;
                        qkyz = globalMultipolek[t011] * oneThird;
                        ukx = inducedDipolek[0];
                        uky = inducedDipolek[1];
                        ukz = inducedDipolek[2];
                        pkx = inducedDipolepk[0];
                        pky = inducedDipolepk[1];
                        pkz = inducedDipolepk[2];
                        final double pdk = pdamp[k];
                        final double ptk = thole[k];
                        scale = masking_local[k];
                        scalep = maskingp_local[k];
                        scaled = maskingd_local[k];
                        scale3 = 1.0;
                        scale5 = 1.0;
                        scale7 = 1.0;
                        double r = sqrt(r2 + beta);
                        double ralpha = aewald * r;
                        double exp2a = exp(-ralpha * ralpha);
                        rr1 = 1.0 / r;
                        rr2 = rr1 * rr1;
                        bn0 = erfc(ralpha) * rr1;
                        bn1 = (bn0 + an0 * exp2a) * rr2;
                        bn2 = (3.0 * bn1 + an1 * exp2a) * rr2;
                        bn3 = (5.0 * bn2 + an2 * exp2a) * rr2;
                        bn4 = (7.0 * bn3 + an3 * exp2a) * rr2;
                        bn5 = (9.0 * bn4 + an4 * exp2a) * rr2;
                        bn6 = (11.0 * bn5 + an5 * exp2a) * rr2;
                        rr3 = rr1 * rr2;
                        rr5 = 3.0 * rr3 * rr2;
                        rr7 = 5.0 * rr5 * rr2;
                        rr9 = 7.0 * rr7 * rr2;
                        rr11 = 9.0 * rr9 * rr2;
                        rr13 = 11.0 * rr11 * rr2;
                        ddsc3x = 0.0;
                        ddsc3y = 0.0;
                        ddsc3z = 0.0;
                        ddsc5x = 0.0;
                        ddsc5y = 0.0;
                        ddsc5z = 0.0;
                        ddsc7x = 0.0;
                        ddsc7y = 0.0;
                        ddsc7z = 0.0;
                        double damp = pdi * pdk;
                        if (damp != 0.0) {
                            final double pgamma = min(pti, ptk);
                            final double rdamp = r / damp;
                            damp = -pgamma * rdamp * rdamp * rdamp;
                            if (damp > -50.0) {
                                final double expdamp = exp(damp);
                                scale3 = 1.0 - expdamp;
                                scale5 = 1.0 - expdamp * (1.0 - damp);
                                scale7 = 1.0 - expdamp * (1.0 - damp + 0.6 * damp * damp);
                                final double temp3 = -3.0 * damp * expdamp / r2;
                                final double temp5 = -damp;
                                final double temp7 = -0.2 - 0.6 * damp;
                                ddsc3x = temp3 * xr;
                                ddsc3y = temp3 * yr;
                                ddsc3z = temp3 * zr;
                                ddsc5x = temp5 * ddsc3x;
                                ddsc5y = temp5 * ddsc3y;
                                ddsc5z = temp5 * ddsc3z;
                                ddsc7x = temp7 * ddsc5x;
                                ddsc7y = temp7 * ddsc5y;
                                ddsc7z = temp7 * ddsc5z;
                            }
                        }
                        if (doPermanentRealSpace) {
                            permanentEnergy += permanentPair();
                            count++;
                        }
                        if (polarization != Polarization.NONE && doPolarization) {
                            /**
                             * Polarization does not use the softcore tensors.
                             */
                            if (soft && doPermanentRealSpace) {
                                scale3 = 1.0;
                                scale5 = 1.0;
                                scale7 = 1.0;
                                r = sqrt(r2);
                                ralpha = aewald * r;
                                exp2a = exp(-ralpha * ralpha);
                                rr1 = 1.0 / r;
                                rr2 = rr1 * rr1;
                                bn0 = erfc(ralpha) * rr1;
                                bn1 = (bn0 + an0 * exp2a) * rr2;
                                bn2 = (3.0 * bn1 + an1 * exp2a) * rr2;
                                bn3 = (5.0 * bn2 + an2 * exp2a) * rr2;
                                bn4 = (7.0 * bn3 + an3 * exp2a) * rr2;
                                bn5 = (9.0 * bn4 + an4 * exp2a) * rr2;
                                bn6 = (11.0 * bn5 + an5 * exp2a) * rr2;
                                rr3 = rr1 * rr2;
                                rr5 = 3.0 * rr3 * rr2;
                                rr7 = 5.0 * rr5 * rr2;
                                rr9 = 7.0 * rr7 * rr2;
                                rr11 = 9.0 * rr9 * rr2;
                                ddsc3x = 0.0;
                                ddsc3y = 0.0;
                                ddsc3z = 0.0;
                                ddsc5x = 0.0;
                                ddsc5y = 0.0;
                                ddsc5z = 0.0;
                                ddsc7x = 0.0;
                                ddsc7y = 0.0;
                                ddsc7z = 0.0;
                                damp = pdi * pdk;
                                if (damp != 0.0) {
                                    final double pgamma = min(pti, ptk);
                                    final double rdamp = r / damp;
                                    damp = -pgamma * rdamp * rdamp * rdamp;
                                    if (damp > -50.0) {
                                        final double expdamp = exp(damp);
                                        scale3 = 1.0 - expdamp;
                                        scale5 = 1.0 - expdamp * (1.0 - damp);
                                        scale7 = 1.0 - expdamp * (1.0 - damp + 0.6 * damp * damp);
                                        final double temp3 = -3.0 * damp * expdamp / r2;
                                        final double temp5 = -damp;
                                        final double temp7 = -0.2 - 0.6 * damp;
                                        ddsc3x = temp3 * xr;
                                        ddsc3y = temp3 * yr;
                                        ddsc3z = temp3 * zr;
                                        ddsc5x = temp5 * ddsc3x;
                                        ddsc5y = temp5 * ddsc3y;
                                        ddsc5z = temp5 * ddsc3z;
                                        ddsc7x = temp7 * ddsc5x;
                                        ddsc7y = temp7 * ddsc5y;
                                        ddsc7z = temp7 * ddsc5z;
                                    }
                                }
                            }
                            inducedEnergy += polarizationPair();
                        }
                    }
                    if (iSymm == 0) {
                        for (Atom ak : ai.get1_5s()) {
                            int index = ak.xyzIndex - 1;
                            masking_local[index] = 1.0;
                        }
                        for (Torsion torsion : ai.getTorsions()) {
                            Atom ak = torsion.get1_4(ai);
                            if (ak != null) {
                                int index = ak.xyzIndex - 1;
                                masking_local[index] = 1.0;
                                for (int j : ip11[i]) {
                                    if (j == index) {
                                        maskingp_local[index] = 1.0;
                                    }
                                }
                            }
                        }
                        for (Angle angle : ai.getAngles()) {
                            Atom ak = angle.get1_3(ai);
                            if (ak != null) {
                                int index = ak.xyzIndex - 1;
                                masking_local[index] = 1.0;
                                maskingp_local[index] = 1.0;
                            }
                        }
                        for (Bond bond : ai.getBonds()) {
                            int index = bond.get1_2(ai).xyzIndex - 1;
                            masking_local[index] = 1.0;
                            maskingp_local[index] = 1.0;
                        }
                        for (int j : ip11[i]) {
                            maskingd_local[j] = 1.0;
                        }
                    }
                }
            }

            /**
             * Evaluate the real space permanent energy for a pair of multipole
             * sites.
             *
             * @return the permanent multipole energy.
             */
            private double permanentPair() {
                final double dixdkx = diy * dkz - diz * dky;
                final double dixdky = diz * dkx - dix * dkz;
                final double dixdkz = dix * dky - diy * dkx;
                final double dixrx = diy * zr - diz * yr;
                final double dixry = diz * xr - dix * zr;
                final double dixrz = dix * yr - diy * xr;
                final double dkxrx = dky * zr - dkz * yr;
                final double dkxry = dkz * xr - dkx * zr;
                final double dkxrz = dkx * yr - dky * xr;
                final double qirx = qixx * xr + qixy * yr + qixz * zr;
                final double qiry = qixy * xr + qiyy * yr + qiyz * zr;
                final double qirz = qixz * xr + qiyz * yr + qizz * zr;
                final double qkrx = qkxx * xr + qkxy * yr + qkxz * zr;
                final double qkry = qkxy * xr + qkyy * yr + qkyz * zr;
                final double qkrz = qkxz * xr + qkyz * yr + qkzz * zr;
                final double qiqkrx = qixx * qkrx + qixy * qkry + qixz * qkrz;
                final double qiqkry = qixy * qkrx + qiyy * qkry + qiyz * qkrz;
                final double qiqkrz = qixz * qkrx + qiyz * qkry + qizz * qkrz;
                final double qkqirx = qkxx * qirx + qkxy * qiry + qkxz * qirz;
                final double qkqiry = qkxy * qirx + qkyy * qiry + qkyz * qirz;
                final double qkqirz = qkxz * qirx + qkyz * qiry + qkzz * qirz;
                final double qixqkx = qixy * qkxz + qiyy * qkyz + qiyz * qkzz - qixz * qkxy - qiyz * qkyy - qizz * qkyz;
                final double qixqky = qixz * qkxx + qiyz * qkxy + qizz * qkxz - qixx * qkxz - qixy * qkyz - qixz * qkzz;
                final double qixqkz = qixx * qkxy + qixy * qkyy + qixz * qkyz - qixy * qkxx - qiyy * qkxy - qiyz * qkxz;
                final double rxqirx = yr * qirz - zr * qiry;
                final double rxqiry = zr * qirx - xr * qirz;
                final double rxqirz = xr * qiry - yr * qirx;
                final double rxqkrx = yr * qkrz - zr * qkry;
                final double rxqkry = zr * qkrx - xr * qkrz;
                final double rxqkrz = xr * qkry - yr * qkrx;
                final double rxqikrx = yr * qiqkrz - zr * qiqkry;
                final double rxqikry = zr * qiqkrx - xr * qiqkrz;
                final double rxqikrz = xr * qiqkry - yr * qiqkrx;
                final double rxqkirx = yr * qkqirz - zr * qkqiry;
                final double rxqkiry = zr * qkqirx - xr * qkqirz;
                final double rxqkirz = xr * qkqiry - yr * qkqirx;
                final double qkrxqirx = qkry * qirz - qkrz * qiry;
                final double qkrxqiry = qkrz * qirx - qkrx * qirz;
                final double qkrxqirz = qkrx * qiry - qkry * qirx;
                final double qidkx = qixx * dkx + qixy * dky + qixz * dkz;
                final double qidky = qixy * dkx + qiyy * dky + qiyz * dkz;
                final double qidkz = qixz * dkx + qiyz * dky + qizz * dkz;
                final double qkdix = qkxx * dix + qkxy * diy + qkxz * diz;
                final double qkdiy = qkxy * dix + qkyy * diy + qkyz * diz;
                final double qkdiz = qkxz * dix + qkyz * diy + qkzz * diz;
                final double dixqkrx = diy * qkrz - diz * qkry;
                final double dixqkry = diz * qkrx - dix * qkrz;
                final double dixqkrz = dix * qkry - diy * qkrx;
                final double dkxqirx = dky * qirz - dkz * qiry;
                final double dkxqiry = dkz * qirx - dkx * qirz;
                final double dkxqirz = dkx * qiry - dky * qirx;
                final double rxqidkx = yr * qidkz - zr * qidky;
                final double rxqidky = zr * qidkx - xr * qidkz;
                final double rxqidkz = xr * qidky - yr * qidkx;
                final double rxqkdix = yr * qkdiz - zr * qkdiy;
                final double rxqkdiy = zr * qkdix - xr * qkdiz;
                final double rxqkdiz = xr * qkdiy - yr * qkdix;
                /**
                 * Calculate the scalar products for permanent multipoles.
                 */
                final double sc2 = dix * dkx + diy * dky + diz * dkz;
                final double sc3 = dix * xr + diy * yr + diz * zr;
                final double sc4 = dkx * xr + dky * yr + dkz * zr;
                final double sc5 = qirx * xr + qiry * yr + qirz * zr;
                final double sc6 = qkrx * xr + qkry * yr + qkrz * zr;
                final double sc7 = qirx * dkx + qiry * dky + qirz * dkz;
                final double sc8 = qkrx * dix + qkry * diy + qkrz * diz;
                final double sc9 = qirx * qkrx + qiry * qkry + qirz * qkrz;
                final double sc10 = 2.0 * (qixy * qkxy + qixz * qkxz + qiyz * qkyz) + qixx * qkxx + qiyy * qkyy + qizz * qkzz;
                /**
                 * Calculate the gl functions for permanent multipoles.
                 */
                final double gl0 = ci * ck;
                final double gl1 = ck * sc3 - ci * sc4;
                final double gl2 = ci * sc6 + ck * sc5 - sc3 * sc4;
                final double gl3 = sc3 * sc6 - sc4 * sc5;
                final double gl4 = sc5 * sc6;
                final double gl5 = -4.0 * sc9;
                final double gl6 = sc2;
                final double gl7 = 2.0 * (sc7 - sc8);
                final double gl8 = 2.0 * sc10;
                /**
                 * Compute the energy contributions for this interaction.
                 */
                final double scale1 = 1.0 - scale;
                final double ereal = gl0 * bn0 + (gl1 + gl6) * bn1 + (gl2 + gl7 + gl8) * bn2 + (gl3 + gl5) * bn3 + gl4 * bn4;
                final double efix = scale1 * (gl0 * rr1 + (gl1 + gl6) * rr3 + (gl2 + gl7 + gl8) * rr5
                        + (gl3 + gl5) * rr7 + gl4 * rr9);

                final double e = selfScale * l2 * (ereal - efix);

                if (!(gradient || (soft && lambdaTerm))) {
                    return e;
                }

                if (gradient) {
                    final double gf1 = bn1 * gl0 + bn2 * (gl1 + gl6) + bn3 * (gl2 + gl7 + gl8) + bn4 * (gl3 + gl5) + bn5 * gl4;
                    final double gf2 = -ck * bn1 + sc4 * bn2 - sc6 * bn3;
                    final double gf3 = ci * bn1 + sc3 * bn2 + sc5 * bn3;
                    final double gf4 = 2.0 * bn2;
                    final double gf5 = 2.0 * (-ck * bn2 + sc4 * bn3 - sc6 * bn4);
                    final double gf6 = 2.0 * (-ci * bn2 - sc3 * bn3 - sc5 * bn4);
                    final double gf7 = 4.0 * bn3;
                    /*
                     * Get the permanent force with screening.
                     */
                    double ftm2x = gf1 * xr + gf2 * dix + gf3 * dkx + gf4 * (qkdix - qidkx) + gf5 * qirx + gf6 * qkrx + gf7 * (qiqkrx + qkqirx);
                    double ftm2y = gf1 * yr + gf2 * diy + gf3 * dky + gf4 * (qkdiy - qidky) + gf5 * qiry + gf6 * qkry + gf7 * (qiqkry + qkqiry);
                    double ftm2z = gf1 * zr + gf2 * diz + gf3 * dkz + gf4 * (qkdiz - qidkz) + gf5 * qirz + gf6 * qkrz + gf7 * (qiqkrz + qkqirz);
                    /*
                     * Get the permanent torque with screening.
                     */
                    double ttm2x = -bn1 * dixdkx + gf2 * dixrx + gf4 * (dixqkrx + dkxqirx + rxqidkx - 2.0 * qixqkx) - gf5 * rxqirx - gf7 * (rxqikrx + qkrxqirx);
                    double ttm2y = -bn1 * dixdky + gf2 * dixry + gf4 * (dixqkry + dkxqiry + rxqidky - 2.0 * qixqky) - gf5 * rxqiry - gf7 * (rxqikry + qkrxqiry);
                    double ttm2z = -bn1 * dixdkz + gf2 * dixrz + gf4 * (dixqkrz + dkxqirz + rxqidkz - 2.0 * qixqkz) - gf5 * rxqirz - gf7 * (rxqikrz + qkrxqirz);
                    double ttm3x = bn1 * dixdkx + gf3 * dkxrx - gf4 * (dixqkrx + dkxqirx + rxqkdix - 2.0 * qixqkx) - gf6 * rxqkrx - gf7 * (rxqkirx - qkrxqirx);
                    double ttm3y = bn1 * dixdky + gf3 * dkxry - gf4 * (dixqkry + dkxqiry + rxqkdiy - 2.0 * qixqky) - gf6 * rxqkry - gf7 * (rxqkiry - qkrxqiry);
                    double ttm3z = bn1 * dixdkz + gf3 * dkxrz - gf4 * (dixqkrz + dkxqirz + rxqkdiz - 2.0 * qixqkz) - gf6 * rxqkrz - gf7 * (rxqkirz - qkrxqirz);
                    /**
                     * Handle the case where scaling is used.
                     */
                    if (scale1 != 0.0) {
                        final double gfr1 = rr3 * gl0 + rr5 * (gl1 + gl6) + rr7 * (gl2 + gl7 + gl8) + rr9 * (gl3 + gl5) + rr11 * gl4;
                        final double gfr2 = -ck * rr3 + sc4 * rr5 - sc6 * rr7;
                        final double gfr3 = ci * rr3 + sc3 * rr5 + sc5 * rr7;
                        final double gfr4 = 2.0 * rr5;
                        final double gfr5 = 2.0 * (-ck * rr5 + sc4 * rr7 - sc6 * rr9);
                        final double gfr6 = 2.0 * (-ci * rr5 - sc3 * rr7 - sc5 * rr9);
                        final double gfr7 = 4.0 * rr7;
                        /*
                         * Get the permanent force without screening.
                         */
                        final double ftm2rx = gfr1 * xr + gfr2 * dix + gfr3 * dkx + gfr4 * (qkdix - qidkx) + gfr5 * qirx + gfr6 * qkrx + gfr7 * (qiqkrx + qkqirx);
                        final double ftm2ry = gfr1 * yr + gfr2 * diy + gfr3 * dky + gfr4 * (qkdiy - qidky) + gfr5 * qiry + gfr6 * qkry + gfr7 * (qiqkry + qkqiry);
                        final double ftm2rz = gfr1 * zr + gfr2 * diz + gfr3 * dkz + gfr4 * (qkdiz - qidkz) + gfr5 * qirz + gfr6 * qkrz + gfr7 * (qiqkrz + qkqirz);
                        /*
                         * Get the permanent torque without screening.
                         */
                        final double ttm2rx = -rr3 * dixdkx + gfr2 * dixrx + gfr4 * (dixqkrx + dkxqirx + rxqidkx - 2.0 * qixqkx) - gfr5 * rxqirx - gfr7 * (rxqikrx + qkrxqirx);
                        final double ttm2ry = -rr3 * dixdky + gfr2 * dixry + gfr4 * (dixqkry + dkxqiry + rxqidky - 2.0 * qixqky) - gfr5 * rxqiry - gfr7 * (rxqikry + qkrxqiry);
                        final double ttm2rz = -rr3 * dixdkz + gfr2 * dixrz + gfr4 * (dixqkrz + dkxqirz + rxqidkz - 2.0 * qixqkz) - gfr5 * rxqirz - gfr7 * (rxqikrz + qkrxqirz);
                        final double ttm3rx = rr3 * dixdkx + gfr3 * dkxrx - gfr4 * (dixqkrx + dkxqirx + rxqkdix - 2.0 * qixqkx) - gfr6 * rxqkrx - gfr7 * (rxqkirx - qkrxqirx);
                        final double ttm3ry = rr3 * dixdky + gfr3 * dkxry - gfr4 * (dixqkry + dkxqiry + rxqkdiy - 2.0 * qixqky) - gfr6 * rxqkry - gfr7 * (rxqkiry - qkrxqiry);
                        final double ttm3rz = rr3 * dixdkz + gfr3 * dkxrz - gfr4 * (dixqkrz + dkxqirz + rxqkdiz - 2.0 * qixqkz) - gfr6 * rxqkrz - gfr7 * (rxqkirz - qkrxqirz);
                        ftm2x -= scale1 * ftm2rx;
                        ftm2y -= scale1 * ftm2ry;
                        ftm2z -= scale1 * ftm2rz;
                        ttm2x -= scale1 * ttm2rx;
                        ttm2y -= scale1 * ttm2ry;
                        ttm2z -= scale1 * ttm2rz;
                        ttm3x -= scale1 * ttm3rx;
                        ttm3y -= scale1 * ttm3ry;
                        ttm3z -= scale1 * ttm3rz;
                    }
                    gx_local[i] += selfScale * l2 * ftm2x;
                    gy_local[i] += selfScale * l2 * ftm2y;
                    gz_local[i] += selfScale * l2 * ftm2z;
                    tx_local[i] += selfScale * l2 * ttm2x;
                    ty_local[i] += selfScale * l2 * ttm2y;
                    tz_local[i] += selfScale * l2 * ttm2z;
                    gxk_local[k] -= selfScale * l2 * ftm2x;
                    gyk_local[k] -= selfScale * l2 * ftm2y;
                    gzk_local[k] -= selfScale * l2 * ftm2z;
                    txk_local[k] += selfScale * l2 * ttm3x;
                    tyk_local[k] += selfScale * l2 * ttm3y;
                    tzk_local[k] += selfScale * l2 * ttm3z;
                    /**
                     * This is dU/dL/dX for the first term of dU/dL:
                     * d[dfL2dL * ereal]/dx
                     */
                    if (lambdaTerm && soft) {
                        lx_local[i] += selfScale * dEdLSign * dlPowPerm * ftm2x;
                        ly_local[i] += selfScale * dEdLSign * dlPowPerm * ftm2y;
                        lz_local[i] += selfScale * dEdLSign * dlPowPerm * ftm2z;
                        ltx_local[i] += selfScale * dEdLSign * dlPowPerm * ttm2x;
                        lty_local[i] += selfScale * dEdLSign * dlPowPerm * ttm2y;
                        ltz_local[i] += selfScale * dEdLSign * dlPowPerm * ttm2z;
                        lxk_local[k] -= selfScale * dEdLSign * dlPowPerm * ftm2x;
                        lyk_local[k] -= selfScale * dEdLSign * dlPowPerm * ftm2y;
                        lzk_local[k] -= selfScale * dEdLSign * dlPowPerm * ftm2z;
                        ltxk_local[k] += selfScale * dEdLSign * dlPowPerm * ttm3x;
                        ltyk_local[k] += selfScale * dEdLSign * dlPowPerm * ttm3y;
                        ltzk_local[k] += selfScale * dEdLSign * dlPowPerm * ttm3z;
                    }
                }
                if (lambdaTerm && soft) {
                    double dRealdL = gl0 * bn1 + (gl1 + gl6) * bn2 + (gl2 + gl7 + gl8) * bn3 + (gl3 + gl5) * bn4 + gl4 * bn5;
                    double d2RealdL2 = gl0 * bn2 + (gl1 + gl6) * bn3 + (gl2 + gl7 + gl8) * bn4 + (gl3 + gl5) * bn5 + gl4 * bn6;


                    dUdL += selfScale * dEdLSign * (dlPowPerm * ereal + lPowPerm * dlAlpha * dRealdL);
                    d2UdL2 += selfScale * dEdLSign * (d2lPowPerm * ereal
                            + dlPowPerm * dlAlpha * dRealdL
                            + dlPowPerm * dlAlpha * dRealdL
                            + lPowPerm * d2lAlpha * dRealdL
                            + lPowPerm * dlAlpha * dlAlpha * d2RealdL2);

                    /*
                    double dFixdL = gl0 * rr3 + (gl1 + gl6) * rr5 + (gl2 + gl7 + gl8) * rr7 + (gl3 + gl5) * rr9 + gl4 * rr11;
                    double d2FixdL2 = gl0 * rr5 + (gl1 + gl6) * rr7 + (gl2 + gl7 + gl8) * rr9 + (gl3 + gl5) * rr11 + gl4 * rr13;
                    
                    dUdL += selfScale * dEdLSign *  (dlPowPerm * efix + lPowPerm * dlAlpha * dFixdL);
                    d2UdL2 += selfScale * dEdLSign * (d2lPowPerm * efix
                    + dlPowPerm * dlAlpha * dFixdL
                    + dlPowPerm * dlAlpha * dFixdL
                    + lPowPerm * d2lAlpha * dFixdL
                    + lPowPerm * dlAlpha * dlAlpha * d2FixdL2); */

                    /**
                     * Collect terms for dU/dL/dX for the second term of dU/dL:
                     * d[fL2*dfL1dL*dRealdL]/dX
                     */
                    final double gf1 = bn2 * gl0 + bn3 * (gl1 + gl6)
                            + bn4 * (gl2 + gl7 + gl8)
                            + bn5 * (gl3 + gl5) + bn6 * gl4;
                    final double gf2 = -ck * bn2 + sc4 * bn3 - sc6 * bn4;
                    final double gf3 = ci * bn2 + sc3 * bn3 + sc5 * bn4;
                    final double gf4 = 2.0 * bn3;
                    final double gf5 = 2.0 * (-ck * bn3 + sc4 * bn4 - sc6 * bn5);
                    final double gf6 = 2.0 * (-ci * bn3 - sc3 * bn4 - sc5 * bn5);
                    final double gf7 = 4.0 * bn4;
                    /*
                     * Get the permanent force with screening.
                     */
                    double ftm2x = gf1 * xr + gf2 * dix + gf3 * dkx
                            + gf4 * (qkdix - qidkx) + gf5 * qirx
                            + gf6 * qkrx + gf7 * (qiqkrx + qkqirx);
                    double ftm2y = gf1 * yr + gf2 * diy + gf3 * dky
                            + gf4 * (qkdiy - qidky) + gf5 * qiry
                            + gf6 * qkry + gf7 * (qiqkry + qkqiry);
                    double ftm2z = gf1 * zr + gf2 * diz + gf3 * dkz
                            + gf4 * (qkdiz - qidkz) + gf5 * qirz
                            + gf6 * qkrz + gf7 * (qiqkrz + qkqirz);
                    /*
                     * Get the permanent torque with screening.
                     */
                    double ttm2x = -bn2 * dixdkx + gf2 * dixrx
                            + gf4 * (dixqkrx + dkxqirx + rxqidkx - 2.0 * qixqkx)
                            - gf5 * rxqirx - gf7 * (rxqikrx + qkrxqirx);
                    double ttm2y = -bn2 * dixdky + gf2 * dixry
                            + gf4 * (dixqkry + dkxqiry + rxqidky - 2.0 * qixqky)
                            - gf5 * rxqiry - gf7 * (rxqikry + qkrxqiry);
                    double ttm2z = -bn2 * dixdkz + gf2 * dixrz
                            + gf4 * (dixqkrz + dkxqirz + rxqidkz - 2.0 * qixqkz)
                            - gf5 * rxqirz - gf7 * (rxqikrz + qkrxqirz);
                    double ttm3x = bn2 * dixdkx + gf3 * dkxrx
                            - gf4 * (dixqkrx + dkxqirx + rxqkdix - 2.0 * qixqkx)
                            - gf6 * rxqkrx - gf7 * (rxqkirx - qkrxqirx);
                    double ttm3y = bn2 * dixdky + gf3 * dkxry
                            - gf4 * (dixqkry + dkxqiry + rxqkdiy - 2.0 * qixqky)
                            - gf6 * rxqkry - gf7 * (rxqkiry - qkrxqiry);
                    double ttm3z = bn2 * dixdkz + gf3 * dkxrz
                            - gf4 * (dixqkrz + dkxqirz + rxqkdiz - 2.0 * qixqkz)
                            - gf6 * rxqkrz - gf7 * (rxqkirz - qkrxqirz);

                    /**
                     * Handle the case where scaling is used.
                     */
                    /* if (scale1 != 0.0) {
                    final double gfr1 = rr5 * gl0 + rr7 * (gl1 + gl6) + rr9 * (gl2 + gl7 + gl8) + rr11 * (gl3 + gl5) + rr13 * gl4;
                    final double gfr2 = -ck * rr5 + sc4 * rr7 - sc6 * rr9;
                    final double gfr3 = ci * rr5 + sc3 * rr7 + sc5 * rr9;
                    final double gfr4 = 2.0 * rr7;
                    final double gfr5 = 2.0 * (-ck * rr7 + sc4 * rr9 - sc6 * rr11);
                    final double gfr6 = 2.0 * (-ci * rr7 - sc3 * rr9 - sc5 * rr11);
                    final double gfr7 = 4.0 * rr9;
                    
                    //Get the permanent force without screening.
                    final double ftm2rx = gfr1 * xr + gfr2 * dix + gfr3 * dkx + gfr4 * (qkdix - qidkx) + gfr5 * qirx + gfr6 * qkrx + gfr7 * (qiqkrx + qkqirx);
                    final double ftm2ry = gfr1 * yr + gfr2 * diy + gfr3 * dky + gfr4 * (qkdiy - qidky) + gfr5 * qiry + gfr6 * qkry + gfr7 * (qiqkry + qkqiry);
                    final double ftm2rz = gfr1 * zr + gfr2 * diz + gfr3 * dkz + gfr4 * (qkdiz - qidkz) + gfr5 * qirz + gfr6 * qkrz + gfr7 * (qiqkrz + qkqirz);
                    
                    // Get the permanent torque without screening. 
                    final double ttm2rx = -rr5 * dixdkx + gfr2 * dixrx + gfr4 * (dixqkrx + dkxqirx + rxqidkx - 2.0 * qixqkx) - gfr5 * rxqirx - gfr7 * (rxqikrx + qkrxqirx);
                    final double ttm2ry = -rr5 * dixdky + gfr2 * dixry + gfr4 * (dixqkry + dkxqiry + rxqidky - 2.0 * qixqky) - gfr5 * rxqiry - gfr7 * (rxqikry + qkrxqiry);
                    final double ttm2rz = -rr5 * dixdkz + gfr2 * dixrz + gfr4 * (dixqkrz + dkxqirz + rxqidkz - 2.0 * qixqkz) - gfr5 * rxqirz - gfr7 * (rxqikrz + qkrxqirz);
                    final double ttm3rx = rr5 * dixdkx + gfr3 * dkxrx - gfr4 * (dixqkrx + dkxqirx + rxqkdix - 2.0 * qixqkx) - gfr6 * rxqkrx - gfr7 * (rxqkirx - qkrxqirx);
                    final double ttm3ry = rr5 * dixdky + gfr3 * dkxry - gfr4 * (dixqkry + dkxqiry + rxqkdiy - 2.0 * qixqky) - gfr6 * rxqkry - gfr7 * (rxqkiry - qkrxqiry);
                    final double ttm3rz = rr5 * dixdkz + gfr3 * dkxrz - gfr4 * (dixqkrz + dkxqirz + rxqkdiz - 2.0 * qixqkz) - gfr6 * rxqkrz - gfr7 * (rxqkirz - qkrxqirz);
                    ftm2x -= scale1 * ftm2rx;
                    ftm2y -= scale1 * ftm2ry;
                    ftm2z -= scale1 * ftm2rz;
                    ttm2x -= scale1 * ttm2rx;
                    ttm2y -= scale1 * ttm2ry;
                    ttm2z -= scale1 * ttm2rz;
                    ttm3x -= scale1 * ttm3rx;
                    ttm3y -= scale1 * ttm3ry;
                    ttm3z -= scale1 * ttm3rz;
                    } */
                    /**
                     * Add in dU/dL/dX for the second term of dU/dL:
                     * d[fL2*dfL1dL*dRealdL]/dX
                     */
                    lx_local[i] += selfScale * dEdLSign * lPowPerm * dlAlpha * ftm2x;
                    ly_local[i] += selfScale * dEdLSign * lPowPerm * dlAlpha * ftm2y;
                    lz_local[i] += selfScale * dEdLSign * lPowPerm * dlAlpha * ftm2z;
                    ltx_local[i] += selfScale * dEdLSign * lPowPerm * dlAlpha * ttm2x;
                    lty_local[i] += selfScale * dEdLSign * lPowPerm * dlAlpha * ttm2y;
                    ltz_local[i] += selfScale * dEdLSign * lPowPerm * dlAlpha * ttm2z;
                    lxk_local[k] -= selfScale * dEdLSign * lPowPerm * dlAlpha * ftm2x;
                    lyk_local[k] -= selfScale * dEdLSign * lPowPerm * dlAlpha * ftm2y;
                    lzk_local[k] -= selfScale * dEdLSign * lPowPerm * dlAlpha * ftm2z;
                    ltxk_local[k] += selfScale * dEdLSign * lPowPerm * dlAlpha * ttm3x;
                    ltyk_local[k] += selfScale * dEdLSign * lPowPerm * dlAlpha * ttm3y;
                    ltzk_local[k] += selfScale * dEdLSign * lPowPerm * dlAlpha * ttm3z;
                }
                return e;
            }

            /**
             * Evaluate the polarization energy for a pair of polarizable
             * multipole sites.
             *
             * @return the polarization energy.
             */
            private double polarizationPair() {
                final double dsc3 = 1.0 - scale3 * scaled;
                final double dsc5 = 1.0 - scale5 * scaled;
                final double dsc7 = 1.0 - scale7 * scaled;
                final double psc3 = 1.0 - scale3 * scalep;
                final double psc5 = 1.0 - scale5 * scalep;
                final double psc7 = 1.0 - scale7 * scalep;
                final double usc3 = 1.0 - scale3;
                final double usc5 = 1.0 - scale5;
                final double dixukx = diy * ukz - diz * uky;
                final double dixuky = diz * ukx - dix * ukz;
                final double dixukz = dix * uky - diy * ukx;
                final double dkxuix = dky * uiz - dkz * uiy;
                final double dkxuiy = dkz * uix - dkx * uiz;
                final double dkxuiz = dkx * uiy - dky * uix;
                final double dixukpx = diy * pkz - diz * pky;
                final double dixukpy = diz * pkx - dix * pkz;
                final double dixukpz = dix * pky - diy * pkx;
                final double dkxuipx = dky * piz - dkz * piy;
                final double dkxuipy = dkz * pix - dkx * piz;
                final double dkxuipz = dkx * piy - dky * pix;
                final double dixrx = diy * zr - diz * yr;
                final double dixry = diz * xr - dix * zr;
                final double dixrz = dix * yr - diy * xr;
                final double dkxrx = dky * zr - dkz * yr;
                final double dkxry = dkz * xr - dkx * zr;
                final double dkxrz = dkx * yr - dky * xr;
                final double qirx = qixx * xr + qixy * yr + qixz * zr;
                final double qiry = qixy * xr + qiyy * yr + qiyz * zr;
                final double qirz = qixz * xr + qiyz * yr + qizz * zr;
                final double qkrx = qkxx * xr + qkxy * yr + qkxz * zr;
                final double qkry = qkxy * xr + qkyy * yr + qkyz * zr;
                final double qkrz = qkxz * xr + qkyz * yr + qkzz * zr;
                final double rxqirx = yr * qirz - zr * qiry;
                final double rxqiry = zr * qirx - xr * qirz;
                final double rxqirz = xr * qiry - yr * qirx;
                final double rxqkrx = yr * qkrz - zr * qkry;
                final double rxqkry = zr * qkrx - xr * qkrz;
                final double rxqkrz = xr * qkry - yr * qkrx;
                final double qiukx = qixx * ukx + qixy * uky + qixz * ukz;
                final double qiuky = qixy * ukx + qiyy * uky + qiyz * ukz;
                final double qiukz = qixz * ukx + qiyz * uky + qizz * ukz;
                final double qkuix = qkxx * uix + qkxy * uiy + qkxz * uiz;
                final double qkuiy = qkxy * uix + qkyy * uiy + qkyz * uiz;
                final double qkuiz = qkxz * uix + qkyz * uiy + qkzz * uiz;
                final double qiukpx = qixx * pkx + qixy * pky + qixz * pkz;
                final double qiukpy = qixy * pkx + qiyy * pky + qiyz * pkz;
                final double qiukpz = qixz * pkx + qiyz * pky + qizz * pkz;
                final double qkuipx = qkxx * pix + qkxy * piy + qkxz * piz;
                final double qkuipy = qkxy * pix + qkyy * piy + qkyz * piz;
                final double qkuipz = qkxz * pix + qkyz * piy + qkzz * piz;
                final double uixqkrx = uiy * qkrz - uiz * qkry;
                final double uixqkry = uiz * qkrx - uix * qkrz;
                final double uixqkrz = uix * qkry - uiy * qkrx;
                final double ukxqirx = uky * qirz - ukz * qiry;
                final double ukxqiry = ukz * qirx - ukx * qirz;
                final double ukxqirz = ukx * qiry - uky * qirx;
                final double uixqkrpx = piy * qkrz - piz * qkry;
                final double uixqkrpy = piz * qkrx - pix * qkrz;
                final double uixqkrpz = pix * qkry - piy * qkrx;
                final double ukxqirpx = pky * qirz - pkz * qiry;
                final double ukxqirpy = pkz * qirx - pkx * qirz;
                final double ukxqirpz = pkx * qiry - pky * qirx;
                final double rxqiukx = yr * qiukz - zr * qiuky;
                final double rxqiuky = zr * qiukx - xr * qiukz;
                final double rxqiukz = xr * qiuky - yr * qiukx;
                final double rxqkuix = yr * qkuiz - zr * qkuiy;
                final double rxqkuiy = zr * qkuix - xr * qkuiz;
                final double rxqkuiz = xr * qkuiy - yr * qkuix;
                final double rxqiukpx = yr * qiukpz - zr * qiukpy;
                final double rxqiukpy = zr * qiukpx - xr * qiukpz;
                final double rxqiukpz = xr * qiukpy - yr * qiukpx;
                final double rxqkuipx = yr * qkuipz - zr * qkuipy;
                final double rxqkuipy = zr * qkuipx - xr * qkuipz;
                final double rxqkuipz = xr * qkuipy - yr * qkuipx;
                /**
                 * Calculate the scalar products for permanent multipoles.
                 */
                final double sc3 = dix * xr + diy * yr + diz * zr;
                final double sc4 = dkx * xr + dky * yr + dkz * zr;
                final double sc5 = qirx * xr + qiry * yr + qirz * zr;
                final double sc6 = qkrx * xr + qkry * yr + qkrz * zr;
                /**
                 * Calculate the scalar products for polarization components.
                 */
                final double sci1 = uix * dkx + uiy * dky + uiz * dkz + dix * ukx + diy * uky + diz * ukz;
                final double sci3 = uix * xr + uiy * yr + uiz * zr;
                final double sci4 = ukx * xr + uky * yr + ukz * zr;
                final double sci7 = qirx * ukx + qiry * uky + qirz * ukz;
                final double sci8 = qkrx * uix + qkry * uiy + qkrz * uiz;
                final double scip1 = pix * dkx + piy * dky + piz * dkz + dix * pkx + diy * pky + diz * pkz;
                final double scip2 = uix * pkx + uiy * pky + uiz * pkz + pix * ukx + piy * uky + piz * ukz;
                final double scip3 = pix * xr + piy * yr + piz * zr;
                final double scip4 = pkx * xr + pky * yr + pkz * zr;
                final double scip7 = qirx * pkx + qiry * pky + qirz * pkz;
                final double scip8 = qkrx * pix + qkry * piy + qkrz * piz;
                /**
                 * Calculate the gl functions for polarization components.
                 */
                final double gli1 = ck * sci3 - ci * sci4;
                final double gli2 = -sc3 * sci4 - sci3 * sc4;
                final double gli3 = sci3 * sc6 - sci4 * sc5;
                final double gli6 = sci1;
                final double gli7 = 2.0 * (sci7 - sci8);
                final double glip1 = ck * scip3 - ci * scip4;
                final double glip2 = -sc3 * scip4 - scip3 * sc4;
                final double glip3 = scip3 * sc6 - scip4 * sc5;
                final double glip6 = scip1;
                final double glip7 = 2.0 * (scip7 - scip8);
                /**
                 * Compute the energy contributions for this interaction.
                 */
                final double ereal = (gli1 + gli6) * bn1 + (gli2 + gli7) * bn2 + gli3 * bn3;
                final double efix = (gli1 + gli6) * rr3 * psc3 + (gli2 + gli7) * rr5 * psc5 + gli3 * rr7 * psc7;
                final double e = selfScale * 0.5 * (ereal - efix);
                if (!(gradient || lambdaTerm)) {
                    return polarizationScale * e;
                }
                boolean dorli = false;
                if (psc3 != 0.0 || dsc3 != 0.0 || usc3 != 0.0) {
                    dorli = true;
                }
                /*
                 * Get the induced force with screening.
                 */
                final double gfi1 = 0.5 * bn2 * (gli1 + glip1 + gli6 + glip6) + 0.5 * bn2 * scip2 + 0.5 * bn3 * (gli2 + glip2 + gli7 + glip7) - 0.5 * bn3 * (sci3 * scip4 + scip3 * sci4) + 0.5 * bn4 * (gli3 + glip3);
                final double gfi2 = -ck * bn1 + sc4 * bn2 - sc6 * bn3;
                final double gfi3 = ci * bn1 + sc3 * bn2 + sc5 * bn3;
                final double gfi4 = 2.0 * bn2;
                final double gfi5 = bn3 * (sci4 + scip4);
                final double gfi6 = -bn3 * (sci3 + scip3);
                double ftm2ix = gfi1 * xr + 0.5 * (gfi2 * (uix + pix) + bn2 * (sci4 * pix + scip4 * uix) + gfi3 * (ukx + pkx) + bn2 * (sci3 * pkx + scip3 * ukx) + (sci4 + scip4) * bn2 * dix + (sci3 + scip3) * bn2 * dkx + gfi4 * (qkuix + qkuipx - qiukx - qiukpx)) + gfi5 * qirx + gfi6 * qkrx;
                double ftm2iy = gfi1 * yr + 0.5 * (gfi2 * (uiy + piy) + bn2 * (sci4 * piy + scip4 * uiy) + gfi3 * (uky + pky) + bn2 * (sci3 * pky + scip3 * uky) + (sci4 + scip4) * bn2 * diy + (sci3 + scip3) * bn2 * dky + gfi4 * (qkuiy + qkuipy - qiuky - qiukpy)) + gfi5 * qiry + gfi6 * qkry;
                double ftm2iz = gfi1 * zr + 0.5 * (gfi2 * (uiz + piz) + bn2 * (sci4 * piz + scip4 * uiz) + gfi3 * (ukz + pkz) + bn2 * (sci3 * pkz + scip3 * ukz) + (sci4 + scip4) * bn2 * diz + (sci3 + scip3) * bn2 * dkz + gfi4 * (qkuiz + qkuipz - qiukz - qiukpz)) + gfi5 * qirz + gfi6 * qkrz;
                /*
                 * Get the induced torque with screening.
                 */
                final double gti2 = 0.5 * bn2 * (sci4 + scip4);
                final double gti3 = 0.5 * bn2 * (sci3 + scip3);
                final double gti4 = gfi4;
                final double gti5 = gfi5;
                final double gti6 = gfi6;
                double ttm2ix = -0.5 * bn1 * (dixukx + dixukpx) + gti2 * dixrx - gti5 * rxqirx + 0.5 * gti4 * (ukxqirx + rxqiukx + ukxqirpx + rxqiukpx);
                double ttm2iy = -0.5 * bn1 * (dixuky + dixukpy) + gti2 * dixry - gti5 * rxqiry + 0.5 * gti4 * (ukxqiry + rxqiuky + ukxqirpy + rxqiukpy);
                double ttm2iz = -0.5 * bn1 * (dixukz + dixukpz) + gti2 * dixrz - gti5 * rxqirz + 0.5 * gti4 * (ukxqirz + rxqiukz + ukxqirpz + rxqiukpz);
                double ttm3ix = -0.5 * bn1 * (dkxuix + dkxuipx) + gti3 * dkxrx - gti6 * rxqkrx - 0.5 * gti4 * (uixqkrx + rxqkuix + uixqkrpx + rxqkuipx);
                double ttm3iy = -0.5 * bn1 * (dkxuiy + dkxuipy) + gti3 * dkxry - gti6 * rxqkry - 0.5 * gti4 * (uixqkry + rxqkuiy + uixqkrpy + rxqkuipy);
                double ttm3iz = -0.5 * bn1 * (dkxuiz + dkxuipz) + gti3 * dkxrz - gti6 * rxqkrz - 0.5 * gti4 * (uixqkrz + rxqkuiz + uixqkrpz + rxqkuipz);
                double ftm2rix = 0.0;
                double ftm2riy = 0.0;
                double ftm2riz = 0.0;
                double ttm2rix = 0.0;
                double ttm2riy = 0.0;
                double ttm2riz = 0.0;
                double ttm3rix = 0.0;
                double ttm3riy = 0.0;
                double ttm3riz = 0.0;
                if (dorli) {
                    /*
                     * Get the induced force without screening.
                     */
                    final double gfri1 = 0.5 * rr5 * ((gli1 + gli6) * psc3 + (glip1 + glip6) * dsc3 + scip2 * usc3) + 0.5 * rr7 * ((gli7 + gli2) * psc5 + (glip7 + glip2) * dsc5 - (sci3 * scip4 + scip3 * sci4) * usc5) + 0.5 * rr9 * (gli3 * psc7 + glip3 * dsc7);
                    final double gfri4 = 2.0 * rr5;
                    final double gfri5 = rr7 * (sci4 * psc7 + scip4 * dsc7);
                    final double gfri6 = -rr7 * (sci3 * psc7 + scip3 * dsc7);
                    ftm2rix = gfri1 * xr + 0.5 * (-rr3 * ck * (uix * psc3 + pix * dsc3) + rr5 * sc4 * (uix * psc5 + pix * dsc5) - rr7 * sc6 * (uix * psc7 + pix * dsc7)) + (rr3 * ci * (ukx * psc3 + pkx * dsc3) + rr5 * sc3 * (ukx * psc5 + pkx * dsc5) + rr7 * sc5 * (ukx * psc7 + pkx * dsc7)) * 0.5 + rr5 * usc5 * (sci4 * pix + scip4 * uix + sci3 * pkx + scip3 * ukx) * 0.5 + 0.5 * (sci4 * psc5 + scip4 * dsc5) * rr5 * dix + 0.5 * (sci3 * psc5 + scip3 * dsc5) * rr5 * dkx + 0.5 * gfri4 * ((qkuix - qiukx) * psc5 + (qkuipx - qiukpx) * dsc5) + gfri5 * qirx + gfri6 * qkrx;
                    ftm2riy = gfri1 * yr + 0.5 * (-rr3 * ck * (uiy * psc3 + piy * dsc3) + rr5 * sc4 * (uiy * psc5 + piy * dsc5) - rr7 * sc6 * (uiy * psc7 + piy * dsc7)) + (rr3 * ci * (uky * psc3 + pky * dsc3) + rr5 * sc3 * (uky * psc5 + pky * dsc5) + rr7 * sc5 * (uky * psc7 + pky * dsc7)) * 0.5 + rr5 * usc5 * (sci4 * piy + scip4 * uiy + sci3 * pky + scip3 * uky) * 0.5 + 0.5 * (sci4 * psc5 + scip4 * dsc5) * rr5 * diy + 0.5 * (sci3 * psc5 + scip3 * dsc5) * rr5 * dky + 0.5 * gfri4 * ((qkuiy - qiuky) * psc5 + (qkuipy - qiukpy) * dsc5) + gfri5 * qiry + gfri6 * qkry;
                    ftm2riz = gfri1 * zr + 0.5 * (-rr3 * ck * (uiz * psc3 + piz * dsc3) + rr5 * sc4 * (uiz * psc5 + piz * dsc5) - rr7 * sc6 * (uiz * psc7 + piz * dsc7)) + (rr3 * ci * (ukz * psc3 + pkz * dsc3) + rr5 * sc3 * (ukz * psc5 + pkz * dsc5) + rr7 * sc5 * (ukz * psc7 + pkz * dsc7)) * 0.5 + rr5 * usc5 * (sci4 * piz + scip4 * uiz + sci3 * pkz + scip3 * ukz) * 0.5 + 0.5 * (sci4 * psc5 + scip4 * dsc5) * rr5 * diz + 0.5 * (sci3 * psc5 + scip3 * dsc5) * rr5 * dkz + 0.5 * gfri4 * ((qkuiz - qiukz) * psc5 + (qkuipz - qiukpz) * dsc5) + gfri5 * qirz + gfri6 * qkrz;
                    /*
                     * Get the induced torque without screening.
                     */
                    final double gtri2 = 0.5 * rr5 * (sci4 * psc5 + scip4 * dsc5);
                    final double gtri3 = 0.5 * rr5 * (sci3 * psc5 + scip3 * dsc5);
                    final double gtri4 = gfri4;
                    final double gtri5 = gfri5;
                    final double gtri6 = gfri6;
                    ttm2rix = -rr3 * (dixukx * psc3 + dixukpx * dsc3) * 0.5 + gtri2 * dixrx - gtri5 * rxqirx + gtri4 * ((ukxqirx + rxqiukx) * psc5 + (ukxqirpx + rxqiukpx) * dsc5) * 0.5;
                    ttm2riy = -rr3 * (dixuky * psc3 + dixukpy * dsc3) * 0.5 + gtri2 * dixry - gtri5 * rxqiry + gtri4 * ((ukxqiry + rxqiuky) * psc5 + (ukxqirpy + rxqiukpy) * dsc5) * 0.5;
                    ttm2riz = -rr3 * (dixukz * psc3 + dixukpz * dsc3) * 0.5 + gtri2 * dixrz - gtri5 * rxqirz + gtri4 * ((ukxqirz + rxqiukz) * psc5 + (ukxqirpz + rxqiukpz) * dsc5) * 0.5;
                    ttm3rix = -rr3 * (dkxuix * psc3 + dkxuipx * dsc3) * 0.5 + gtri3 * dkxrx - gtri6 * rxqkrx - gtri4 * ((uixqkrx + rxqkuix) * psc5 + (uixqkrpx + rxqkuipx) * dsc5) * 0.5;
                    ttm3riy = -rr3 * (dkxuiy * psc3 + dkxuipy * dsc3) * 0.5 + gtri3 * dkxry - gtri6 * rxqkry - gtri4 * ((uixqkry + rxqkuiy) * psc5 + (uixqkrpy + rxqkuipy) * dsc5) * 0.5;
                    ttm3riz = -rr3 * (dkxuiz * psc3 + dkxuipz * dsc3) * 0.5 + gtri3 * dkxrz - gtri6 * rxqkrz - gtri4 * ((uixqkrz + rxqkuiz) * psc5 + (uixqkrpz + rxqkuipz) * dsc5) * 0.5;
                }
                /*
                 * Account for partially excluded induced interactions.
                 */
                double temp3 = 0.5 * rr3 * ((gli1 + gli6) * scalep + (glip1 + glip6) * scaled);
                double temp5 = 0.5 * rr5 * ((gli2 + gli7) * scalep + (glip2 + glip7) * scaled);
                final double temp7 = 0.5 * rr7 * (gli3 * scalep + glip3 * scaled);
                final double fridmpx = temp3 * ddsc3x + temp5 * ddsc5x + temp7 * ddsc7x;
                final double fridmpy = temp3 * ddsc3y + temp5 * ddsc5y + temp7 * ddsc7y;
                final double fridmpz = temp3 * ddsc3z + temp5 * ddsc5z + temp7 * ddsc7z;
                /*
                 * Find some scaling terms for induced-induced force.
                 */
                temp3 = 0.5 * rr3 * scip2;
                temp5 = -0.5 * rr5 * (sci3 * scip4 + scip3 * sci4);
                final double findmpx = temp3 * ddsc3x + temp5 * ddsc5x;
                final double findmpy = temp3 * ddsc3y + temp5 * ddsc5y;
                final double findmpz = temp3 * ddsc3z + temp5 * ddsc5z;
                /*
                 * Modify the forces for partially excluded interactions.
                 */
                ftm2ix = ftm2ix - fridmpx - findmpx;
                ftm2iy = ftm2iy - fridmpy - findmpy;
                ftm2iz = ftm2iz - fridmpz - findmpz;
                /*
                 * Correction to convert mutual to direct polarization force.
                 */
                if (polarization == Polarization.DIRECT) {
                    final double gfd = 0.5 * (bn2 * scip2 - bn3 * (scip3 * sci4 + sci3 * scip4));
                    final double gfdr = 0.5 * (rr5 * scip2 * usc3 - rr7 * (scip3 * sci4 + sci3 * scip4) * usc5);
                    ftm2ix = ftm2ix - gfd * xr - 0.5 * bn2 * (sci4 * pix + scip4 * uix + sci3 * pkx + scip3 * ukx);
                    ftm2iy = ftm2iy - gfd * yr - 0.5 * bn2 * (sci4 * piy + scip4 * uiy + sci3 * pky + scip3 * uky);
                    ftm2iz = ftm2iz - gfd * zr - 0.5 * bn2 * (sci4 * piz + scip4 * uiz + sci3 * pkz + scip3 * ukz);
                    final double fdirx = gfdr * xr + 0.5 * usc5 * rr5 * (sci4 * pix + scip4 * uix + sci3 * pkx + scip3 * ukx);
                    final double fdiry = gfdr * yr + 0.5 * usc5 * rr5 * (sci4 * piy + scip4 * uiy + sci3 * pky + scip3 * uky);
                    final double fdirz = gfdr * zr + 0.5 * usc5 * rr5 * (sci4 * piz + scip4 * uiz + sci3 * pkz + scip3 * ukz);
                    ftm2ix = ftm2ix + fdirx + findmpx;
                    ftm2iy = ftm2iy + fdiry + findmpy;
                    ftm2iz = ftm2iz + fdirz + findmpz;
                }
                /**
                 * Handle the case where scaling is used.
                 */
                ftm2ix = ftm2ix - ftm2rix;
                ftm2iy = ftm2iy - ftm2riy;
                ftm2iz = ftm2iz - ftm2riz;
                ttm2ix = ttm2ix - ttm2rix;
                ttm2iy = ttm2iy - ttm2riy;
                ttm2iz = ttm2iz - ttm2riz;
                ttm3ix = ttm3ix - ttm3rix;
                ttm3iy = ttm3iy - ttm3riy;
                ttm3iz = ttm3iz - ttm3riz;
                gx_local[i] += polarizationScale * selfScale * ftm2ix;
                gy_local[i] += polarizationScale * selfScale * ftm2iy;
                gz_local[i] += polarizationScale * selfScale * ftm2iz;
                tx_local[i] += polarizationScale * selfScale * ttm2ix;
                ty_local[i] += polarizationScale * selfScale * ttm2iy;
                tz_local[i] += polarizationScale * selfScale * ttm2iz;
                gxk_local[k] -= polarizationScale * selfScale * ftm2ix;
                gyk_local[k] -= polarizationScale * selfScale * ftm2iy;
                gzk_local[k] -= polarizationScale * selfScale * ftm2iz;
                txk_local[k] += polarizationScale * selfScale * ttm3ix;
                tyk_local[k] += polarizationScale * selfScale * ttm3iy;
                tzk_local[k] += polarizationScale * selfScale * ttm3iz;
                if (lambdaTerm) {
                    dUdL += dEdLSign * dlPowPol * e;
                    d2UdL2 += dEdLSign * d2lPowPol * e;
                    lx_local[i] += dEdLSign * dlPowPol * selfScale * ftm2ix;
                    ly_local[i] += dEdLSign * dlPowPol * selfScale * ftm2iy;
                    lz_local[i] += dEdLSign * dlPowPol * selfScale * ftm2iz;
                    ltx_local[i] += dEdLSign * dlPowPol * selfScale * ttm2ix;
                    lty_local[i] += dEdLSign * dlPowPol * selfScale * ttm2iy;
                    ltz_local[i] += dEdLSign * dlPowPol * selfScale * ttm2iz;

                    lxk_local[k] -= dEdLSign * dlPowPol * selfScale * ftm2ix;
                    lyk_local[k] -= dEdLSign * dlPowPol * selfScale * ftm2iy;
                    lzk_local[k] -= dEdLSign * dlPowPol * selfScale * ftm2iz;
                    ltxk_local[k] += dEdLSign * dlPowPol * selfScale * ttm3ix;
                    ltyk_local[k] += dEdLSign * dlPowPol * selfScale * ttm3iy;
                    ltzk_local[k] += dEdLSign * dlPowPol * selfScale * ttm3iz;
                }
                return polarizationScale * e;
            }
        }
    }

    private class InitializationRegion extends ParallelRegion {

        private final InitializationLoop initializationLoop[];

        public InitializationRegion(int maxThreads) {
            initializationLoop = new InitializationLoop[maxThreads];
            for (int i = 0; i < maxThreads; i++) {
                initializationLoop[i] = new InitializationLoop();
            }
        }

        @Override
        public void run() {
            try {
                execute(0, nAtoms - 1, initializationLoop[getThreadIndex()]);
            } catch (Exception e) {
                String message = "Fatal exception expanding coordinates in thread: " + getThreadIndex() + "\n";
                logger.log(Level.SEVERE, message, e);
            }
        }

        private class InitializationLoop extends IntegerForLoop {

            private final double in[] = new double[3];
            private final double out[] = new double[3];
            private final double x[] = coordinates[0][0];
            private final double y[] = coordinates[0][1];
            private final double z[] = coordinates[0][2];
            // Extra padding to avert cache interference.
            private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;
            private long pad8, pad9, pada, padb, padc, padd, pade, padf;

            @Override
            public void run(int lb, int ub) {
                /**
                 * Initialize the local coordinate arrays.
                 */
                for (int i = lb; i <= ub; i++) {
                    double xyz[] = atoms[i].getXYZ();
                    x[i] = xyz[0];
                    y[i] = xyz[1];
                    z[i] = xyz[2];
                    use[i] = true;
                    sharedPermanentField[0].set(i, 0.0);
                    sharedPermanentField[1].set(i, 0.0);
                    sharedPermanentField[2].set(i, 0.0);
                    sharedPermanentFieldCR[0].set(i, 0.0);
                    sharedPermanentFieldCR[1].set(i, 0.0);
                    sharedPermanentFieldCR[2].set(i, 0.0);
                    sharedMutualField[0].set(i, 0.0);
                    sharedMutualField[1].set(i, 0.0);
                    sharedMutualField[2].set(i, 0.0);
                    sharedMutualFieldCR[0].set(i, 0.0);
                    sharedMutualFieldCR[1].set(i, 0.0);
                    sharedMutualFieldCR[2].set(i, 0.0);
                }

                /**
                 * Expand coordinates.
                 */
                List<SymOp> symOps = crystal.spaceGroup.symOps;
                for (int iSymm = 1; iSymm < nSymm; iSymm++) {
                    SymOp symOp = symOps.get(iSymm);
                    double xs[] = coordinates[iSymm][0];
                    double ys[] = coordinates[iSymm][1];
                    double zs[] = coordinates[iSymm][2];
                    for (int i = lb; i <= ub; i++) {
                        in[0] = x[i];
                        in[1] = y[i];
                        in[2] = z[i];
                        crystal.applySymOp(in, out, symOp);
                        xs[i] = out[0];
                        ys[i] = out[1];
                        zs[i] = out[2];
                    }
                }

                /**
                 * Initialize the coordinate gradient arrays.
                 */
                if (gradient) {
                    for (int i = lb; i <= ub; i++) {
                        sharedGrad[0].set(i, 0.0);
                        sharedGrad[1].set(i, 0.0);
                        sharedGrad[2].set(i, 0.0);
                        sharedTorque[0].set(i, 0.0);
                        sharedTorque[1].set(i, 0.0);
                        sharedTorque[2].set(i, 0.0);
                    }
                }

                /**
                 * Initialize the lambda gradient arrays.
                 */
                if (lambdaTerm) {
                    for (int i = lb; i <= ub; i++) {
                        shareddEdLdX[0].set(i, 0.0);
                        shareddEdLdX[1].set(i, 0.0);
                        shareddEdLdX[2].set(i, 0.0);
                        shareddEdLTorque[0].set(i, 0.0);
                        shareddEdLTorque[1].set(i, 0.0);
                        shareddEdLTorque[2].set(i, 0.0);
                    }
                }
            }
        }
    }

    private class RotateMultipolesRegion extends ParallelRegion {

        private final RotateMultipolesLoop rotateMultipolesLoop[];

        public RotateMultipolesRegion(int nt) {
            rotateMultipolesLoop = new RotateMultipolesLoop[nt];
            for (int i = 0; i < nt; i++) {
                rotateMultipolesLoop[i] = new RotateMultipolesLoop();
            }
        }

        @Override
        public void run() {
            try {
                execute(0, nAtoms - 1, rotateMultipolesLoop[getThreadIndex()]);
            } catch (Exception e) {
                String message = "Fatal exception rotating multipoles in thread " + getThreadIndex() + "\n";
                logger.log(Level.SEVERE, message, e);
            }
        }

        private class RotateMultipolesLoop extends IntegerForLoop {
            // Local variables

            private final double localOrigin[] = new double[3];
            private final double xAxis[] = new double[3];
            private final double yAxis[] = new double[3];
            private final double zAxis[] = new double[3];
            private final double rotmat[][] = new double[3][3];
            private final double tempDipole[] = new double[3];
            private final double tempQuadrupole[][] = new double[3][3];
            private final double dipole[] = new double[3];
            private final double quadrupole[][] = new double[3][3];
            // Extra padding to avert cache interference.
            private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;
            private long pad8, pad9, pada, padb, padc, padd, pade, padf;

            @Override
            public void run(int lb, int ub) {
                for (int iSymm = 0; iSymm < nSymm; iSymm++) {
                    final double x[] = coordinates[iSymm][0];
                    final double y[] = coordinates[iSymm][1];
                    final double z[] = coordinates[iSymm][2];
                    for (int ii = lb; ii <= ub; ii++) {
                        final double in[] = localMultipole[ii];
                        final double out[] = globalMultipole[iSymm][ii];
                        localOrigin[0] = x[ii];
                        localOrigin[1] = y[ii];
                        localOrigin[2] = z[ii];
                        int referenceSites[] = axisAtom[ii];
                        for (int i = 0; i < 3; i++) {
                            zAxis[i] = 0.0;
                            xAxis[i] = 0.0;
                            dipole[i] = 0.0;
                            for (int j = 0; j < 3; j++) {
                                quadrupole[i][j] = 0.0;
                            }
                        }
                        if (referenceSites == null || referenceSites.length < 2) {
                            out[t000] = in[0];
                            out[t100] = 0.0;
                            out[t010] = 0.0;
                            out[t001] = 0.0;
                            out[t200] = 0.0;
                            out[t020] = 0.0;
                            out[t002] = 0.0;
                            out[t110] = 0.0;
                            out[t101] = 0.0;
                            out[t011] = 0.0;
                            continue;
                        }
                        switch (frame[ii]) {
                            case BISECTOR:
                                int index = referenceSites[0];
                                zAxis[0] = x[index];
                                zAxis[1] = y[index];
                                zAxis[2] = z[index];
                                index = referenceSites[1];
                                xAxis[0] = x[index];
                                xAxis[1] = y[index];
                                xAxis[2] = z[index];
                                diff(zAxis, localOrigin, zAxis);
                                norm(zAxis, zAxis);
                                diff(xAxis, localOrigin, xAxis);
                                norm(xAxis, xAxis);
                                sum(xAxis, zAxis, zAxis);
                                norm(zAxis, zAxis);
                                rotmat[0][2] = zAxis[0];
                                rotmat[1][2] = zAxis[1];
                                rotmat[2][2] = zAxis[2];
                                double dot = dot(xAxis, zAxis);
                                scalar(zAxis, dot, zAxis);
                                diff(xAxis, zAxis, xAxis);
                                norm(xAxis, xAxis);
                                rotmat[0][0] = xAxis[0];
                                rotmat[1][0] = xAxis[1];
                                rotmat[2][0] = xAxis[2];
                                break;
                            case ZTHENBISECTOR:
                                index = referenceSites[0];
                                zAxis[0] = x[index];
                                zAxis[1] = y[index];
                                zAxis[2] = z[index];
                                index = referenceSites[1];
                                xAxis[0] = x[index];
                                xAxis[1] = y[index];
                                xAxis[2] = z[index];
                                index = referenceSites[2];
                                yAxis[0] = x[index];
                                yAxis[1] = y[index];
                                yAxis[2] = z[index];
                                diff(zAxis, localOrigin, zAxis);
                                norm(zAxis, zAxis);
                                rotmat[0][2] = zAxis[0];
                                rotmat[1][2] = zAxis[1];
                                rotmat[2][2] = zAxis[2];
                                diff(xAxis, localOrigin, xAxis);
                                norm(xAxis, xAxis);
                                diff(yAxis, localOrigin, yAxis);
                                norm(yAxis, yAxis);
                                sum(xAxis, yAxis, xAxis);
                                norm(xAxis, xAxis);
                                dot = dot(xAxis, zAxis);
                                scalar(zAxis, dot, zAxis);
                                diff(xAxis, zAxis, xAxis);
                                norm(xAxis, xAxis);
                                rotmat[0][0] = xAxis[0];
                                rotmat[1][0] = xAxis[1];
                                rotmat[2][0] = xAxis[2];
                                break;
                            case ZTHENX:
                            default:
                                index = referenceSites[0];
                                zAxis[0] = x[index];
                                zAxis[1] = y[index];
                                zAxis[2] = z[index];
                                index = referenceSites[1];
                                xAxis[0] = x[index];
                                xAxis[1] = y[index];
                                xAxis[2] = z[index];
                                diff(zAxis, localOrigin, zAxis);
                                norm(zAxis, zAxis);
                                rotmat[0][2] = zAxis[0];
                                rotmat[1][2] = zAxis[1];
                                rotmat[2][2] = zAxis[2];
                                diff(xAxis, localOrigin, xAxis);
                                dot = dot(xAxis, zAxis);
                                scalar(zAxis, dot, zAxis);
                                diff(xAxis, zAxis, xAxis);
                                norm(xAxis, xAxis);
                                rotmat[0][0] = xAxis[0];
                                rotmat[1][0] = xAxis[1];
                                rotmat[2][0] = xAxis[2];
                        }
                        // Finally the Y elements.
                        rotmat[0][1] = rotmat[2][0] * rotmat[1][2] - rotmat[1][0] * rotmat[2][2];
                        rotmat[1][1] = rotmat[0][0] * rotmat[2][2] - rotmat[2][0] * rotmat[0][2];
                        rotmat[2][1] = rotmat[1][0] * rotmat[0][2] - rotmat[0][0] * rotmat[1][2];
                        // Do the rotation.
                        tempDipole[0] = in[t100];
                        tempDipole[1] = in[t010];
                        tempDipole[2] = in[t001];
                        tempQuadrupole[0][0] = in[t200];
                        tempQuadrupole[1][1] = in[t020];
                        tempQuadrupole[2][2] = in[t002];
                        tempQuadrupole[0][1] = in[t110];
                        tempQuadrupole[0][2] = in[t101];
                        tempQuadrupole[1][2] = in[t011];
                        tempQuadrupole[1][0] = in[t110];
                        tempQuadrupole[2][0] = in[t101];
                        tempQuadrupole[2][1] = in[t011];

                        // Check for chiral flipping.
                        if (frame[ii] == MultipoleType.MultipoleFrameDefinition.ZTHENX
                                && referenceSites.length == 3) {
                            localOrigin[0] = x[ii];
                            localOrigin[1] = y[ii];
                            localOrigin[2] = z[ii];
                            int index = referenceSites[0];
                            zAxis[0] = x[index];
                            zAxis[1] = y[index];
                            zAxis[2] = z[index];
                            index = referenceSites[1];
                            xAxis[0] = x[index];
                            xAxis[1] = y[index];
                            xAxis[2] = z[index];
                            index = referenceSites[2];
                            yAxis[0] = x[index];
                            yAxis[1] = y[index];
                            yAxis[2] = z[index];
                            diff(localOrigin, yAxis, localOrigin);
                            diff(zAxis, yAxis, zAxis);
                            diff(xAxis, yAxis, xAxis);
                            double c1 = zAxis[1] * xAxis[2] - zAxis[2] * xAxis[1];
                            double c2 = xAxis[1] * localOrigin[2] - xAxis[2] * localOrigin[1];
                            double c3 = localOrigin[1] * zAxis[2] - localOrigin[2] * zAxis[1];
                            double vol = localOrigin[0] * c1 + zAxis[0] * c2 + xAxis[0] * c3;
                            if (vol < 0.0) {
                                tempDipole[1] = -tempDipole[1];
                                tempQuadrupole[0][1] = -tempQuadrupole[0][1];
                                tempQuadrupole[1][0] = -tempQuadrupole[1][0];
                                tempQuadrupole[1][2] = -tempQuadrupole[1][2];
                                tempQuadrupole[2][1] = -tempQuadrupole[2][1];
                            }
                        }
                        for (int i = 0; i < 3; i++) {
                            double[] rotmati = rotmat[i];
                            double[] quadrupolei = quadrupole[i];
                            for (int j = 0; j < 3; j++) {
                                double[] rotmatj = rotmat[j];
                                dipole[i] += rotmati[j] * tempDipole[j];
                                if (j < i) {
                                    quadrupolei[j] = quadrupole[j][i];
                                } else {
                                    for (int k = 0; k < 3; k++) {
                                        double[] localQuadrupolek = tempQuadrupole[k];
                                        quadrupolei[j] += rotmati[k]
                                                * (rotmatj[0] * localQuadrupolek[0]
                                                + rotmatj[1] * localQuadrupolek[1]
                                                + rotmatj[2] * localQuadrupolek[2]);
                                    }
                                }
                            }
                        }

                        double dipoleScale = 1.0;
                        double quadrupoleScale = 1.0;
                        out[t000] = in[0];
                        out[t100] = dipole[0] * dipoleScale;
                        out[t010] = dipole[1] * dipoleScale;
                        out[t001] = dipole[2] * dipoleScale;
                        out[t200] = quadrupole[0][0] * quadrupoleScale;
                        out[t020] = quadrupole[1][1] * quadrupoleScale;
                        out[t002] = quadrupole[2][2] * quadrupoleScale;
                        out[t110] = quadrupole[0][1] * quadrupoleScale;
                        out[t101] = quadrupole[0][2] * quadrupoleScale;
                        out[t011] = quadrupole[1][2] * quadrupoleScale;

                        /* No rotation (useful for checking non-torque parts
                         * of the multipole gradient
                        out[t000] = scale * in[t000];
                        out[t100] = scale * in[t100] * dipoleScale;
                        out[t010] = scale * in[t010] * dipoleScale;
                        out[t001] = scale * in[t001] * dipoleScale;
                        out[t200] = scale * in[t200] * quadrupoleScale;
                        out[t020] = scale * in[t020] * quadrupoleScale;
                        out[t002] = scale * in[t002] * quadrupoleScale;
                        out[t110] = scale * in[t110] * quadrupoleScale;
                        out[t101] = scale * in[t101] * quadrupoleScale;
                        out[t011] = scale * in[t011] * quadrupoleScale;
                         */
                        PolarizeType polarizeType = atoms[ii].getPolarizeType();
                        polarizability[ii] = polarizeType.polarizability;
                    }
                }
            }
        }
    }

    private class ExpandInducedDipolesRegion extends ParallelRegion {

        private final ExpandInducedDipoleLoop expandInducedDipoleLoop[];

        public ExpandInducedDipolesRegion(int maxThreads) {
            expandInducedDipoleLoop = new ExpandInducedDipoleLoop[maxThreads];
            for (int i = 0; i < maxThreads; i++) {
                expandInducedDipoleLoop[i] = new ExpandInducedDipoleLoop();
            }
        }

        @Override
        public void run() {
            try {
                execute(0, nAtoms - 1, expandInducedDipoleLoop[getThreadIndex()]);
            } catch (Exception e) {
                String message = "Fatal exception expanding coordinates in thread: " + getThreadIndex() + "\n";
                logger.log(Level.SEVERE, message, e);
            }
        }

        private class ExpandInducedDipoleLoop extends IntegerForLoop {

            @Override
            public IntegerSchedule schedule() {
                return pairWiseSchedule;
            }

            @Override
            public void run(int lb, int ub) {
                for (int s = 1; s < nSymm; s++) {
                    SymOp symOp = crystal.spaceGroup.symOps.get(s);
                    for (int ii = lb; ii <= ub; ii++) {
                        crystal.applySymRot(inducedDipole[0][ii], inducedDipole[s][ii], symOp);
                        crystal.applySymRot(inducedDipoleCR[0][ii], inducedDipoleCR[s][ii], symOp);
                    }
                }
            }
        }
    }

    private class ReduceRegion extends ParallelRegion {

        private final TorqueLoop torqueLoop[];
        private final ReduceLoop reduceLoop[];

        public ReduceRegion(int maxThreads) {
            torqueLoop = new TorqueLoop[maxThreads];
            reduceLoop = new ReduceLoop[maxThreads];
            for (int i = 0; i < maxThreads; i++) {
                torqueLoop[i] = new TorqueLoop();
                reduceLoop[i] = new ReduceLoop();
            }
        }

        @Override
        public void run() {
            try {
                int ti = getThreadIndex();
                execute(0, nAtoms - 1, torqueLoop[ti]);
                execute(0, nAtoms - 1, reduceLoop[ti]);
            } catch (Exception e) {
                String message = "Fatal exception computing torque in thread " + getThreadIndex() + "\n";
                logger.log(Level.SEVERE, message, e);
            }
        }

        private class TorqueLoop extends IntegerForLoop {

            private final double trq[] = new double[3];
            private final double u[] = new double[3];
            private final double v[] = new double[3];
            private final double w[] = new double[3];
            private final double r[] = new double[3];
            private final double s[] = new double[3];
            private final double uv[] = new double[3];
            private final double uw[] = new double[3];
            private final double vw[] = new double[3];
            private final double ur[] = new double[3];
            private final double us[] = new double[3];
            private final double vs[] = new double[3];
            private final double ws[] = new double[3];
            private final double t1[] = new double[3];
            private final double t2[] = new double[3];
            private final double localOrigin[] = new double[3];
            // Extra padding to avert cache interference.
            private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;
            private long pad8, pad9, pada, padb, padc, padd, pade, padf;

            @Override
            public void run(int lb, int ub) {
                if (gradient) {
                    for (int i = lb; i <= ub; i++) {
                        torque(i, sharedTorque, sharedGrad);
                    }
                }
                if (lambdaTerm) {
                    for (int i = lb; i <= ub; i++) {
                        torque(i, shareddEdLTorque, shareddEdLdX);
                    }
                }
            }

            public void torque(int i, SharedDoubleArray[] torq,
                    SharedDoubleArray[] grad) {
                final int ax[] = axisAtom[i];
                // Ions, for example, have no torque.
                if (ax == null || ax.length < 2) {
                    return;
                }
                final int ia = ax[0];
                final int ib = i;
                final int ic = ax[1];
                int id = 0;
                trq[0] = torq[0].get(i);
                trq[1] = torq[1].get(i);
                trq[2] = torq[2].get(i);
                double x[] = coordinates[0][0];
                double y[] = coordinates[0][1];
                double z[] = coordinates[0][2];
                localOrigin[0] = x[ib];
                localOrigin[1] = y[ib];
                localOrigin[2] = z[ib];
                u[0] = x[ia];
                u[1] = y[ia];
                u[2] = z[ia];
                v[0] = x[ic];
                v[1] = y[ic];
                v[2] = z[ic];
                // Construct the three rotation axes for the local frame
                diff(u, localOrigin, u);
                diff(v, localOrigin, v);
                switch (frame[i]) {
                    default:
                    case ZTHENX:
                    case BISECTOR:
                        cross(u, v, w);
                        break;
                    case TRISECTOR:
                    case ZTHENBISECTOR:
                        id = ax[2];
                        w[0] = x[id];
                        w[1] = y[id];
                        w[2] = z[id];
                        diff(w, localOrigin, w);
                }

                double ru = r(u);
                double rv = r(v);
                double rw = r(w);
                scalar(u, 1.0 / ru, u);
                scalar(v, 1.0 / rv, v);
                scalar(w, 1.0 / rw, w);
                // Find the perpendicular and angle for each pair of axes.
                cross(v, u, uv);
                cross(w, u, uw);
                cross(w, v, vw);
                double ruv = r(uv);
                double ruw = r(uw);
                double rvw = r(vw);
                scalar(uv, 1.0 / ruv, uv);
                scalar(uw, 1.0 / ruw, uw);
                scalar(vw, 1.0 / rvw, vw);
                // Compute the sine of the angle between the rotation axes.
                double uvcos = dot(u, v);
                double uvsin = sqrt(1.0 - uvcos * uvcos);
                //double uwcos = dot(u, w);
                //double uwsin = sqrt(1.0 - uwcos * uwcos);
                //double vwcos = dot(v, w);
                //double vwsin = sqrt(1.0 - vwcos * vwcos);
                    /*
                 * Negative of dot product of torque with unit vectors gives result of
                 * infinitesimal rotation along these vectors.
                 */
                double dphidu = -(trq[0] * u[0] + trq[1] * u[1] + trq[2] * u[2]);
                double dphidv = -(trq[0] * v[0] + trq[1] * v[1] + trq[2] * v[2]);
                double dphidw = -(trq[0] * w[0] + trq[1] * w[1] + trq[2] * w[2]);
                switch (frame[i]) {
                    case ZTHENBISECTOR:
                        // Build some additional axes needed for the Z-then-Bisector method
                        sum(v, w, r);
                        cross(u, r, s);
                        double rr = r(r);
                        double rs = r(s);
                        scalar(r, 1.0 / rr, r);
                        scalar(s, 1.0 / rs, s);
                        // Find the perpendicular and angle for each pair of axes.
                        cross(r, u, ur);
                        cross(s, u, us);
                        cross(s, v, vs);
                        cross(s, w, ws);
                        double rur = r(ur);
                        double rus = r(us);
                        double rvs = r(vs);
                        double rws = r(ws);
                        scalar(ur, 1.0 / rur, ur);
                        scalar(us, 1.0 / rus, us);
                        scalar(vs, 1.0 / rvs, vs);
                        scalar(ws, 1.0 / rws, ws);
                        // Compute the sine of the angle between the rotation axes
                        double urcos = dot(u, r);
                        double ursin = sqrt(1.0 - urcos * urcos);
                        //double uscos = dot(u, s);
                        //double ussin = sqrt(1.0 - uscos * uscos);
                        double vscos = dot(v, s);
                        double vssin = sqrt(1.0 - vscos * vscos);
                        double wscos = dot(w, s);
                        double wssin = sqrt(1.0 - wscos * wscos);
                        // Compute the projection of v and w onto the ru-plane
                        scalar(s, -vscos, t1);
                        scalar(s, -wscos, t2);
                        sum(v, t1, t1);
                        sum(w, t2, t2);
                        double rt1 = r(t1);
                        double rt2 = r(t2);
                        scalar(t1, 1.0 / rt1, t1);
                        scalar(t2, 1.0 / rt2, t2);
                        double ut1cos = dot(u, t1);
                        double ut1sin = sqrt(1.0 - ut1cos * ut1cos);
                        double ut2cos = dot(u, t2);
                        double ut2sin = sqrt(1.0 - ut2cos * ut2cos);
                        double dphidr = -(trq[0] * r[0] + trq[1] * r[1] + trq[2] * r[2]);
                        double dphids = -(trq[0] * s[0] + trq[1] * s[1] + trq[2] * s[2]);
                        for (int j = 0; j < 3; j++) {
                            double du = ur[j] * dphidr / (ru * ursin) + us[j] * dphids / ru;
                            double dv = (vssin * s[j] - vscos * t1[j]) * dphidu / (rv * (ut1sin + ut2sin));
                            double dw = (wssin * s[j] - wscos * t2[j]) * dphidu / (rw * (ut1sin + ut2sin));
                            grad[j].addAndGet(ia, du);
                            grad[j].addAndGet(ic, dv);
                            grad[j].addAndGet(id, dw);
                            grad[j].addAndGet(ib, -du - dv - dw);
                        }
                        break;
                    case ZTHENX:
                        for (int j = 0; j < 3; j++) {
                            double du = uv[j] * dphidv / (ru * uvsin) + uw[j] * dphidw / ru;
                            double dv = -uv[j] * dphidu / (rv * uvsin);
                            grad[j].addAndGet(ia, du);
                            grad[j].addAndGet(ic, dv);
                            grad[j].addAndGet(ib, -du - dv);
                        }
                        break;
                    case BISECTOR:
                        for (int j = 0; j < 3; j++) {
                            double du = uv[j] * dphidv / (ru * uvsin) + 0.5 * uw[j] * dphidw / ru;
                            double dv = -uv[j] * dphidu / (rv * uvsin) + 0.5 * vw[j] * dphidw / rv;
                            grad[j].addAndGet(ia, du);
                            grad[j].addAndGet(ic, dv);
                            grad[j].addAndGet(ib, -du - dv);
                        }
                        break;
                    default:
                        String message = "Fatal exception: Unknown frame definition: " + frame[i] + "\n";
                        logger.log(Level.SEVERE, message);
                }

            }
        }

        private class ReduceLoop extends IntegerForLoop {

            @Override
            public void run(int lb, int ub) throws Exception {
                for (int i = lb; i <= ub; i++) {
                    atoms[i].addToXYZGradient(
                            sharedGrad[0].get(i),
                            sharedGrad[1].get(i),
                            sharedGrad[2].get(i));
                }
            }
        }
    }

    /**
     * Determine the real space Ewald parameters and permenant multipole self
     * energy.
     * 
     * @param off Real space cutoff.
     * @param aewald Ewald convergence parameter (0.0 turns off reciprocal space).
     */
    private void setEwaldParameters(double off, double aewald) {
        off2 = off * off;
        alsq2 = 2.0 * aewald * aewald;
        if (aewald <= 0.0) {
            piEwald = Double.POSITIVE_INFINITY;
        } else {
            piEwald = 1.0 / (sqrtPi * aewald);
        }
        aewald3 = 4.0 / 3.0 * pow(aewald, 3.0) / sqrtPi;

        if (aewald > 0.0) {
            an0 = alsq2 * piEwald;
            an1 = alsq2 * an0;
            an2 = alsq2 * an1;
            an3 = alsq2 * an2;
            an4 = alsq2 * an3;
            an5 = alsq2 * an4;
        } else {
            an0 = 0.0;
            an1 = 0.0;
            an2 = 0.0;
            an3 = 0.0;
            an4 = 0.0;
            an5 = 0.0;
        }
    }

    /**
     * Real space Ewald is cutoff at ~7 A, compared to ~12 A for vdW, so the
     * number of neighbors is much more compact. A specific list for real space 
     * Ewald is filled during computation of the permanent real space field
     * that includes only evaluated interactions. Subsequent real space loops,
     * especially the SCF, then do not waste time evaluating pairwise distances
     * outside the cutoff.
     */
    private void allocateRealSpaceNeighborLists() {
        for (int i = 0; i < nSymm; i++) {
            for (int j = 0; j < nAtoms; j++) {
                int size = neighborLists[i][j].length;
                if (realSpaceLists[i][j] == null || realSpaceLists[i][j].length < size) {
                    realSpaceLists[i][j] = new int[size];
                }
            }
        }
    }

    /*
     * A precision of 1.0e-8 results in an Ewald coefficient that 
     * ensure continuity in the real space gradient, 
     * but at the cost of increased amplitudes for high frequency 
     * reciprocal space structure factors.
     */
    private double ewaldCoefficient(double cutoff, double precision) {

        double eps = 1.0e-8;
        if (precision < 1.0e-1) {
            eps = precision;
        }

        /*
         * Get an approximate value from cutoff and tolerance.
         */
        double ratio = eps + 1.0;
        double x = 0.5;
        int i = 0;
        // Larger values lead to a more "delta-function-like" Gaussian
        while (ratio >= eps) {
            i++;
            x *= 2.0;
            ratio = erfc(x * cutoff) / cutoff;
        }
        /*
         * Use a binary search to refine the coefficient.
         */
        int k = i + 60;
        double xlo = 0.0;
        double xhi = x;
        for (int j = 0; j < k; j++) {
            x = (xlo + xhi) / 2.0;
            ratio = erfc(x * cutoff) / cutoff;
            if (ratio >= eps) {
                xlo = x;
            } else {
                xhi = x;
            }
        }
        return x;
    }

    public static double ewaldCutoff(double coeff, double maxCutoff, double eps) {
        /*
         * Set the tolerance value; use of 1.0d-8 requires strict convergence
         * of the real Space sum.
         */
        double ratio = erfc(coeff * maxCutoff) / maxCutoff;

        if (ratio > eps) {
            return maxCutoff;
        }

        /*
         * Use a binary search to refine the coefficient.
         */
        double xlo = 0.0;
        double xhi = maxCutoff;
        double cutoff = 0.0;
        for (int j = 0; j < 100; j++) {
            cutoff = (xlo + xhi) / 2.0;
            ratio = erfc(coeff * cutoff) / cutoff;
            if (ratio >= eps) {
                xlo = cutoff;
            } else {
                xhi = cutoff;
            }
        }
        return cutoff;
    }

    /**
     * Given an array of atoms (with atom types), assign multipole types and
     * reference sites.
     *
     * @param atoms
     *            List
     * @param forceField
     *            ForceField
     */
    private void assignMultipoles() {
        if (forceField == null) {
            String message = "No force field is defined.\n";
            logger.log(Level.SEVERE, message);
        }
        if (forceField.getForceFieldTypeCount(ForceFieldType.MULTIPOLE) < 1) {
            String message = "Force field has no multipole types.\n";
            logger.log(Level.SEVERE, message);
            return;
        }
        if (nAtoms < 1) {
            String message = "No atoms are defined.\n";
            logger.log(Level.SEVERE, message);
            return;
        }
        for (int i = 0; i < nAtoms; i++) {
            if (!assignMultipole(i)) {
                Atom atom = atoms[i];
                String message = "No multipole could be assigned to atom:\n"
                        + atom + "\nof type:\n" + atom.getAtomType();
                logger.log(Level.SEVERE, message);
            }
        }
        /**
         * Check for multipoles that were not assigned correctly.
         */
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nAtoms; i++) {
            boolean flag = false;
            for (int j = 0; j < 10; j++) {
                if (Double.isNaN(localMultipole[i][j])) {
                    flag = true;
                    break;
                }
            }
            if (flag) {
                sb.append("\n" + atoms[i].toString() + "\n");
                sb.append(format("%d", i + 1));
                for (int j = 0; j < 10; j++) {
                    sb.append(format(" %8.3f", localMultipole[i][j]));
                }
                sb.append("\n");
            }
        }
        if (sb.length() > 0) {
            String message = "Fatal exception: Error assigning multipoles. " + sb.toString();
            logger.log(Level.SEVERE, message);
            System.exit(-1);
        }
    }

    private boolean assignMultipole(int i) {
        Atom atom = atoms[i];
        AtomType atomType = atoms[i].getAtomType();
        if (atomType == null) {
            String message = "Fatal exception: Multipoles can only be assigned to atoms that have been typed.";
            logger.severe(message);
        }
        PolarizeType polarizeType = forceField.getPolarizeType(atomType.getKey());
        if (polarizeType != null) {
            atom.setPolarizeType(polarizeType);
        } else {
            String message = "Fatal Exception: No polarization type was found for " + atom.toString();
            logger.severe(message);
        }
        MultipoleType multipoleType = null;
        String key = null;
        // No reference atoms.
        key = atomType.getKey() + " 0 0";
        multipoleType = forceField.getMultipoleType(key);
        if (multipoleType != null) {
            atom.setMultipoleType(multipoleType, null);
            localMultipole[i][t000] = multipoleType.charge;
            localMultipole[i][t100] = multipoleType.dipole[0];
            localMultipole[i][t010] = multipoleType.dipole[1];
            localMultipole[i][t001] = multipoleType.dipole[2];
            localMultipole[i][t200] = multipoleType.quadrupole[0][0];
            localMultipole[i][t020] = multipoleType.quadrupole[1][1];
            localMultipole[i][t002] = multipoleType.quadrupole[2][2];
            localMultipole[i][t110] = multipoleType.quadrupole[0][1];
            localMultipole[i][t101] = multipoleType.quadrupole[0][2];
            localMultipole[i][t011] = multipoleType.quadrupole[1][2];
            axisAtom[i] = null;
            frame[i] = multipoleType.frameDefinition;
            return true;
        }
        // No bonds.
        List<Bond> bonds = atom.getBonds();
        if (bonds == null || bonds.size() < 1) {
            String message = "Multipoles can only be assigned after bonded relationships are defined.\n";
            logger.severe(message);
        }
        // 1 reference atom.
        for (Bond b : bonds) {
            Atom atom2 = b.get1_2(atom);
            key = atomType.getKey() + " " + atom2.getAtomType().getKey() + " 0";
            multipoleType = multipoleType = forceField.getMultipoleType(key);
            if (multipoleType != null) {
                int multipoleReferenceAtoms[] = new int[1];
                multipoleReferenceAtoms[0] = atom2.xyzIndex - 1;
                atom.setMultipoleType(multipoleType, null);
                localMultipole[i][0] = multipoleType.charge;
                localMultipole[i][1] = multipoleType.dipole[0];
                localMultipole[i][2] = multipoleType.dipole[1];
                localMultipole[i][3] = multipoleType.dipole[2];
                localMultipole[i][4] = multipoleType.quadrupole[0][0];
                localMultipole[i][5] = multipoleType.quadrupole[1][1];
                localMultipole[i][6] = multipoleType.quadrupole[2][2];
                localMultipole[i][7] = multipoleType.quadrupole[0][1];
                localMultipole[i][8] = multipoleType.quadrupole[0][2];
                localMultipole[i][9] = multipoleType.quadrupole[1][2];
                axisAtom[i] = multipoleReferenceAtoms;
                frame[i] = multipoleType.frameDefinition;
                return true;
            }
        }
        // 2 reference atoms.
        for (Bond b : bonds) {
            Atom atom2 = b.get1_2(atom);
            String key2 = atom2.getAtomType().getKey();
            for (Bond b2 : bonds) {
                if (b == b2) {
                    continue;
                }
                Atom atom3 = b2.get1_2(atom);
                String key3 = atom3.getAtomType().getKey();
                key = atomType.getKey() + " " + key2 + " " + key3;
                multipoleType = forceField.getMultipoleType(key);
                if (multipoleType != null) {
                    int multipoleReferenceAtoms[] = new int[2];
                    multipoleReferenceAtoms[0] = atom2.xyzIndex - 1;
                    multipoleReferenceAtoms[1] = atom3.xyzIndex - 1;
                    atom.setMultipoleType(multipoleType, null);
                    localMultipole[i][0] = multipoleType.charge;
                    localMultipole[i][1] = multipoleType.dipole[0];
                    localMultipole[i][2] = multipoleType.dipole[1];
                    localMultipole[i][3] = multipoleType.dipole[2];
                    localMultipole[i][4] = multipoleType.quadrupole[0][0];
                    localMultipole[i][5] = multipoleType.quadrupole[1][1];
                    localMultipole[i][6] = multipoleType.quadrupole[2][2];
                    localMultipole[i][7] = multipoleType.quadrupole[0][1];
                    localMultipole[i][8] = multipoleType.quadrupole[0][2];
                    localMultipole[i][9] = multipoleType.quadrupole[1][2];
                    axisAtom[i] = multipoleReferenceAtoms;
                    frame[i] = multipoleType.frameDefinition;
                    return true;
                }
            }
        }
        // 3 reference atoms.
        for (Bond b : bonds) {
            Atom atom2 = b.get1_2(atom);
            String key2 = atom2.getAtomType().getKey();
            for (Bond b2 : bonds) {
                if (b == b2) {
                    continue;
                }
                Atom atom3 = b2.get1_2(atom);
                String key3 = atom3.getAtomType().getKey();
                for (Bond b3 : bonds) {
                    if (b == b3 || b2 == b3) {
                        continue;
                    }
                    Atom atom4 = b3.get1_2(atom);
                    String key4 = atom4.getAtomType().getKey();
                    key = atomType.getKey() + " " + key2 + " " + key3 + " " + key4;
                    multipoleType = forceField.getMultipoleType(key);
                    if (multipoleType != null) {
                        int multipoleReferenceAtoms[] = new int[3];
                        multipoleReferenceAtoms[0] = atom2.xyzIndex - 1;
                        multipoleReferenceAtoms[1] = atom3.xyzIndex - 1;
                        multipoleReferenceAtoms[2] = atom4.xyzIndex - 1;
                        atom.setMultipoleType(multipoleType, null);
                        localMultipole[i][0] = multipoleType.charge;
                        localMultipole[i][1] = multipoleType.dipole[0];
                        localMultipole[i][2] = multipoleType.dipole[1];
                        localMultipole[i][3] = multipoleType.dipole[2];
                        localMultipole[i][4] = multipoleType.quadrupole[0][0];
                        localMultipole[i][5] = multipoleType.quadrupole[1][1];
                        localMultipole[i][6] = multipoleType.quadrupole[2][2];
                        localMultipole[i][7] = multipoleType.quadrupole[0][1];
                        localMultipole[i][8] = multipoleType.quadrupole[0][2];
                        localMultipole[i][9] = multipoleType.quadrupole[1][2];
                        axisAtom[i] = multipoleReferenceAtoms;
                        frame[i] = multipoleType.frameDefinition;
                        return true;
                    }
                }
                List<Angle> angles = atom.getAngles();
                for (Angle angle : angles) {
                    Atom atom4 = angle.get1_3(atom);
                    if (atom4 != null) {
                        String key4 = atom4.getAtomType().getKey();
                        key = atomType.getKey() + " " + key2 + " " + key3 + " " + key4;
                        multipoleType = forceField.getMultipoleType(key);
                        if (multipoleType != null) {
                            int multipoleReferenceAtoms[] = new int[3];
                            multipoleReferenceAtoms[0] = atom2.xyzIndex - 1;
                            multipoleReferenceAtoms[1] = atom3.xyzIndex - 1;
                            multipoleReferenceAtoms[2] = atom4.xyzIndex - 1;
                            atom.setMultipoleType(multipoleType, null);
                            localMultipole[i][0] = multipoleType.charge;
                            localMultipole[i][1] = multipoleType.dipole[0];
                            localMultipole[i][2] = multipoleType.dipole[1];
                            localMultipole[i][3] = multipoleType.dipole[2];
                            localMultipole[i][4] = multipoleType.quadrupole[0][0];
                            localMultipole[i][5] = multipoleType.quadrupole[1][1];
                            localMultipole[i][6] = multipoleType.quadrupole[2][2];
                            localMultipole[i][7] = multipoleType.quadrupole[0][1];
                            localMultipole[i][8] = multipoleType.quadrupole[0][2];
                            localMultipole[i][9] = multipoleType.quadrupole[1][2];
                            axisAtom[i] = multipoleReferenceAtoms;
                            frame[i] = multipoleType.frameDefinition;
                            return true;
                        }
                    }
                }
            }
        }
        // Revert to a 2 reference atom definition that may include a 1-3 site.
        // For example a hydrogen on water.
        for (Bond b : bonds) {
            Atom atom2 = b.get1_2(atom);
            String key2 = atom2.getAtomType().getKey();
            List<Angle> angles = atom.getAngles();
            for (Angle angle : angles) {
                Atom atom3 = angle.get1_3(atom);
                if (atom3 != null) {
                    String key3 = atom3.getAtomType().getKey();
                    key = atomType.getKey() + " " + key2 + " " + key3;
                    multipoleType = forceField.getMultipoleType(key);
                    if (multipoleType != null) {
                        int multipoleReferenceAtoms[] = new int[2];
                        multipoleReferenceAtoms[0] = atom2.xyzIndex - 1;
                        multipoleReferenceAtoms[1] = atom3.xyzIndex - 1;
                        atom.setMultipoleType(multipoleType, null);
                        localMultipole[i][0] = multipoleType.charge;
                        localMultipole[i][1] = multipoleType.dipole[0];
                        localMultipole[i][2] = multipoleType.dipole[1];
                        localMultipole[i][3] = multipoleType.dipole[2];
                        localMultipole[i][4] = multipoleType.quadrupole[0][0];
                        localMultipole[i][5] = multipoleType.quadrupole[1][1];
                        localMultipole[i][6] = multipoleType.quadrupole[2][2];
                        localMultipole[i][7] = multipoleType.quadrupole[0][1];
                        localMultipole[i][8] = multipoleType.quadrupole[0][2];
                        localMultipole[i][9] = multipoleType.quadrupole[1][2];
                        axisAtom[i] = multipoleReferenceAtoms;
                        frame[i] = multipoleType.frameDefinition;
                        return true;
                    }
                    for (Angle angle2 : angles) {
                        Atom atom4 = angle2.get1_3(atom);
                        if (atom4 != null && atom4 != atom3) {
                            String key4 = atom4.getAtomType().getKey();
                            key = atomType.getKey() + " " + key2 + " " + key3 + " " + key4;
                            multipoleType = forceField.getMultipoleType(key);
                            if (multipoleType != null) {
                                int multipoleReferenceAtoms[] = new int[3];
                                multipoleReferenceAtoms[0] = atom2.xyzIndex - 1;
                                multipoleReferenceAtoms[1] = atom3.xyzIndex - 1;
                                multipoleReferenceAtoms[2] = atom4.xyzIndex - 1;
                                atom.setMultipoleType(multipoleType, null);
                                localMultipole[i][0] = multipoleType.charge;
                                localMultipole[i][1] = multipoleType.dipole[0];
                                localMultipole[i][2] = multipoleType.dipole[1];
                                localMultipole[i][3] = multipoleType.dipole[2];
                                localMultipole[i][4] = multipoleType.quadrupole[0][0];
                                localMultipole[i][5] = multipoleType.quadrupole[1][1];
                                localMultipole[i][6] = multipoleType.quadrupole[2][2];
                                localMultipole[i][7] = multipoleType.quadrupole[0][1];
                                localMultipole[i][8] = multipoleType.quadrupole[0][2];
                                localMultipole[i][9] = multipoleType.quadrupole[1][2];
                                axisAtom[i] = multipoleReferenceAtoms;
                                frame[i] = multipoleType.frameDefinition;
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private void assignPolarizationGroups() {
        /**
         * Find directly connected group members for each atom.
         */
        List<Integer> group = new ArrayList<Integer>();
        List<Integer> polarizationGroup = new ArrayList<Integer>();
        //int g11 = 0;
        for (Atom ai : atoms) {
            group.clear();
            polarizationGroup.clear();
            Integer index = ai.getXYZIndex() - 1;
            group.add(index);
            polarizationGroup.add(ai.getType());
            PolarizeType polarizeType = ai.getPolarizeType();
            if (polarizeType != null) {
                if (polarizeType.polarizationGroup != null) {
                    for (int i : polarizeType.polarizationGroup) {
                        if (!polarizationGroup.contains(i)) {
                            polarizationGroup.add(i);
                        }
                    }
                    growGroup(polarizationGroup, group, ai);
                    Collections.sort(group);
                    ip11[index] = new int[group.size()];
                    int j = 0;
                    for (int k : group) {
                        ip11[index][j++] = k;
                    }
                } else {
                    ip11[index] = new int[group.size()];
                    int j = 0;
                    for (int k : group) {
                        ip11[index][j++] = k;
                    }
                }
                //g11 += ip11[index].length;
                //System.out.println(format("%d %d", index + 1, g11));
            } else {
                String message = "The polarize keyword was not found for atom "
                        + (index + 1) + " with type " + ai.getType();
                logger.severe(message);
            }
        }
        /**
         * Find 1-2 group relationships.
         */
        int mask[] = new int[nAtoms];
        List<Integer> list = new ArrayList<Integer>();
        List<Integer> keep = new ArrayList<Integer>();
        for (int i = 0; i < nAtoms; i++) {
            mask[i] = -1;
        }
        for (int i = 0; i < nAtoms; i++) {
            list.clear();
            for (int j : ip11[i]) {
                list.add(j);
                mask[j] = i;
            }
            keep.clear();
            for (int j : list) {
                Atom aj = atoms[j];
                ArrayList<Bond> bonds = aj.getBonds();
                for (Bond b : bonds) {
                    Atom ak = b.get1_2(aj);
                    int k = ak.getXYZIndex() - 1;
                    if (mask[k] != i) {
                        keep.add(k);
                    }
                }
            }
            list.clear();
            for (int j : keep) {
                for (int k : ip11[j]) {
                    list.add(k);
                }
            }
            Collections.sort(list);
            ip12[i] = new int[list.size()];
            int j = 0;
            for (int k : list) {
                ip12[i][j++] = k;
            }
        }
        /**
         * Find 1-3 group relationships.
         */
        for (int i = 0; i < nAtoms; i++) {
            mask[i] = -1;
        }
        for (int i = 0; i < nAtoms; i++) {
            for (int j : ip11[i]) {
                mask[j] = i;
            }
            for (int j : ip12[i]) {
                mask[j] = i;
            }
            list.clear();
            for (int j : ip12[i]) {
                for (int k : ip12[j]) {
                    if (mask[k] != i) {
                        if (!list.contains(k)) {
                            list.add(k);
                        }
                    }
                }
            }
            ip13[i] = new int[list.size()];
            Collections.sort(list);
            int j = 0;
            for (int k : list) {
                ip13[i][j++] = k;
            }
        }
    }

    /**
     * A recursive method that checks all atoms bonded to the seed atom for
     * inclusion in the polarization group. The method is called on each newly
     * found group member.
     *
     * @param polarizationGroup
     *            Atom types that should be included in the group.
     * @param group
     *            XYZ indeces of current group members.
     * @param seed
     *            The bonds of the seed atom are queried for inclusion in the
     *            group.
     */
    private void growGroup(List<Integer> polarizationGroup,
            List<Integer> group, Atom seed) {
        List<Bond> bonds = seed.getBonds();
        for (Bond bi : bonds) {
            Atom aj = bi.get1_2(seed);
            int tj = aj.getType();
            boolean added = false;
            for (int g : polarizationGroup) {
                if (g == tj) {
                    Integer index = aj.getXYZIndex() - 1;
                    if (!group.contains(index)) {
                        group.add(index);
                        added = true;
                        break;
                    }
                }
            }
            if (added) {
                PolarizeType polarizeType = aj.getPolarizeType();
                for (int i : polarizeType.polarizationGroup) {
                    if (!polarizationGroup.contains(i)) {
                        polarizationGroup.add(i);
                    }
                }
                growGroup(polarizationGroup, group, aj);
            }
        }
    }

    private void torque(int iSymm,
            double tx[], double ty[], double tz[],
            double gx[], double gy[], double gz[],
            double origin[], double[] u,
            double v[], double w[], double uv[], double uw[],
            double vw[], double ur[], double us[], double vs[],
            double ws[], double t1[], double t2[], double r[],
            double s[]) {
        for (int i = 0; i < nAtoms; i++) {
            final int ax[] = axisAtom[i];
            // Ions, for example, have no torque.
            if (ax == null || ax.length < 2) {
                return;
            }
            final int ia = ax[0];
            final int ib = i;
            final int ic = ax[1];
            int id = 0;
            double x[] = coordinates[iSymm][0];
            double y[] = coordinates[iSymm][1];
            double z[] = coordinates[iSymm][2];
            origin[0] = x[ib];
            origin[1] = y[ib];
            origin[2] = z[ib];
            u[0] = x[ia];
            u[1] = y[ia];
            u[2] = z[ia];
            v[0] = x[ic];
            v[1] = y[ic];
            v[2] = z[ic];
            // Construct the three rotation axes for the local frame
            diff(u, origin, u);
            diff(v, origin, v);
            switch (frame[i]) {
                default:
                case ZTHENX:
                case BISECTOR:
                    cross(u, v, w);
                    break;
                case TRISECTOR:
                case ZTHENBISECTOR:
                    id = ax[2];
                    w[0] = x[id];
                    w[1] = y[id];
                    w[2] = z[id];
                    diff(w, origin, w);
            }

            double ru = r(u);
            double rv = r(v);
            double rw = r(w);
            scalar(u, 1.0 / ru, u);
            scalar(v, 1.0 / rv, v);
            scalar(w, 1.0 / rw, w);
            // Find the perpendicular and angle for each pair of axes.
            cross(v, u, uv);
            cross(w, u, uw);
            cross(w, v, vw);
            double ruv = r(uv);
            double ruw = r(uw);
            double rvw = r(vw);
            scalar(uv, 1.0 / ruv, uv);
            scalar(uw, 1.0 / ruw, uw);
            scalar(vw, 1.0 / rvw, vw);
            // Compute the sine of the angle between the rotation axes.
            double uvcos = dot(u, v);
            double uvsin = sqrt(1.0 - uvcos * uvcos);
            //double uwcos = dot(u, w);
            //double uwsin = sqrt(1.0 - uwcos * uwcos);
            //double vwcos = dot(v, w);
            //double vwsin = sqrt(1.0 - vwcos * vwcos);
                    /*
             * Negative of dot product of torque with unit vectors gives result of
             * infinitesimal rotation along these vectors.
             */
            double dphidu = -(tx[i] * u[0] + ty[i] * u[1] + tz[i] * u[2]);
            double dphidv = -(tx[i] * v[0] + ty[i] * v[1] + tz[i] * v[2]);
            double dphidw = -(tx[i] * w[0] + ty[i] * w[1] + tz[i] * w[2]);
            switch (frame[i]) {
                case ZTHENBISECTOR:
                    // Build some additional axes needed for the Z-then-Bisector method
                    sum(v, w, r);
                    cross(u, r, s);
                    double rr = r(r);
                    double rs = r(s);
                    scalar(r, 1.0 / rr, r);
                    scalar(s, 1.0 / rs, s);
                    // Find the perpendicular and angle for each pair of axes.
                    cross(r, u, ur);
                    cross(s, u, us);
                    cross(s, v, vs);
                    cross(s, w, ws);
                    double rur = r(ur);
                    double rus = r(us);
                    double rvs = r(vs);
                    double rws = r(ws);
                    scalar(ur, 1.0 / rur, ur);
                    scalar(us, 1.0 / rus, us);
                    scalar(vs, 1.0 / rvs, vs);
                    scalar(ws, 1.0 / rws, ws);
                    // Compute the sine of the angle between the rotation axes
                    double urcos = dot(u, r);
                    double ursin = sqrt(1.0 - urcos * urcos);
                    //double uscos = dot(u, s);
                    //double ussin = sqrt(1.0 - uscos * uscos);
                    double vscos = dot(v, s);
                    double vssin = sqrt(1.0 - vscos * vscos);
                    double wscos = dot(w, s);
                    double wssin = sqrt(1.0 - wscos * wscos);
                    // Compute the projection of v and w onto the ru-plane
                    scalar(s, -vscos, t1);
                    scalar(s, -wscos, t2);
                    sum(v, t1, t1);
                    sum(w, t2, t2);
                    double rt1 = r(t1);
                    double rt2 = r(t2);
                    scalar(t1, 1.0 / rt1, t1);
                    scalar(t2, 1.0 / rt2, t2);
                    double ut1cos = dot(u, t1);
                    double ut1sin = sqrt(1.0 - ut1cos * ut1cos);
                    double ut2cos = dot(u, t2);
                    double ut2sin = sqrt(1.0 - ut2cos * ut2cos);
                    double dphidr = -(tx[i] * r[0] + ty[i] * r[1] + tz[i] * r[2]);
                    double dphids = -(tx[i] * s[0] + ty[i] * s[1] + tz[i] * s[2]);
                    for (int j = 0; j < 3; j++) {
                        double du = ur[j] * dphidr / (ru * ursin) + us[j] * dphids / ru;
                        double dv = (vssin * s[j] - vscos * t1[j]) * dphidu / (rv * (ut1sin + ut2sin));
                        double dw = (wssin * s[j] - wscos * t2[j]) * dphidu / (rw * (ut1sin + ut2sin));
                        u[j] = du;
                        v[j] = dv;
                        w[j] = dw;
                        r[j] = -du - dv - dw;
                        //sharedGrad[j].addAndGet(ia, du);
                        //sharedGrad[j].addAndGet(ic, dv);
                        //sharedGrad[j].addAndGet(id, dw);
                        //sharedGrad[j].addAndGet(ib, -du - dv - dw);
                    }
                    gx[ia] += u[0];
                    gy[ia] += u[1];
                    gz[ia] += u[2];
                    gx[ic] += v[0];
                    gy[ic] += v[1];
                    gz[ic] += v[2];
                    gx[id] += w[0];
                    gy[id] += w[1];
                    gz[id] += w[2];
                    gx[ib] += r[0];
                    gy[ib] += r[1];
                    gz[ib] += r[2];
                    break;
                case ZTHENX:
                    for (int j = 0; j < 3; j++) {
                        double du = uv[j] * dphidv / (ru * uvsin) + uw[j] * dphidw / ru;
                        double dv = -uv[j] * dphidu / (rv * uvsin);
                        u[j] = du;
                        v[j] = dv;
                        w[j] = -du - dv;
                        //sharedGrad[j].addAndGet(ia, du);
                        //sharedGrad[j].addAndGet(ic, dv);
                        //sharedGrad[j].addAndGet(ib, -du - dv);
                    }
                    gx[ia] += u[0];
                    gy[ia] += u[1];
                    gz[ia] += u[2];
                    gx[ic] += v[0];
                    gy[ic] += v[1];
                    gz[ic] += v[2];
                    gx[ib] += w[0];
                    gy[ib] += w[1];
                    gz[ib] += w[2];
                    break;
                case BISECTOR:
                    for (int j = 0; j < 3; j++) {
                        double du = uv[j] * dphidv / (ru * uvsin) + 0.5 * uw[j] * dphidw / ru;
                        double dv = -uv[j] * dphidu / (rv * uvsin) + 0.5 * vw[j] * dphidw / rv;
                        u[j] = du;
                        v[j] = dv;
                        w[j] = -du - dv;
                        //sharedGrad[j].addAndGet(ia, du);
                        //sharedGrad[j].addAndGet(ic, dv);
                        //sharedGrad[j].addAndGet(ib, -du - dv);
                    }
                    gx[ia] += u[0];
                    gy[ia] += u[1];
                    gz[ia] += u[2];
                    gx[ic] += v[0];
                    gy[ic] += v[1];
                    gz[ic] += v[2];
                    gx[ib] += w[0];
                    gy[ib] += w[1];
                    gz[ib] += w[2];
                    break;
                default:
                    String message = "Fatal exception: Unknown frame definition: " + frame[i] + "\n";
                    logger.log(Level.SEVERE, message);
            }
        }
    }

    /**
     * Set the electrostatic lambda scaling factor.
     *
     * @param lambda Must satisfy greater than or equal to 0.0 and less than or
     *      equal to 1.0.
     */
    @Override
    public void setLambda(double lambda) {
        assert (lambda >= 0.0 && lambda <= 1.0);
        this.lambda = lambda;

        lAlpha = permanentLambdaAlpha * (1.0 - lambda) * (1.0 - lambda);
        /**
         * f = sqrt(r^2 + lAlpha)
         * df/dL = alpha * (lambda - 1.0) / f
         * df/dL = -dlAlpha / f
         */
        dlAlpha = permanentLambdaAlpha * (1.0 - lambda);
        d2lAlpha = -permanentLambdaAlpha;

        lPowPerm = pow(lambda, permanentLambdaExponent);
        dlPowPerm = permanentLambdaExponent * pow(lambda, permanentLambdaExponent - 1.0);
        if (permanentLambdaExponent >= 2.0) {
            d2lPowPerm = permanentLambdaExponent * (permanentLambdaExponent - 1.0) * pow(lambda, permanentLambdaExponent - 2.0);
        } else {
            d2lPowPerm = 0.0;
        }

        /**
         * Polarization is turned on from polarizationLambdaStart .. 1.0.
         */
        if (lambda < polarizationLambdaStart) {
            lPowPol = 0.0;
            dlPowPol = 0.0;
            d2lPowPol = 0.0;
        } else if (lambda <= polarizationLambdaEnd) {
            double polarizationWindow = polarizationLambdaStart - polarizationLambdaEnd;
            double polarizationLambdaScale = 1.0 / polarizationWindow;
            polarizationLambda = polarizationLambdaScale * (lambda - polarizationLambdaStart);
            lPowPol = pow(polarizationLambda, polarizationLambdaExponent);
            dlPowPol = polarizationLambdaExponent * pow(polarizationLambda, polarizationLambdaExponent - 1.0);
            if (polarizationLambdaExponent >= 2.0) {
                d2lPowPol = polarizationLambdaExponent * (polarizationLambdaExponent - 1.0) * pow(polarizationLambda, polarizationLambdaExponent - 2.0);
            } else {
                d2lPowPol = 0.0;
            }
            /**
             * Add the chain rule term due to shrinking the lambda range 
             * for the polarization energy. 
             */
            dlPowPol *= polarizationLambdaScale;
            d2lPowPol *= (polarizationLambdaScale * polarizationLambdaScale);
        } else {
            lPowPol = 1.0;
            dlPowPol = 0.0;
            d2lPowPol = 0.0;
        }

        /**
         * Set up the softcore flag.
         */
        boolean softAtoms = false;
        for (int i = 0; i < nAtoms; i++) {
            isSoft[i] = atoms[i].applyLambda();
            if (isSoft[i]) {
                softAtoms = true;
                // Outer loop atom hard, inner loop atom soft.
                softCore[0][i] = true;
                // Both soft.
                softCore[1][i] = true;
            } else {
                // Both hard - full interaction.
                softCore[0][i] = false;
                // Outer loop atom soft, inner loop atom hard.
                softCore[1][i] = true;
            }
        }
        if (!softAtoms) {
            logger.warning(" No atoms are selected for soft core electrostatics.\n");
        }
    }

    /**
     * Get the current lambda scale value.
     * @return lambda
     */
    @Override
    public double getLambda() {
        return lambda;
    }

    @Override
    public double getdEdL() {
        return shareddEdLambda.get();
    }

    @Override
    public double getd2EdL2() {
        return sharedd2EdLambda2.get();
    }

    @Override
    public void getdEdXdL(double[] gradient) {
        int index = 0;
        for (int i = 0; i < nAtoms; i++) {
            gradient[index++] += shareddEdLdX[0].get(i);
            gradient[index++] += shareddEdLdX[1].get(i);
            gradient[index++] += shareddEdLdX[2].get(i);
        }
    }
    /**
     * Number of unique tensors for given order.
     */
    private static final int tensorCount = TensorRecursion.tensorCount(3);
    private final double sfPhi[] = new double[tensorCount];
    private final double sPhi[] = new double[tensorCount];
    private static final double oneThird = 1.0 / 3.0;
}
