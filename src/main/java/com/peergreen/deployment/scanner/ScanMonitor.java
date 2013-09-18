/**
 * Copyright 2013 Peergreen S.A.S.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.peergreen.deployment.scanner;

import java.io.File;
import java.io.FileFilter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.felix.ipojo.annotations.Bind;
import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Unbind;
import org.apache.felix.ipojo.annotations.Validate;

import com.peergreen.deployment.Artifact;
import com.peergreen.deployment.ArtifactBuilder;
import com.peergreen.deployment.ArtifactProcessRequest;
import com.peergreen.deployment.DeploymentMode;
import com.peergreen.deployment.DeploymentService;
import com.peergreen.deployment.monitor.URITracker;
import com.peergreen.deployment.monitor.URITrackerException;
import com.peergreen.deployment.tracker.DeploymentServiceTracker;

/**
 * Checks for files stored in directories <br />
 * This class checks for bind/unbind on requires attributes as it's using Thread
 * so fields may be null while checking them in run() method.
 *
 * @author Florent Benoit
 */
@Component
@Provides(specifications = DeploymentServiceTracker.class)
@Instantiate(name = "Directory scanner monitor")
public class ScanMonitor implements Runnable, DeploymentServiceTracker {

    /**
     * Deployment service.
     */
    private DeploymentService deploymentService;

    /**
     * Artifact builder.
     */
    private ArtifactBuilder artifactBuilder;

    /**
     * Needs the file tracker.
     */
    private URITracker fileTracker;

    /**
     * Scan interval before trying to detect new files in some directories.
     */
    private long scanInterval = 5000L;

    /**
     * Directories that will be scanned for deploying new artifacts.
     */
    private List<File> monitoredDirectories;

    /**
     * Boolean that is checked for stopping the loop.
     */
    private final AtomicBoolean stopThread = new AtomicBoolean(false);

    /**
     * List of Files that we've already sent to the deployer service.
     */
    private final List<File> trackedByDeploymentService;

    /**
     * Map between a File and its last length known.
     */
    private Map<File, Long> fileLengths = null;

    private FileFilter fileFilter;

    /**
     * Group that will contain the Thread created to run this instance.
     */
    private ThreadGroup threadGroup;

    public ScanMonitor() {
        this.monitoredDirectories = new LinkedList<File>();
        this.trackedByDeploymentService = new ArrayList<File>();
        this.fileLengths = new HashMap<File, Long>();
    }


    @Validate
    public void startComponent() {
        // reset flag
        this.stopThread.set(false);

        // Start the thread
        Thread thread;
        if (threadGroup != null) {
            thread = new Thread(threadGroup, this);
        } else {
            thread = new Thread(this);
        }
        thread.setName("Peergreen Directories Scanner");
        thread.setDaemon(true);
        thread.start();
    }

    @Invalidate
    public void stopComponent() {
        this.stopThread.set(true);
    }

    /**
     * Start the thread of this class. <br>
     * It will search and deploy files to deploy.<br>
     * In development mode, it will check the changes.
     */
    @Override
    public void run() {

        // Add default directory
        if (monitoredDirectories.isEmpty()) {
            File deployDirectory = new File(System.getProperty("user.dir"), "deploy");
            if (!deployDirectory.exists()) {
                deployDirectory.mkdirs();
            }
            monitoredDirectories.add(deployDirectory);
        }

        for (; ; ) {
            if (stopThread.get()) {
                // Stop the thread
                return;
            }

            // Save file lengths in order to avoid deploying file for which copy is in process
            saveFileLengths();

            // 20% of the monitor interval is spent to wait in order to guarantee the file integrity
            // If the file is being copied, the file size increases in the time
            try {
                Thread.sleep((long) (scanInterval * 0.2));
            } catch (InterruptedException e) {
                throw new RuntimeException("Thread fail to sleep");
            }

            // Check new archives/containers to start
            detectNewArchives();

            // 80% is for waiting
            try {
                Thread.sleep((long) (scanInterval * 0.8));
            } catch (InterruptedException e) {
                throw new RuntimeException("Thread fail to sleep");
            }
        }
    }

    /**
     * Save file lengths in scanned directories.
     */
    private void saveFileLengths() {
        // Clear Map
        fileLengths.clear();

        for (File deployDirectory : monitoredDirectories) {
            // Get files
            File[] files = deployDirectory.listFiles(fileFilter);

            // Next directory if there are no files to scan.
            if (files == null) {
                continue;
            }

            for (File file : files) {
                try {
                    fileLengths.put(file, getFileSize(file));
                } catch (Exception e) {
                    // File cannot be read
                    // or a sub file is not completed (in case of a directory)
                }
            }
        }
    }


    /**
     * Return the file or directory size.
     *
     * @param file a file or directory
     * @return the total file size (files in folders included)
     * @throws URITrackerException if the
     */
    protected long getFileSize(final File file) throws URITrackerException {
        if (fileTracker == null) {
            throw new URITrackerException("No file tracker available");
        }
        return fileTracker.getLength(file.toURI());
    }

