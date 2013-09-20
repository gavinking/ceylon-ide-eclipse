package com.redhat.ceylon.eclipse.core.classpath;

import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.isModelTypeChecked;
import static org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.internal.debug.ui.actions.ForceReturnAction;

public class BuildProjectAfterClasspathChangeJob extends Job {
	protected final IProject project;
    private boolean buildReferencedProjects;
    private boolean buildReferencingProjects;
    private boolean forceRebuild;

	public BuildProjectAfterClasspathChangeJob(String name, IProject project, boolean buildReferencedProjects, boolean buildReferencingProjects, boolean forceRebuild) {
		super(name);
		this.project = project;
		this.buildReferencedProjects = buildReferencedProjects;
        this.buildReferencingProjects = buildReferencingProjects;
        this.forceRebuild = forceRebuild;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		if (forceRebuild || ! isModelTypeChecked(project)) {
			try {
				List<IBuildConfiguration> configs = new ArrayList<IBuildConfiguration>();
				configs.add(project.getBuildConfig(IBuildConfiguration.DEFAULT_CONFIG_NAME));
				if (buildReferencingProjects) {
	                for (IProject p: project.getReferencingProjects()) {
	                    if (p.isOpen()) {
	                        configs.add(p.getBuildConfig(IBuildConfiguration.DEFAULT_CONFIG_NAME));
	                    }
	                }
				}
				project.getWorkspace().build(configs.toArray(new IBuildConfiguration[1]), 
						INCREMENTAL_BUILD, buildReferencedProjects, monitor);    	            			
			}
			catch (CoreException e) {
				e.printStackTrace();
			}
		}
		else {
		    // System.out.println("Don't build the project " + project + " since it's already typechecked");
		}
		return Status.OK_STATUS;
	}

	protected boolean reallyRun() {
		return true;
	}

    @Override
    public boolean shouldRun() {
        if (forceRebuild || ! isModelTypeChecked(project)) {
            return true;
        } else {
            // System.out.println("Don't build the project " + project + " since it's already typechecked");
            return false;
        }
    }

    @Override
    public boolean shouldSchedule() {
        if (forceRebuild || ! isModelTypeChecked(project)) {
            return true;
        } else {
            // System.out.println("Don't build the project " + project + " since it's already typechecked");
            return false;
        }
    }
}