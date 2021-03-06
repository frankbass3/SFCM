/*
 * Image/J Plugins
 * Copyright (C) 2002-2010 Jarek Sacha
 * Author's email: jsacha at users dot sourceforge dot net
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * Latest release available at http://sourceforge.net/projects/ij-plugins/
 */

package clustering.old.kmeans;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;
import ij.process.ImageConverter;
import ij.process.StackConverter;



/**
 * ImageJ plugin wrapper for k-means clustering algorithm.
 *
 * @author Jarek Sacha
 * @see KMeans
 */
public final class KMeansClusteringPlugin implements PlugIn {

    public static final String RESULTS_WINDOW_TITLE = "k-means cluster centers";

    private static final KMeans.Config CONFIG = new KMeans.Config();
    private static boolean showCentroidImage;
    private static boolean sendToResultTable;

    private static final boolean APPLY_LUT = false;
    private static final boolean AUTO_BRIGHTNESS = true;


    private static final String TITLE = "k-means Clustering";
    private static final String ABOUT = "" +
            "k-means Clustering performs pixel-based segmentation of multi-band\n" +
            "images. An image stack is interpreted as a set of bands corresponding to\n" +
            "the same image. For instance, an RGB color images has three bands: red,\n" +
            "green, and blue. Each pixels is represented by an n-valued vector , where\n" +
            "n is a number of bands, for instance, a 3-value vector [r,g,b] in case of\n" +
            "a color image.\n" +
            "Each cluster is defined by its centroid in n-dimensional space. Pixels are\n" +
            "grouped by their proximity to cluster's centroids.\n" +
            "Cluster centroids are determined using a heuristics: initially centroids\n" +
            "are randomly initialized and then their location is interactively\n" +
            "optimized.\n" +
            "For more information on this and other clustering approaches see:\n" +
            "Anil K. Jain and Richard C. Dubes, \"Algorithms for Clustering Data\",\n" +
            "Prentice Hall, 1988.\n" +
            "http://homepages.inf.ed.ac.uk/rbf/BOOKS/JAIN/Clustering_Jain_Dubes.pdf\n";


    @Override
    public void run(final String arg) {

        if ("about".equalsIgnoreCase(arg)) {
            IJ.showMessage("About " + TITLE, ABOUT);
            return;
        }

        // Get reference to current image
        final ImagePlus imp = IJ.getImage();

        if (imp.getType() == ImagePlus.COLOR_256) {
            IJ.error(TITLE, "Only color images are supported.");
            return;
        }

        // Create configuration dialog
        final GenericDialog dialog = new GenericDialog("K-means Configuration");
        dialog.addNumericField("Number_of_clusters", CONFIG.getNumberOfClusters(), 0);
        dialog.addNumericField("Cluster_center_tolerance", CONFIG.getTolerance(), 8);
        dialog.addCheckbox("Enable_randomization_seed", CONFIG.isRandomizationSeedEnabled());
        dialog.addNumericField("Randomization_seed", CONFIG.getRandomizationSeed(), 0);
        dialog.addCheckbox("Show_clusters_as_centroid_value", showCentroidImage);
        dialog.addCheckbox("Enable_clustering_animation", CONFIG.isClusterAnimationEnabled());
        dialog.addCheckbox("Print optimization trace", CONFIG.isPrintTraceEnabled());
        dialog.addCheckbox("Send_to_results_table", sendToResultTable);

        // Show dialog
        dialog.showDialog();
        if (dialog.wasCanceled()) {
            return;
        }

        // Read configuration from dialog
        CONFIG.setNumberOfClusters((int) Math.round(dialog.getNextNumber()));
        CONFIG.setTolerance((float) dialog.getNextNumber());
        CONFIG.setRandomizationSeedEnabled(dialog.getNextBoolean());
        CONFIG.setRandomizationSeed((int) Math.round(dialog.getNextNumber()));
        showCentroidImage = dialog.getNextBoolean();
        CONFIG.setClusterAnimationEnabled(dialog.getNextBoolean());
        CONFIG.setPrintTraceEnabled(dialog.getNextBoolean());
        sendToResultTable = dialog.getNextBoolean();

        run(imp);
    }


