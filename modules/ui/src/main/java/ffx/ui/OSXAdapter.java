/**
 * Title: Force Field X.
 * <p>
 * Description: Force Field X - Software for Molecular Biophysics.
 * <p>
 * Copyright: Copyright (c) Michael J. Schnieders 2001-2019.
 * <p>
 * This file is part of Force Field X.
 * <p>
 * Force Field X is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 * <p>
 * Force Field X is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Force Field X; if not, write to the Free Software Foundation, Inc., 59 Temple
 * Place, Suite 330, Boston, MA 02111-1307 USA
 * <p>
 * Linking this library statically or dynamically with other modules is making a
 * combined work based on this library. Thus, the terms and conditions of the
 * GNU General Public License cover the whole combination.
 * <p>
 * As a special exception, the copyright holders of this library give you
 * permission to link this library with independent modules to produce an
 * executable, regardless of the license terms of these independent modules, and
 * to copy and distribute the resulting executable under terms of your choice,
 * provided that you also meet, for each linked independent module, the terms
 * and conditions of the license of that module. An independent module is a
 * module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but
 * you are not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
package ffx.ui;

import java.io.File;

import com.apple.mrj.MRJAboutHandler;
import com.apple.mrj.MRJApplicationUtils;
import com.apple.mrj.MRJOpenDocumentHandler;
import com.apple.mrj.MRJPrefsHandler;
import com.apple.mrj.MRJQuitHandler;

import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * The OSXAdapter class was developed by following an example supplied on the OS
 * X site. It handles events generated by the following standard OS X toolbar
 * items: About, Preferences, Quit and File Associations
 *
 * @author Michael J. Schnieders
 */
@SuppressWarnings("deprecation")
public class OSXAdapter implements MRJAboutHandler, MRJOpenDocumentHandler,
        MRJQuitHandler, MRJPrefsHandler {

    private final MainPanel mainPanel;

    public OSXAdapter(MainPanel m) {
        mainPanel = m;
        MRJApplicationUtils.registerAboutHandler(this);
        MRJApplicationUtils.registerOpenDocumentHandler(this);
        MRJApplicationUtils.registerPrefsHandler(this);
        MRJApplicationUtils.registerQuitHandler(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return new ToStringBuilder(this).toString();
    }


    @Override
    public void handleAbout() {
        if (mainPanel != null) {
            mainPanel.about();
        }
    }

    @Override
    public void handleOpenFile(File file) {
        if (file == null) {
            return;
        }
        if (mainPanel != null) {
            mainPanel.open(file.getAbsolutePath());
        }
    }

    @Override
    public void handleQuit() {
        if (mainPanel != null) {
            mainPanel.exit();
        } else {
            System.exit(-1);
        }
    }

    @Override
    public void handlePrefs() {
        if (mainPanel != null) {
            mainPanel.getGraphics3D().preferences();
        }
    }

    /**
     * Set Mac OS X Systems Properties to promote native integration. How soon
     * do these need to be set to be recognized?
     */
    public static void setOSXProperties() {

        // Apple MRJ Flags
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Force Field X");
        System.setProperty("com.apple.mrj.application.growbox.intrudes", "false");
        System.setProperty("com.apple.mrj.application.live-resize", "true");

        // Apple OS X Flags
        System.setProperty("com.apple.macos.smallTabs", "true");
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        // Apple AWT flags.
        System.setProperty("apple.awt.brushMetalLook", "true");
        System.setProperty("apple.awt.graphics.EnableQ2DX", "true");
        System.setProperty("apple.awt.showGrowBox", "true");
        System.setProperty("apple.awt.textantialiasing", "true");

    }
}