    /**
     * Scan all files present in the monitored directories and deploy them. (if not
     * yet tracked).
     */
    protected void detectNewArchives() {

        for (File deployDirectory : monitoredDirectories) {
            // get files
            File[] files = deployDirectory.listFiles(fileFilter);

            // next directory if there are no files to scan.
            if (files == null) {
                continue;
            }

            // Sort the files by names
            Arrays.sort(files, new AlphabeticalOrder());

            List<File> filesToDeploy = new ArrayList<>();

            // analyze each file to detect new modules that are not yet deployed.
            for (File file : files) {

                // Already tracked ?
                if (trackedByDeploymentService.contains(file)) {
                    // yes, then check other files
                    continue;
                }

                // File doesn't exist (for symlink)
                if (!file.exists()) {
                    continue;
                }

                // File length has changed: maybe a file copy that is not yet completed.
                if (fileLengthHasChanged(file)) {
                    continue;
                }

                // Add the file to files that needs to be deployed
                filesToDeploy.add(file);
            }

            if (stopThread.get()) {
                // Don't deploy new archives after the reception of a stop order
                return;
            }

            // Build a list of artifacts that we will send to the service
            List<ArtifactProcessRequest> artifactProcessRequests = new ArrayList<>();
            for (File f : filesToDeploy) {
                // no builder, continue
                if (artifactBuilder == null) {
                    continue;
                }
                // Build artifact
                Artifact artifact = artifactBuilder.build(f.getName(), f.toURI());
                ArtifactProcessRequest artifactProcessRequest = new ArtifactProcessRequest(artifact);
                artifactProcessRequest.setDeploymentMode(DeploymentMode.DEPLOY);
                artifactProcessRequests.add(artifactProcessRequest);
            }

            // Now process these files
            process(artifactProcessRequests);

        }
    }


    /**
     * Process the given list of artifacts.
     */
    protected void process(List<ArtifactProcessRequest> artifactProcessRequests) {
        if (deploymentService == null) {
            return;
        }

        if (!artifactProcessRequests.isEmpty()) {
            deploymentService.process(artifactProcessRequests);

            // this is now tracked by the deployment service
            for (ArtifactProcessRequest artifactProcessRequest : artifactProcessRequests) {
                File f = new File(artifactProcessRequest.getArtifact().uri().getPath());
                this.trackedByDeploymentService.add(f);
            }

        }
    }

    /**
     * Check if the file length has changed.
     *
     * @param file The given file
     * @return True if the file length has changed
     */
    private boolean fileLengthHasChanged(final File file) {
        // File length not known
        if (!fileLengths.containsKey(file)) {
            return true;
        }

        long storedFileLength = fileLengths.get(file);
        long currentFileLength = 0;
        try {
            currentFileLength = getFileSize(file);
        } catch (Exception e) {
            // File cannot be checked, probably the file is being written.
            return true;
        }
        if (storedFileLength != currentFileLength) {
            // File length has changed
            return true;
        }

        // No change in file length
        return false;
    }

    /**
     * @return the list of directories that are tracked.
     */
    public List<File> getMonitoredDirectories() {
        return monitoredDirectories;
    }

    /**
     * Sets the list of directories that are tracked.
     *
     * @param directories list of directories that are tracked.
     */
    public void setMonitoredDirectories(final List<File> monitoredDirectories) {
        this.monitoredDirectories = monitoredDirectories;
    }

    /**
     * Add a directory to the list of directories that are tracked.
     *
     * @param directory the directory to add
     */
    public void addMonitoredDirectory(final File monitoredDirectory) {
        monitoredDirectories.add(monitoredDirectory);
    }

    /**
     * Remove a directory to the list of directories to monitor.
     *
     * @param directory the directory to remove
     */
    public void removeDirectory(final File monitoredDirectory) {
        monitoredDirectories.remove(monitoredDirectory);
    }

    /**
     * Set the scan interval between each directory scan.
     *
     * @param scanInterval value to set
     */
    public void setScanInterval(final int scanInterval) {
        this.scanInterval = scanInterval;
    }


    @Override
    public void onChange(Artifact artifact, DeploymentMode deploymentMode) {
        // Only UNDEPLOY notification
        if (deploymentMode != DeploymentMode.UNDEPLOY) {
            return;
        }

        // Get file being undeployed
        URI uri = artifact.uri();
        if (!"file".equals(uri.getScheme())) {
            return;
        }

        File artifactFile = new File(uri.getPath());

        // If being tracked, remove it only if file is no longer here
        if (trackedByDeploymentService.contains(artifactFile) && !artifactFile.exists()) {
            trackedByDeploymentService.remove(artifactFile);
        }
    }

    @Bind(filter = "(scheme=file)")
    public void bindURITracker(URITracker fileTracker) {
        this.fileTracker = fileTracker;
    }

    @Unbind
    public void unbindURITracker(URITracker fileTracker) {
        this.fileTracker = null;
    }

    @Bind
    public void bindArtifactBuilder(ArtifactBuilder artifactBuilder) {
        this.artifactBuilder = artifactBuilder;
    }

    @Unbind
    public void unbindArtifactBuilder(ArtifactBuilder artifactBuilder) {
        this.artifactBuilder = null;
    }

    @Bind
    public void bindDeploymentService(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @Unbind
    public void unbindDeploymentService(DeploymentService deploymentService) {
        this.deploymentService = null;
    }

    @Bind(filter = "(group.name=peergreen)")
    public void bindThreadGroup(ThreadGroup threadGroup) {
        this.threadGroup = threadGroup;
    }

}
