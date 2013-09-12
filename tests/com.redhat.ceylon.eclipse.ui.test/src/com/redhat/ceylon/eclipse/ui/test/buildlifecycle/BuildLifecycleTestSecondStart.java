package com.redhat.ceylon.eclipse.ui.test.buildlifecycle;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

import junit.framework.Assert;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.swtbot.eclipse.finder.SWTWorkbenchBot;
import org.eclipse.swtbot.swt.finder.junit.SWTBotJunit4ClassRunner;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite.SuiteClasses;

import com.redhat.ceylon.eclipse.ui.test.AbstractMultiProjectTest;
import com.redhat.ceylon.eclipse.ui.test.Utils;
import com.redhat.ceylon.eclipse.ui.test.Utils.CeylonBuildSummary;

@RunWith(SWTBotJunit4ClassRunner.class)
public class BuildLifecycleTestSecondStart extends AbstractMultiProjectTest {

    private static SWTWorkbenchBot  bot;
    
    @BeforeClass
    public static void beforeClass() {
        bot = Utils.createBot();

        String testRunWronglyError = ""
                + "This test should be started :\n"
                + "  - as a SWTBot test,\n"
                + "  - just after the '" + BuildLifecycleTestFirstStart.class.getSimpleName() + "' test,"
                + "  - and WITHOUT removing the testing workspace OSGI data";
        
        IWorkspaceDescription description = workspace.getDescription();
        if (description.isAutoBuilding() || workspace.getRoot().getProjects().length < 3) {
            throw new RuntimeException(testRunWronglyError);
        }
        
        if (! (referencedJavaProject = workspace.getRoot().getProject(referencedJavaProjectName)).exists()) {
            throw new RuntimeException(testRunWronglyError);
        }
        if (! (referencedCeylonProject = workspace.getRoot().getProject(referencedCeylonProjectName)).exists()) {
            throw new RuntimeException(testRunWronglyError);
        }
        if (! (mainProject = workspace.getRoot().getProject(mainProjectName)).exists()) {
            throw new RuntimeException(testRunWronglyError);
        }
        
        
        referencedJavaProjectJDT = JavaCore.create(referencedJavaProject);
        referencedCeylonProjectJDT = JavaCore.create(referencedCeylonProject);
        mainProjectJDT = JavaCore.create(mainProject);
        
    }


    public static String[] filesTouchedBeforeRestart = {
        referencedCeylonProjectName + "/src/referencedCeylonProject/CeylonDeclarations_Referenced_Ceylon_Project.ceylon",        
        mainProjectName + "/src/usedModule/CeylonDeclarations_Main_Ceylon_Project.ceylon"
    };

    public static String[] filesToRecompileAtRestart = {
        referencedCeylonProjectName + "/src/referencedCeylonProject/CeylonDeclarations_Referenced_Ceylon_Project.ceylon",        
        mainProjectName + "/src/usedModule/CeylonDeclarations_Main_Ceylon_Project.ceylon",
        mainProjectName + "/src/mainModule/run.ceylon"
    };
    
    public static Collection<IFile> getFilesTouchedBeforeRestart() {
        return toIFileList(filesTouchedBeforeRestart);
    }
    
    public static Collection<IFile> getFilesToRecompileAtRestart() {
        return toIFileList(filesToRecompileAtRestart);
    }
    
    private static Collection<IFile> toIFileList(String[] names) {
        ArrayList<IFile> files = new ArrayList<>();
        for (String name : names) {
            files.add(workspace.getRoot().getFile(new Path(name)));
        }
        return files;
    }
    
    @Test
    public void importShouldTriggerFullBuild() throws InterruptedException, CoreException {
        
        IPath projectDescriptionPath = null;
        IPath userDirPath = new Path(System.getProperty("user.dir"));

        IProject project = workspace.getRoot().getProject(mainProjectName);
        CeylonBuildSummary buildSummary = new CeylonBuildSummary(project);
        buildSummary.install();
        
        // Now set autoBuild back to true so that it should only compile the files that have 
        // been touched before restart
        IWorkspaceDescription description = workspace.getDescription();
        assertFalse("AutoBuilding should be false due to the FirstStart test :\n"
                + "This test should always be started just after " + BuildLifecycleTestFirstStart.class.getSimpleName() + " and WITHOUT removing workspace OSGI data", description.isAutoBuilding());
        description.setAutoBuilding(true);
        workspace.setDescription(description);
        
        if (!buildSummary.waitForBuildEnd(120)) {
            fail("No build has been automatically started after restart");
        }
        
        assertThat("The referenced Java project build should not have any error",
                Utils.getProjectErrorMarkers(referencedJavaProject),
                Matchers.empty());
        assertThat("The referenced Ceylon project build should not have any error",
                Utils.getProjectErrorMarkers(referencedJavaProject),
                Matchers.empty());
        assertThat("The main project build should not have any error",
                Utils.getProjectErrorMarkers(mainProject),
                Matchers.empty());
        
        assertTrue("It should have done an incremental build after restart",
                buildSummary.didIncrementalBuild());
        assertEquals("It should have build the referenced projects first", 
                1, 
                buildSummary.getPreviousBuilds().size());
        assertTrue("It should have done a full typecheck during the restart incremental build", 
                buildSummary.didFullTypeCheckDuringIncrementalBuild());

        assertThat("The restart incremental build didn't see all the file changes",
                buildSummary.getIncrementalBuildChangedSources(), 
                Matchers.containsInAnyOrder(getFilesTouchedBeforeRestart().toArray(new IFile[] {})));
        assertThat("The restart incremental build missed some impacted files", 
                buildSummary.getIncrementalBuildSourcesToCompile(), 
                Matchers.containsInAnyOrder(getFilesToRecompileAtRestart().toArray(new IFile[] {})));
    }
}
