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
import ij.process.*;
import ij.gui.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import ij.plugin.filter.*;

public class Prealign_Stack implements PlugInFilter, MouseListener, KeyListener, ImageListener {

	ImagePlus imp;
	String originalTitle; // original image title, title is changed while plugin is running
	ImageCanvas imCanvas;
	ImageWindow win;

	int stackSize; // will contain number of slices
	int currentSlice; // the slice which is active when plugin is finished

	// arrays: size will be stackSize
	Point[] clickPoint; // a Point for every slice
	Point[] correction; // is computed out of clickPoints 
	boolean[] clicked; // whether there are clickPoints for slices

	// plugin options
	int firstSlice; // first slice that has to be corrected
	int lastSlice; // last slice that has to be corrected
	int refSlice; // slice with correction (0, 0)
	boolean correctHead; // true if slices < firstSlice should be translated like firstSlice
	boolean correctTail; // true if slices > lastSlice should be translated like lastSlice
	boolean saveToFile; // save to MultiStackReg file
	boolean alignX; // whether x ...
	boolean alignY; // ... and y alignment should be applied
	int firstClicked; // first slice with clickPoint
	int lastClicked; // last slice with clickPoint
	
	public int setup(String arg, ImagePlus imp) {
		this.imp = imp;
		return DOES_ALL;
	}
	  
	public void run(ImageProcessor ip) {

		// check for other running alignmnent plugin, only one alignment plugin is allowed
		if (imp.getTitle().endsWith("- Prealign Plugin running ...")) {
			IJ.showMessage("Another Prealign Stack Plugin is already running.\nEnd it by pressing ESC when image window is focused.");
			return;
		}
		originalTitle = imp.getTitle();
		imp.setTitle(imp.getTitle() + "- Prealign Plugin running ...");
		
		// get image stack
		ImageStack stack = imp.getStack();
		stackSize = stack.getSize(); // number of slices
		imp.killRoi(); // may avoid errors
		win = imp.getWindow(); // necessary to get canvas
		imCanvas = win.getCanvas(); // canvas for mouse click listener
		
		// initialize arrays
		// indices are 0...stackSize-1 while stack indices are 1...stackSize
		clickPoint = new Point[stackSize];
		correction = new Point[stackSize];
		clicked = new boolean[stackSize];
		for (int i = 0; i < stackSize; i++) {
			clicked[i] = false;
		}
	
		
		// assign mouse and key listener
		imCanvas.addMouseListener(this); // detect mouse on canvas
		imCanvas.addKeyListener(this); // keys are used to end plugin
		win.addKeyListener(this);
		
		//
		// GenericDialog for plugin options
		//

		GenericDialog gd = new GenericDialog("Prealign Stack");

		// range
		gd.addNumericField("Correct translation from slice", 1, 0);
		gd.addNumericField("to slice", stackSize, 0);

		// adjust to... option
		String[] choices = {"first slice of range", "last slice of range", "currently selected slice while finishing Plugin"};
		gd.addChoice("Adjust to...", choices, "first slice of range");

		// correct head / tail options
		gd.addCheckbox("Correct previous slices", false);
		gd.addCheckbox("Correct following slices", false);

		// save MultiStackReg File and Apply Alignment options
		gd.addCheckbox("Save MultiStackReg File", false);
		gd.addCheckbox("Apply x alignment", true);
		gd.addCheckbox("Apply y alignment", true);

		// Message at the bottom of the dialog
		gd.addMessage("Click OK to start Plugin. ESC cancels Plugin.\n" + 
				"Mark positions at least on one slice and finish by pressing ENTER.");

		// show dialog
		gd.showDialog();
		if (gd.wasCanceled()) {
			IJ.error("Prealign Stack", "Plugin canceled!");
			imp.setTitle(originalTitle);
			return;
		}

		// get parameters
		firstSlice = (int) gd.getNextNumber(); // range start
		lastSlice = (int) gd.getNextNumber(); // range end
		String reference = gd.getNextChoice(); // adjust to...
		correctHead = gd.getNextBoolean(); // correct previous slices
		correctTail = gd.getNextBoolean(); // correct following slices
		saveToFile = gd.getNextBoolean(); // save MultiStackReg file
		alignX = gd.getNextBoolean();
		alignY = gd.getNextBoolean();

		//
		// correct some errors in user input
		//

		if (firstSlice > lastSlice) { // swap first and last slice: firstSlice < lastSlice
			IJ.showMessage("Hint", "Range of slices is invalid. Range will be turned the other way round.");
			int temp;
			temp = firstSlice;
			firstSlice = lastSlice;
			lastSlice = temp;
		}
		
		if (firstSlice < 1) { // if "firstSlice" is invalid 
			firstSlice = 1;
			IJ.showMessage("Hint", "Range of slices is invalid. Beginning of range is corrected to first slice.");
		}
		if (lastSlice > stackSize) { // if "lastSlice" is invalid
			lastSlice = stackSize;
			IJ.showMessage("Hint", "Range of slices is invalid. End of range is corrected to last possible slice.");
		}
		
		// get slice for which the correction should be (0, 0): refSlice
		switch (reference.charAt(0)) { // checks only first char of choice
		  case 'f': {refSlice = firstSlice; break;}
		  case 'l': {refSlice = lastSlice; break;}
		  case 'c': {refSlice = 0;} // 0 will later be replaced by currently selected slice
		}
		
		firstClicked = stackSize; // appropriate initialization: firstSlice will be <= lastSlice if any slice is clicked
		lastClicked = 1; // appropriate initialization: same principle as above

		imp.setSlice(firstSlice); // start with firstSlice
	}

