//******************************************************************************
//
// Title:       Force Field X.
// Description: Force Field X - Software for Molecular Biophysics.
// Copyright:   Copyright (c) Michael J. Schnieders 2001-2019.
//
// This file is part of Force Field X.
//
// Force Field X is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 3 as published by
// the Free Software Foundation.
//
// Force Field X is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
// details.
//
// You should have received a copy of the GNU General Public License along with
// Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
// Place, Suite 330, Boston, MA 02111-1307 USA
//
// Linking this library statically or dynamically with other modules is making a
// combined work based on this library. Thus, the terms and conditions of the
// GNU General Public License cover the whole combination.
//
// As a special exception, the copyright holders of this library give you
// permission to link this library with independent modules to produce an
// executable, regardless of the license terms of these independent modules, and
// to copy and distribute the resulting executable under terms of your choice,
// provided that you also meet, for each linked independent module, the terms
// and conditions of the license of that module. An independent module is a
// module which is not derived from or based on this library. If you modify this
// library, you may extend this exception to your version of the library, but
// you are not obligated to do so. If you do not wish to do so, delete this
// exception statement from your version.
//
//******************************************************************************

package test

import ffx.potential.Utilities
import ffx.potential.cli.PotentialScript

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

import org.apache.commons.math3.ml.clustering.CentroidCluster
import org.apache.commons.math3.ml.clustering.Clusterable
import org.apache.commons.math3.ml.clustering.MultiKMeansPlusPlusClusterer
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer


import java.util.logging.Level;

/**
 * The Cluster script clusters structures utilizing RMSD.
 *
 * @author Aaron J. Nessler
 * @author Mallory R. Tollefson
 * @author Michael J. Schnieders
 * <br>
 * Usage:
 * <br>
 * ffxc Cluster [options] &lt;filename&gt;
 */
@Command(description = " Cluster structures using an RMSD matrix.", name = "ffxc Cluster")
class Cluster extends PotentialScript {

    /**
     * -a or --algorithm Clustering algorithm to use.
     */
    @Option(names = ['-a', '--algorithm'], paramLabel = "kmeans",
            description = "Algorithm to be used during clustering.")
    private String algorithm = "kmeans";

    /**
     * -k or --clusters Clustering algorithm to use.
     */
    @Option(names = ['-k', '--clusters'], paramLabel = "3",
            description = "Number of desired clusters for the input data.")
    private int clusters = 3;

    /**
     * The final argument(s) should be one or more filenames.
     */
    @Parameters(arity = "1", paramLabel = "files",
            description = 'The RMSD matrix.')
    List<String> filenames = null

    private File baseDir = null

    void setBaseDir(File baseDir) {
        this.baseDir = baseDir
    }

    /**
     * Execute the script.
     */
    @Override
    Cluster run() {
        if (!init()) {
            return this
        }

        if (filenames == null || filenames.isEmpty()) {
            logger.info(helpString())
            return this
        }
        File file = new File(filenames.get(0));
        int nDim = 0;
        List<double[]> distMatrix = new ArrayList<double[]>();
        // TODO: Have FFX calculate RMSD rather than read them in... (likely requires separate script)
        // Read in the RMSD matrix.
        try {
            FileReader fr = new FileReader(file);
            BufferedReader br = new BufferedReader(fr);
            String data = br.readLine();
            // Check for blank lines at the top of the file
            while (data != null && data.trim().equals("")) {
                data = br.readLine();
            }
            if (data == null) {
                logger.severe("No data in RMSD file.");
            }
            String[] tokens = data.trim().split("\t");
            // Expect a n x n matrix of distance values.
            nDim = tokens.size();
            for (int i = 0; i < nDim; i++) {
                double[] tokens2 = new double[nDim];
                for (int j = 0; j < nDim; j++) {
                    tokens2[j] = tokens[j].toDouble();
                }
                distMatrix.add(tokens2);
                data = br.readLine();
                if (data != null) {
                    tokens = data.trim().split("\t");
                }
            }
            br.close();
            fr.close();
        } catch (IOException e) {
            logger.severe(e.toString());
        }
        if (distMatrix == null) {
            logger.severe("Input read attempt failed.");
        }
        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(String.format("Original Distance Matrix:\n"))
            String tempString = "";
            for (double[] i : distMatrix) {
                for (int j = 0; j < nDim; j++) {
                    tempString += String.format("%f\t", i[j]);
                }
                tempString += "\n";
            }
            logger.finest(tempString);
        }

