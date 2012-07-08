package com.redhat.ceylon.eclipse.core.vfs;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;

import com.redhat.ceylon.compiler.typechecker.io.VirtualFile;

public abstract class ResourceVirtualFile implements VirtualFile {

    protected IProject project;
    protected IPath path;
    protected IResource resource;

    public static ResourceVirtualFile createResourceVirtualFile(IResource resource)    
    {
        if (resource instanceof IFolder)
        {
            return new IFolderVirtualFile(resource);
        }
        
        if (resource instanceof IFile)
        {
            return new IFileVirtualFile(resource);
        }
        
        throw new RuntimeException("Bad resource for constructing the VirtualFile :" +  resource.toString());
    }
    
    // TODO Verify the everything works with project-relative paths
    public ResourceVirtualFile(IProject project, IPath path) {
        this.project = project;
        this.path = path;
        resource = createResourceFromIPath();
    }

    public ResourceVirtualFile(IResource resource) {
        this.resource = resource;
        this.project = resource.getProject();
        this.path = resource.getProjectRelativePath();
    }
    
    abstract protected IResource createResourceFromIPath();

    @Override
    public String getName() {
        return path.lastSegment().toString();
    }

    @Override
    public String getPath() {
        return project.getLocation().append(path).toString();
    }

    /**
     * @return the resource
     */
    public IResource getResource() {
        return resource;
    }

    @Override
    public int hashCode() {
        return getPath().hashCode();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof VirtualFile) {
            return ((VirtualFile) obj).getPath().equals(getPath());
        }
        else {
            return super.equals(obj);
        }
    }
}