	// handle MouseEvents
	public void mouseClicked(MouseEvent e) { // getting x and y position of MouseClick
		
		// mouse coordinates on the screen
		int x = e.getX();
		int y = e.getY();

		// coordinates relative to the image corner
		int xPos = imCanvas.offScreenX(x);
		int yPos = imCanvas.offScreenY(y);
		// IJ.showMessage(xPos + ", " + yPos); // uncomment to display every ClickPoint

		// save clickPoint
		clickPoint[imp.getSlice()-1] = new Point(xPos, yPos);
		clicked[imp.getSlice() - 1] = true;
		if (imp.getSlice() < firstClicked) firstClicked = imp.getSlice(); // adjust first / clicked Slice
		if (imp.getSlice() > lastClicked) lastClicked = imp.getSlice();

		// go to next slice
		imp.setSlice(imp.getSlice()+1); // go to next slice

	}
	
	// handle KeyEvents
	public void keyTyped(KeyEvent e) {

		if (e.getKeyChar() == 10) { // the ENTER key is pressed
		
			currentSlice = imp.getSlice(); // for 'adjust to currently selected slice' option

			// show second dialog
			GenericDialog infoGd = new GenericDialog("Prealign Stack");
			
			if (firstClicked <= lastClicked) { // if firstClicked / lastClicked have been modified
				// (see initialization of firstClicked / lastClicked above)
				infoGd.addMessage("Slices " + firstSlice + " to " + lastSlice + " will be corrected.");	
				infoGd.addMessage("Click 'OK' to apply changes now or return by clicking 'Cancel'.");
			} else infoGd.addMessage("No slice has been clicked yet.");
			
			// show dialog, apply changes if user clicked "OK"
			infoGd.showDialog();
			if (!infoGd.wasCanceled() && firstClicked <= lastClicked) {
				imCanvas.removeMouseListener(this); // stop MouseListener
				imCanvas.removeKeyListener(this); // stop KeyListener
				win.removeKeyListener(this);
				imp.setTitle(originalTitle); // restore image title
				// if refSlice should be currentSlice, refSlice == 0
				if (refSlice == 0) refSlice = currentSlice;
				applyChanges();
			}
		}
		
		if (e.getKeyChar() == 27) { // end plugin if ESC key is typed
				imCanvas.removeMouseListener(this);
				imCanvas.removeKeyListener(this);
				win.removeKeyListener(this);
				imp.setTitle(originalTitle);
		}
	}
	
	public void imageClosed(ImagePlus imp2) { // end plugin if Image is closed
		imCanvas.removeMouseListener(this);
		imCanvas.removeKeyListener(this);
		win.removeKeyListener(this);
		imp.setTitle(originalTitle);	
	}
	  
