package com.peergreen.deployment.scanner;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.apache.felix.ipojo.annotations.Validate;

import com.peergreen.deployment.Artifact;
import com.peergreen.deployment.ArtifactBuilder;
import com.peergreen.deployment.DeploymentMode;
import com.peergreen.deployment.DeploymentService;
import com.peergreen.deployment.report.DeploymentStatusReport;

@Component
@Provides
@Instantiate(name="Scan monitor")
public class ScanMonitor {

    @Requires
    private DeploymentService deploymentService;

    @Requires
    private ArtifactBuilder artifactBuilder;



    protected Artifact getArtifact(String name)  {
        URL url = ScanMonitor.class.getResource("/" + name);
        try {
            return artifactBuilder.build(name, url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URI");
        }
    }


    protected List<Artifact> getArtifacts() {
        File bundlesDir  = new File(System.getProperty("user.dir"), "deployment-system");
        if (!bundlesDir.exists()) {
            bundlesDir = new File("/Users/benoitf/Documents/workspace/deployment/resources/bundles/");
        }

        try {
            System.out.println("Will deploy all modules available in " + bundlesDir.getCanonicalPath());
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        List<Artifact> artifacts = new ArrayList<Artifact>();
        process(bundlesDir, artifacts);
        return artifacts;
    }


    protected void process(File directory, List<Artifact> artifacts)  {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile() && (child.getPath().endsWith(".jar") || child.getPath().endsWith(".xml") || child.getPath().endsWith(".properties"))) {
                    artifacts.add(artifactBuilder.build(child.getName(), child.toURI()));
                }
                if (child.isDirectory()) {
                    process(child, artifacts);
                }
            }
        }


    }

    @Validate
    public void startComponent() {
        Runnable runnable = new Runnable() {

            @Override
            public void run() {
                // TODO Auto-generated method stub
                start();
            }
        };

        System.out.println("before RUN");
        new Thread(runnable).start();
        System.out.println("RUN done");

    }


    public void start() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        System.out.println("Launching deployment...");


        // artifacts
        List<Artifact> artifacts = new ArrayList<Artifact>();

        // build artifact
       // artifacts.add(getArtifact("d.txt"));
        //artifacts.add(getArtifact("plan1.xml"));

      // artifacts.add(getArtifact("bundle1.jar"));
        //artifacts.add(getArtifact("bundle2.jar"));


        //artifacts.add(getArtifact("plan2.xml"));
        //artifacts.add(getArtifact("subplan.xml"));

        artifacts = getArtifacts();

        // deploy
        DeploymentStatusReport deploymentStatusReport = deploymentService.process(artifacts, DeploymentMode.DEPLOY);
        System.out.println("Report will be printed in 5 second");
        try {
            Thread.sleep(5000L);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        System.out.println("Report : " + deploymentStatusReport);



        // Sleep
        try {
            Thread.sleep(10000L);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        //System.out.println("Undeploying....");

        //undeploy
        //deploymentService.process(artifacts, DeploymentMode.UNDEPLOY);


        // Sleep
        /*try {
            Thread.sleep(2000L);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        */
    }

}
