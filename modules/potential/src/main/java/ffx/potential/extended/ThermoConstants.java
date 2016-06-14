package ffx.potential.extended;

import static org.apache.commons.math3.util.FastMath.sqrt;

/**
 * 
 * @author slucore
 */
public class ThermoConstants {

    /**
     * Boltzmann constant in units of g*Ang**2/ps**2/mole/K
     */
    public static final double kB = 0.83144725;
    /**
     * Conversion from kcal/mole to g*Ang**2/ps**2.
     */
    public static final double convert = 4.1840e2;
    /**
     * Gas constant (in Kcal/mole/Kelvin).
     */
    public static final double R = 1.9872066e-3;
    /**
     * Random force conversion to kcal/mol/A;
     */
    public static final double randomConvert = sqrt(4.184) / 10e9;
    public static final double randomConvert2 = randomConvert * randomConvert;
    
}