	public void applyChanges() {

		// interpolation for every slice within range [firstClicked:lastClicked] without mouseClick
		for (int i = firstClicked; i <= lastClicked; i++) {
			if (!clicked[i-1]) {

				// search for next clicked slice before slice i
				int leftInterpolSlice = i - 1;
				while (!clicked[leftInterpolSlice-1]) leftInterpolSlice--;
				
				// search for next clicked slice after slice i
				int rightInterpolSlice = i + 1;
				while (!clicked[rightInterpolSlice-1]) rightInterpolSlice++;

				// get coordinate of next mouseClicks
				int leftX = (int) clickPoint[leftInterpolSlice-1].getX();
				int leftY = (int) clickPoint[leftInterpolSlice-1].getY();
				int rightX = (int) clickPoint[rightInterpolSlice-1].getX();
				int rightY = (int) clickPoint[rightInterpolSlice-1].getY();

				// interpolation
				int x = (int) (leftX + (i - leftInterpolSlice) * (rightX - leftX) / (rightInterpolSlice - leftInterpolSlice));
				int y = (int) (leftY + (i - leftInterpolSlice) * (rightY - leftY) / (rightInterpolSlice - leftInterpolSlice));

				clickPoint[i-1] = new Point(x, y);
			}
		}
		
		// take next possible slice as reference if there's no information about refSlice
		if (refSlice < firstClicked) {
			clickPoint[refSlice-1] = clickPoint[firstClicked-1]; // take information from firstSlice instead
		}
		if (refSlice > lastClicked) {
			clickPoint[refSlice-1] = clickPoint[lastClicked-1]; // take information from lastSlice instead
		}

		// adjust previous and following slices inside range using information of first / last clicked slice
		for (int i = firstSlice; i < firstClicked; i++) { // slices at the beginning of the range
			clickPoint[i-1] = clickPoint[firstClicked-1];
		}
		for (int i = lastSlice; i > lastClicked; i--) { // slices at the end of the range
			clickPoint[i-1] = clickPoint[lastClicked-1];
		}

		// use clickPoints to adjust all corrections to refSlice
		getCorrections();

		// adjust slices < firstSlice if correctHead == true
		if (correctHead) {
			for (int i = 1; i < firstSlice; i++) {
				correction[i-1] = correction[firstSlice-1];
			}
		} else {
			for (int i = 1; i < firstSlice; i++) { // make sure there will be no correction
				correction[i-1] = correction[refSlice-1];
			}
		}

		// adjust slices > lastSlice if correctTail == true
		if (correctTail) { // for 'correct following slices' option
			for (int i = lastSlice + 1; i <= stackSize; i++) {
				correction[i-1] = correction[lastSlice-1];
			}
		} else {
			for (int i = lastSlice + 1; i <= stackSize; i++) { // make sure there will be no correction
				correction[i-1] = correction[refSlice-1];
			}
		}
		
		//
		// save MultiStackReg file
		//

		if (saveToFile) {
			SaveDialog sd = new SaveDialog("Save MultiStackReg File...", "translations", ".txt");
			String directory = sd.getDirectory();
			String fileName = sd.getFileName();
			if (fileName == null) return;
			try {
				FileWriter fw = new FileWriter(directory + fileName);
				fw.write("MultiStackReg Transformation File\n");
				fw.write("File Version 1.0\n");
				fw.write("0\n"); // no two stack align (MultiStackReg), otherwise: 1
				
				int x0 = imp.getWidth() / 2;
				int y0 = imp.getHeight() / 2;
				int[] x = new int[stackSize];
				int[] y = new int[stackSize];

				if (refSlice > 1)
				for (int i = refSlice-1; i >= 1; i--) {
					x[i-1] = (int) (x0 - correction[i-1].getX() + correction[i].getX());
					y[i-1] = (int) (y0 - correction[i-1].getY() + correction[i].getY());
				}

				if (refSlice < lastSlice)
				for (int i = refSlice+1; i <= stackSize; i++) {
					x[i-1] = (int) (x0 - correction[i-1].getX() + correction[i-2].getX());
					y[i-1] = (int) (y0 - correction[i-1].getY() + correction[i-2].getY());	
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
			} catch (IOException ioe) {IJ.showMessage("Saving MultiStackReg File failed.");}

		}
		
		//
		// apply alignment
		//

		if (alignX || alignY) { // if either x or y should be aligned
			translate(alignX, alignY);
		}

	}
	
	public void getCorrections() { // clickPoints & refPoint -> corrections
		Point refPoint = clickPoint[refSlice-1]; // correction of refSlice has to be (0, 0)
		for (int i = 1; i <= stackSize; i++) { // correct offset
			int x =  (int) (refPoint.getX() - clickPoint[i-1].getX());
			int y =  (int) (refPoint.getY() - clickPoint[i-1].getY());
			correction[i-1] = new Point(x, y);
		}
	}
	
	public void translate(boolean doX, boolean doY) { // applies corrections using ImageJ's Translate function

		int slicetmp = imp.getSlice(); // save selected slice to restore selection afterwards

		for (int i = 1; i <= stackSize; i++) {

			imp.setSlice(i);

			if (doX && doY)
			  IJ.run("Translate...", "x=" + correction[i-1].getX() + " y=" + correction[i-1].getY() + " interpolation=None slice");
			if (doX && !doY)
			  IJ.run("Translate...", "x=" + correction[i-1].getX() + " y=" + 0 + " interpolation=None slice");
			if (!doX && doY)
			  IJ.run("Translate...", "x=" + 0 + " y=" + correction[i-1].getY() + " interpolation=None slice");
		}

		imp.setSlice(slicetmp); // restore selection

	}
	  
	// methods inherited from mouse, key and image listener
	public void mousePressed(MouseEvent e) {}
	public void mouseReleased(MouseEvent e) {}
	public void mouseEntered(MouseEvent e) {}
	public void mouseExited(MouseEvent e) {}
	public void keyPressed(KeyEvent e) {}
	public void keyReleased(KeyEvent e) {}
	public void imageOpened(ImagePlus imp2) {}
	public void imageUpdated(ImagePlus imp2) {}

}
