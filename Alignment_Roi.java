/*
Copyright (c) 2010 Michael Mohn and Jannik Meyer, Ulm University

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

import ij.*;
import ij.io.*;
import ij.gui.*;
import ij.process.*;
import ij.plugin.filter.*;
import java.awt.*;
import java.io.*;
import java.util.*;

public class Alignment_Roi implements PlugInFilter {

	ImagePlus imp;
	int stackSize;
	int selectedSlice; // index of selected slice when plugin is started

	Rectangle roiRect; // Roi
	int roiWidth;
	int roiHeight;

	double[][] refImage; // pixel array for error computation
	int[] correctionX; // array for corrections in x direction
	int[] correctionY; // array for corrections in y direction

	// plugin parameters

	int range; // range of possible translations that are checked
	double power; // loading of error
	int firstSlice; // index of first corrected slice
	int lastSlice; // index of last corrected slice
	String adjustTo; // contains answer to "adjust to" choice
	boolean correctPrevious; // whether slices before firstSlice should be corrected
	boolean correctFollowing; // same for slices after lastSlice
	boolean prevSlice; // for compare to previous slice mode
	double minerror; // min. of computed errors
	int bestXcorr; // correction with least error sum
	int bestYcorr;
	int refSlice; // index of start slice / slice for refImage
	boolean saveFile; // -> save in MultiStackReg File
	boolean doTranslate; // -> apply corrections

	public int setup(String arg, ImagePlus imp) {
		this.imp = imp;
		return DOES_ALL;
	}

	public void run(ImageProcessor ip) {
	try {
		// get Roi
		roiRect = imp.getRoi().getBounds();
		int roiX = (int) roiRect.getX();
		int roiY = (int) roiRect.getY();
		roiWidth = (int) roiRect.getWidth();
		roiHeight = (int) roiRect.getHeight();
		//IJ.showMessage(roiWidth + " x " + roiHeight + " at " + roiX + ", " + roiY);	// uncomment to check Roi

		// get number of slices and currently selected slice
		stackSize = imp.getStack().getSize();
		selectedSlice = imp.getSlice();
		
		//
		// dialog for plugin parameters
		//

		GenericDialog gd = new GenericDialog("Alignment");

		gd.addNumericField("Range (px): +-", 5, 0); // range of checked corrections
		gd.addNumericField("Error exponent", 2.0, 2); // error loading
	
		// plugin mode: compare all slices with selected slice or with their neighbor
		String[] choices = {"selected slice", "previous slice"};
		gd.addChoice("Compare with...", choices, "selected slice");

		// range of corrected slices
		gd.addNumericField("Correct translation from slice", 1, 0);
		gd.addNumericField("to", stackSize, 0);

		// set reference slice with correction (0, 0): first slice, last slice or current slice
		String[] choices2 = {"first slice of range", "last slice of range", "currently selected slice"};
		gd.addChoice("Adjust to...", choices2, "first slice of range");

		// correct head and tail options
		gd.addCheckbox("Correct previous slices", false);
		gd.addCheckbox("Correct following slices", false);

		// save MultiStackReg file option
		gd.addCheckbox("Save MultiStackReg File", false);

		// apply translations option
		gd.addCheckbox("Apply translations", true);
		
		// show dialog
		gd.showDialog();
		if (gd.wasCanceled()) {
			IJ.error("Plugin canceled!");
			return;
		}

		// get values from dialog

		range = (int) gd.getNextNumber();
		power = gd.getNextNumber(); // error loading
		if (gd.getNextChoiceIndex() == 1) prevSlice = true; else prevSlice = false; // plugin mode

		// range of slices
		firstSlice = (int) gd.getNextNumber();
		lastSlice = (int) gd.getNextNumber();

		// correct some errors in user input
		if (firstSlice > lastSlice) { // correct invalid range: swap first and last slice
			IJ.showMessage("Hint", "Range of slices is invalid. Range will be turned the other way round.");
			int temp;
			temp = firstSlice;	// swap first and last slice
			firstSlice = lastSlice;
			lastSlice = temp;
		}
		if (firstSlice < 1) { // if start of range is invalid
			firstSlice = 1;
			IJ.showMessage("Hint", "Range of slices is invalid. Beginning of range is corrected to first slice.");
		}
		if (lastSlice > stackSize) { // if end of range is invalid
			lastSlice = stackSize;
			IJ.showMessage("Hint", "Range of slices is invalid. End of range is corrected to last possible slice.");
		}

		adjustTo = gd.getNextChoice(); // adjust to... option
		correctPrevious = gd.getNextBoolean(); // correct slices before range
		correctFollowing = gd.getNextBoolean(); // correct slices after range
		saveFile = gd.getNextBoolean(); // save to MultiStackReg file checkbox
		doTranslate = gd.getNextBoolean(); // whether translations are applied

		// cancel plugin if results would never be used
		if (!(saveFile || doTranslate)) {
			IJ.error("Please choose at least 'Apply translations' or file output. Plugin canceled.");
			return;
		}

		// avoid problems with invalid refSlice, adjustSlice
		if (selectedSlice > lastSlice || selectedSlice < firstSlice) {
			IJ.error(
			"Error: currently selected slice is beyond entered range. Set Roi in a slice which has to be corrected and restart Plugin.");
			return;
		}
		IJ.showStatus("Computing corrections. Please wait...");
		
		//
		// arrays for x and y correcions
		//

		correctionX = new int[stackSize];
		correctionY = new int[stackSize];

		//
		// compute corrections
		//
		
		// use firstSlice as first reference slice in previous slice mode
		if (prevSlice) imp.setSlice(firstSlice);

		// copy values from reference image to 2d array
		refImage = new double[roiWidth][roiHeight];
		for (int i = 0; i < roiWidth; i++) {
		  for (int j = 0; j < roiHeight; j++) {
			  refImage[i][j] = ip.getPixelValue(roiX + i, roiY + j);
		  }
		}

		if (prevSlice) { // prevSlice mode

			refSlice = selectedSlice;
			correctionX[0] = 0;
			correctionY[0] = 0;

			if (refSlice < lastSlice) // avoid exception when refSlice == lastSlice
			for (int slice = refSlice + 1; slice <= lastSlice; slice++) { // for every slice do...

				computeBestCorr(ip, slice, roiX, roiY);

				if (slice == refSlice + 1) { // first iteration: use correction to initialize bestXcorr, bestYcorr
					correctionX[slice-1] = bestXcorr;
					correctionY[slice-1] = bestYcorr;
				}
				else {
					correctionX[slice-1] = correctionX[slice-2] + bestXcorr; // absolute correction of slice
					correctionY[slice-1] = correctionY[slice-2] + bestYcorr; // uses correction of previous slice
				}

				roiX -= bestXcorr; // Roi adjustment: Roi moves, corrections are relative to previous slice
				roiY -= bestYcorr;

				// copy current image to refImage
				for (int i = 0; i < roiWidth; i++) {
			  		for (int j = 0; j < roiHeight; j++) {
				 	 refImage[i][j] = ip.getPixelValue(roiX + i, roiY + j);
			 		}
				}
			}

			// reset Roi (begin at refSlice again)
			roiX = (int) roiRect.getX();
			roiY = (int) roiRect.getY();
			// reset refImage
			imp.setSlice(refSlice);
			for (int i = 0; i < roiWidth; i++) {
				for (int j = 0; j < roiHeight; j++) {
			 	 refImage[i][j] = ip.getPixelValue(roiX + i, roiY + j);
				}
			}
			
			// same procedure as above in other direction:

			if (refSlice > firstSlice) // avoid exception when refSlice == firstSlice
			for (int slice = refSlice -1; slice >= firstSlice; slice--) {	// for every slice do...

				computeBestCorr(ip, slice, roiX, roiY);

				if (slice == firstSlice - 1) {
					correctionX[slice-1] = bestXcorr;
					correctionY[slice-1] = bestYcorr;
				}
				else {
					correctionX[slice-1] = correctionX[slice] + bestXcorr;
					correctionY[slice-1] = correctionY[slice] + bestYcorr;
				}

				roiX -= bestXcorr;
				roiY -= bestYcorr;

				// copy current image to refImage
				for (int i = 0; i < roiWidth; i++) {
			  		for (int j = 0; j < roiHeight; j++) {
				 	 refImage[i][j] = ip.getPixelValue(roiX + i, roiY + j);
			 		}
				}
			}

		}

		else { // compare with selected slice mode

			refSlice = selectedSlice; 
			correctionX[refSlice-1] = 0; // correction of refSlice is (0, 0)
			correctionY[refSlice-1] = 0;

			if (refSlice > 1)
			for (int slice = refSlice-1; slice >= firstSlice; slice--) { // go down starting from selected slice

				computeBestCorr(ip, slice, roiX, roiY);
				roiX -= bestXcorr;
				roiY -= bestYcorr;
				if (slice == refSlice-1) {
					correctionX[slice-1] = bestXcorr;
					correctionY[slice-1] = bestYcorr;
				}
				else {
					correctionX[slice-1] = correctionX[slice] + bestXcorr;
					correctionY[slice-1] = correctionY[slice] + bestYcorr;
				}
			}


			// reset Roi (beginning from refSlice again)
			roiX = (int) roiRect.getX();
			roiY = (int) roiRect.getY();			
		
			if (refSlice < lastSlice)
			for (int slice = refSlice+1; slice <= lastSlice; slice++) { // go up starting from selected slice

				computeBestCorr(ip, slice, roiX, roiY);
				roiX -= bestXcorr;
				roiY -= bestYcorr;
				if (slice == refSlice+1) {
					correctionX[slice-1] = bestXcorr;
					correctionY[slice-1] = bestYcorr;
				}
				else {
					correctionX[slice-1] = correctionX[slice-2] + bestXcorr;
					correctionY[slice-1] = correctionY[slice-2] + bestYcorr;
				}
			}
			
		}

		//
		// adjust to another slice than refSlice
		//

		int adjustSlice = firstSlice; // default initialization

		// use choice "Adjust to..."
		if (adjustTo.equals("last slice of range")) adjustSlice = lastSlice;
		if (adjustTo.equals("currently selected slice")) adjustSlice = selectedSlice;

		// use offset of adjustSlice
		int offsetX = correctionX[adjustSlice-1];
		int offsetY = correctionY[adjustSlice-1];
		for (int i = firstSlice; i <= lastSlice; i++) {
			correctionX[i-1] -= offsetX;
			correctionY[i-1] -= offsetY;
		}

		//
		// correct previous and correct following slices
		//

		if (correctPrevious) {
			for (int i = 1; i < firstSlice; i++) {
				correctionX[i-1] = correctionX[firstSlice-1];
				correctionY[i-1] = correctionY[firstSlice-1];
			}
		}
		if (correctFollowing) {
			for (int i = lastSlice; i <= stackSize; i++) {
				correctionX[i-1] = correctionX[lastSlice-1];
				correctionY[i-1] = correctionY[lastSlice-1];
			}
		}

		//
		// save MultiStackReg file
		//

		if (saveFile) {
			SaveDialog sd = new SaveDialog("Save MultiStackReg File...", "translations", ".txt");
			String directory = sd.getDirectory();
			String fileName = sd.getFileName();
			if (fileName == null) return;
			try {
				FileWriter fw = new FileWriter(directory + fileName);
				fw.write("MultiStackReg Transformation File\n");
				fw.write("File Version 1.0\n");
				fw.write("0\n"); // no two stack align (MultiStackReg), otherwise: 1
				
				int x0 = ip.getWidth() / 2;
				int y0 = ip.getHeight() / 2;

				int[] x = new int[stackSize];
				int[] y = new int[stackSize];

				if (refSlice > 1)
				for (int i = refSlice-1; i >= 1; i--) {
					x[i-1] = x0 - correctionX[i-1] + correctionX[i];
					y[i-1] = y0 - correctionY[i-1] + correctionY[i];
				}

				if (refSlice < lastSlice)
				for (int i = refSlice+1; i <= stackSize; i++) {
					x[i-1] = x0 - correctionX[i-1] + correctionX[i-2];
					y[i-1] = y0 - correctionY[i-1] + correctionY[i-2];	
				}
				
				if (refSlice > 1)
				for (int i = refSlice-1; i >= 1; i--) {
					fw.write("TRANSLATION\n");
					fw.write("Source img: " + i + " Target img: " + refSlice + "\n");
					fw.write(x[i-1] + "\t" + y[i-1] + "\n");
					fw.write("0.0\t0.0\n0.0\t0.0\n");
					fw.write("" + "\n");
					fw.write(x0 + "\t" + y0 + "\n");
					fw.write("0.0\t0.0\n0.0\t0.0\n");
					fw.write("" + "\n");		
				}

				if (refSlice < lastSlice)
				for (int i = refSlice+1; i <= stackSize; i++) {
					fw.write("TRANSLATION\n");
					fw.write("Source img: " + i + " Target img: " + refSlice + "\n");
					fw.write(x[i-1] + "\t" + y[i-1] + "\n");
					fw.write("0.0\t0.0\n0.0\t0.0\n");
					fw.write("" + "\n");
					fw.write(x0 + "\t" + y0 + "\n");
					fw.write("0.0\t0.0\n0.0\t0.0\n");
					fw.write("" + "\n");	
				}
	
				fw.close();

			} catch (IOException e) {IJ.showMessage("Saving MultiStackReg File failed.");}
			

		}

		//
		// apply corrections
		//

		if (doTranslate) {		
			imp.unlock();
			imp.killRoi();	
			IJ.showStatus("Translating Images...");		
		
			for (int i = 1; i <= stackSize; i++) {
				imp.setSlice(i);
				IJ.run("Translate...", "x=" + correctionX[i - 1] + " y=" + correctionY[i - 1] +
				  " interpolation=None slice");
			}
		
			IJ.showStatus("");
			imp.setRoi(roiRect);
		}

		IJ.showStatus("");
		imp.setRoi(roiRect);
		
	} catch (Exception e) {
		IJ.showMessage("Error! Check Image and Roi."); 
		IJ.showStatus("");
	}

	} // end of run method

	public double computeError(ImageProcessor ip, int xzero, int yzero) { // computes error for given translations xzero, yzero
		double errorsum = 0;
		for (int x = xzero; x < xzero + roiWidth; x++) {
			for (int y = yzero; y < yzero + roiHeight; y++) {
				double error = Math.pow(Math.abs(ip.getPixelValue(x, y) - refImage[x-xzero][y-yzero]), power);
				errorsum += error;
			}
		}
		return errorsum;
	}
	
	public void computeBestCorr(ImageProcessor ip, int slice, int roiX, int roiY) { // computes best correction for a slice
		double minerror = 0;
		bestXcorr = 0;
		bestYcorr = 0;
		imp.setSlice(slice);
		for (int xtrans =  - range; xtrans <=  range; xtrans++) { // check all possible translations
			for (int ytrans = - range; ytrans <= range; ytrans++) {
				double error = computeError(ip, roiX + xtrans, roiY + ytrans);
				if ((xtrans == - range) && (ytrans == - range)) { // true for 1st iteration
					minerror = error;
					bestXcorr = -xtrans;
					bestYcorr = -ytrans;
				}
				if  (error < minerror) { // found new min. error
					minerror = error;
					bestXcorr = -xtrans;
					bestYcorr = -ytrans;
				}
			}
		}
	}

}
