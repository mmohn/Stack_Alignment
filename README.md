# Stack Alignment Plugins (ImageJ)
*Copyright (c) 2010 Michael Mohn and Jannik Meyer, Ulm University*

## Description

This repository contains two ImageJ plugins for the alignment/registration of image stacks:

1. The ``Prealign_Stack`` plugin can be used for a rough pre-alignment by having the user manually mark the same position in each image.
2. The ``Alignment_Roi`` plugin performs image alignment using the least-squares method within a user-specified ROI.

## Installation

Copy the .java files in a new "Stack_Alignment" subfolder in the ImageJ Plugin folder and compile them using the "Compile and Run…" function of ImageJ.
After an ImageJ restart, the plugins should be available from the "Plugins > Stack Alignment" menu.

## Usage

### Prealign_Stack

1. Open an image stack and start the "Prealign_Stack" plugin via the "Plugins > Stack Alignment" menu.
2. In the "Prealign Stack" dialog, set the following parameters:
	 - "Correct translation from slice … to slice …": Indices of the first and last slice between which translations should be applied. The indices default to the first and last slice of the stack.
	 - "Adjust to..." (dropdown menu): Whether to align all slices to the first or last slice of the above-defined range or to use the last selected slice (step 5) as a reference.
	 - "Correct previous/following slices" (checkboxes): Whether translations should also be applied to slices beyond the above-defined range. If selected, the translation of the first or last slice _within_ the range will be also applied to all preceding or subsequent images, respectively.
	 - "Save MultiStackReg File" (checkbox): If this option is enabled, a MultiStackReg-compatible transformation file containing all translations will be saved. The plugin will ask for a file path later.
	 - "Apply x / y alignment" (checkboxes): Whether the plugin should directly apply the determined translations. By selecting only the x or y alignment, it is possible to apply only the horizontal or vertical shifts, respectively.
3. Click OK in the dialog window to start marking the positions. The plugin will then always jump to the first slice. Move to the first slice where you want to mark the position and do so by clicking on the image. After a click has been registered, the plugin will always jump to the next slice.
4. Continue marking the same position in multiple images. _Note that it is always possible to skip one or more images._
5. Press the ENTER key when you are done marking the positions. The plugin will then always ask you to confirm that you are done.
6. Press OK in the dialog to confirm your selection. The plugin will now save and/or apply the translations, depending on the choices made in the first dialog window. If a MultiStackReg file is to be saved, the plugin will ask for a file path.

### Alignment_Roi

1. Open an image stack and draw a rectangular region of interest (ROI) around the image feature that should be used for the alignment. _Note that the computational effort increases with the size of the ROI._
2. \[Optional\] Navigate to the slice that should serve as a reference either for the appearance of the feature or for its position in the image. Make sure that the ROI is still centered around the feature.
3. Start the "Alignment_Roi" plugin via the "Plugins > Stack Alignment" menu.
4. In the "Alignment" dialog, set the following parameters:
	- "Range (px)": Maximum x or y translation between two subsequent slices. The plugin will only search for the optimum translation within this range.
	- "Error exponent": Exponent for the deviations of individual pixels before they are summed up. Defaults to "2.0" (least-squares method).
	- "Compare with..." (dropdown menu): Whether all slices should be compared to the same reference slice defined in the optional 2nd step ("selected slice"), or to the previous slice. In the latter case, the plugin goes through all slices in ascending order, and slice $n$ is always compared to slice $n-1$. _Note that in both modes, the position of the ROI is constantly updated to follow the feature through the stack._
	- "Correct translations from slice ... to ...": Indices of the first and last slice between which translations should be applied. The indices default to the first and last slice of the stack.
	- "Adjust to..." (dropdown menu): Whether to align all slices to the first or last slice of the above-defined range or to use the currently selected slice as a reference (see step 2).
	- "Correct previous/following slices" (checkboxes): Whether translations should also be applied to slices beyond the above-defined range. If selected, the translation of the first or last slice _within_ the range will be also applied to all preceding or subsequent images, respectively. 
	- "Save MultiStackReg File" (checkbox): If this option is enabled, a MultiStackReg-compatible transformation file containing all translations will be saved. The plugin will ask for a file path later.
    - "Apply translations" (checkboxes): Whether the plugin should directly apply the determined translations.
5. Press OK to start the alignment and wait for the plugin to finish. Depending on the above choices, the plugin might determine the translations starting from different slices and might move through the stack in different directions. If "Apply translations" was selected, the plugin will finally go through the whole stack again to actually apply the translations. With the "Save MultiStackReg File" option enabled, the plugin will also ask for a file path.