    private void run(final ImagePlus imp) {
        // Convert to a stack of float images
        final ImagePlus stack = convertToFloatStack(imp);

        // Run clustering
        final KMeans kMeans = new KMeans(CONFIG);
//        Roi roi =  imp.getRoi();
//        ByteProcessor mask = (ByteProcessor) imp.getMask();
//        kMeans.setRoi(roi.getBoundingRect());
//        kMeans.setMask(mask);
        final long startTime = System.currentTimeMillis();
        final ByteProcessor bp = kMeans.run(stack.getStack());
        final long endTime = System.currentTimeMillis();

        // Apply default color map
        if (APPLY_LUT) {
        //    bp.setColorModel(defaultColorModel());
        }
        if (AUTO_BRIGHTNESS) {
            bp.setMinAndMax(0, CONFIG.getNumberOfClusters());
        }

        // Show result image
        final ImagePlus r = new ImagePlus("Clusters", bp);
        r.show();

        // Show animation
        if (CONFIG.isClusterAnimationEnabled()) {
            final ImageStack animationStack = kMeans.getClusterAnimation();
            if (APPLY_LUT) {
              //  animationStack.setColorModel(defaultColorModel());
            }
            final ImagePlus animation = new ImagePlus("Cluster animation", animationStack);
            animation.show();
            if (AUTO_BRIGHTNESS) {
                for (int i = 0; i < animationStack.getSize(); i++) {
                    animation.setSlice(i + 1);
                    animation.getProcessor().setMinAndMax(0, CONFIG.getNumberOfClusters());
                }
                animation.setSlice(1);
                animation.updateAndDraw();
            }
        }

        // Show centroid image
        if (showCentroidImage) {
            final ImagePlus cvImp = KMeansUtils.createCentroidImage(imp.getType(),
                    kMeans.getCentroidValueImage());
            cvImp.show();
        }

        if (sendToResultTable) {
            // Send cluster centers to a Result Table
            final float[][] centers = kMeans.getClusterCenters();
            final String[] labels = stack.getStack().getSliceLabels();
            final ResultsTable rt = new ResultsTable();
            for (int i = 0; i < centers.length; i++) {
                rt.incrementCounter();
                final float[] center = centers[i];
                rt.addLabel("Cluster", "" + i);
                for (int j = 0; j < center.length; j++) {
                    final float v = center[j];
                    rt.addValue("" + labels[j], v);
                }
            }
            rt.show(RESULTS_WINDOW_TITLE);
        }

        IJ.showStatus("Clustering completed in " + (endTime - startTime) + " ms.");
    }


    /**
     * Create 3-3-2-RGB color model
     *
     * @return 3-3-2-RGB color model.
     */
//    private static ColorModel defaultColorModel() {
//        final byte[] reds = new byte[256];
//        final byte[] greens = new byte[256];
//        final byte[] blues = new byte[256];
//        for (int i = 0; i < 256; i++) {
//            reds[i] = (byte) (i & 0xe0);
//            greens[i] = (byte) ((i << 3) & 0xe0);
//            blues[i] = (byte) ((i << 6) & 0xc0);
//        }
//
//        return new IndexColorModel(8, 256, reds, greens, blues);
//    }


    /**
     * Convert image to a stack of FloatProcessors.
     *
     * @param src image to convert.
     * @return float stack.
     */
    private static ImagePlus convertToFloatStack(final ImagePlus src) {

        final ImagePlus dest = new Duplicator().run(src);

        // Remember scaling setup
        final boolean doScaling = ImageConverter.getDoScaling();

        try {
            // Disable scaling
            ImageConverter.setDoScaling(false);

            if (src.getType() == ImagePlus.COLOR_RGB) {
                if (src.getStackSize() > 1) {
                    throw new IllegalArgumentException("Unsupported image type: RGB with more than one slice.");
                }
                final ImageConverter converter = new ImageConverter(dest);
                converter.convertToRGBStack();
            }

            if (dest.getStackSize() > 1) {
                final StackConverter converter = new StackConverter(dest);
                converter.convertToGray32();
            } else {
                final ImageConverter converter = new ImageConverter(dest);
                converter.convertToGray32();
            }

            return dest;
        } finally {
            // Restore original scaling option
            ImageConverter.setDoScaling(doScaling);

        }
    }


}