        // Min-Max normalization of distances (not important if all inputs are on the same scale)
//        double minimumDist=0;
//        double maximumDist=0;
//        for (double[] distArray in distMatrix){
//            for (double dist in distArray){
//                if( minimumDist>dist){
//                    minimumDist = dist;
//                }
//                if(maximumDist<dist){
//                    maximumDist = dist;
//                }
//            }
//        }
//        for(int i = 0; i<distMatrix.size(); i++) {
//            for (int j = 0; j < distMatrix.get(i).size(); j++) {
//                distMatrix.get(i)[j] = (distMatrix.get(i)[j] - minimumDist) / (maximumDist - minimumDist);
//            }
//        }
//
//        if (logger.isLoggable(Level.FINEST)) {
//            logger.finest(String.format("\nNormalized Matrix:\n"));
//            String tempString2 = "";
//            for (double[] i : distMatrix) {
//                for (int j = 0; j < nDim; j++) {
//                    tempString2 += String.format("%f\t", i[j]);
//                }
//                tempString2 += "\n";
//            }
//            logger.finest(tempString2);
//        }

        // Input the RMSD matrix to the clustering algorithm
        // Use the org.apache.commons.math3.ml.clustering package.
        KMeansPlusPlusClusterer<ClusterWrapper> kClust1 = new KMeansPlusPlusClusterer<ClusterWrapper>(clusters, 10000);
        List<ClusterWrapper> myClusterables = new ArrayList<ClusterWrapper>();
        int id = 0;
        for (double[] i : distMatrix) {
            myClusterables.add(new ClusterWrapper(i, id));
            id++;
        }
        List<CentroidCluster<ClusterWrapper>> kClusters = kClust1.cluster(myClusterables);

        if (algorithm.toUpperCase() == "MULTIKMEANS") {
            MultiKMeansPlusPlusClusterer<ClusterWrapper> kClust2 = new MultiKMeansPlusPlusClusterer<>(kClust1, 10000)
            kClusters = kClust2.cluster(myClusterables);
        }

        // TODO: Output the clusters in a useful way.
        //Temp output method prints to screen
        double ttwss = 0;
        for (int i = 0; i < kClusters.size(); i++) {
            double twss = 0; // Reset cluster within distance
            logger.info(String.format("Cluster: " + i));
            double[] sum = new double[kClusters.get(0).getPoints()[0].getPoint().size()]
            for (ClusterWrapper clusterWrapper : kClusters.get(i).getPoints()) {
                logger.info(String.format("Row: %d", clusterWrapper.getUUID()));
                double[] distArray = clusterWrapper.getPoint();
                // Implement TWSS
                for(int j = 0; j<sum.size();j++) {
                    twss += Math.pow(distArray[j] - kClusters.get(i).getCenter().getPoint()[j], 2)
                }
            }
            twss = Math.sqrt(twss);
            logger.info(String.format("Cluster TWSS: %f", twss));
            ttwss+=twss;
        }
        logger.info(String.format("\nTotal TWSS: %f", ttwss));

        return this
    }
}
class ClusterWrapper implements Clusterable{
    private double[] point;
    private final int UUID;

    public ClusterWrapper(double[] distances, int ID) {
        this.point = distances;
        UUID=ID;
    }

    public double[] getPoint() {
        return point;
    }

    public int getUUID() {
        return UUID;
    }
}
