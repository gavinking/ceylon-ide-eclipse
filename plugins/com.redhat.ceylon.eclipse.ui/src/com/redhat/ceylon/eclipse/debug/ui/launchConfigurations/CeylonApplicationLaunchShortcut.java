package com.redhat.ceylon.eclipse.debug.ui.launchConfigurations;

import static com.redhat.ceylon.eclipse.imp.outline.CeylonLabelProvider.getPackageLabel;
import static com.redhat.ceylon.eclipse.imp.proposals.CeylonContentProposer.getDescriptionFor;
import static com.redhat.ceylon.eclipse.imp.proposals.CeylonContentProposer.getStyledDescriptionFor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.ILaunchShortcut;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.ILabelDecorator;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;

import com.redhat.ceylon.compiler.typechecker.TypeChecker;
import com.redhat.ceylon.compiler.typechecker.context.PhasedUnit;
import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.TypeDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.eclipse.imp.builder.CeylonBuilder;
import com.redhat.ceylon.eclipse.imp.editor.Util;
import com.redhat.ceylon.eclipse.imp.outline.CeylonLabelProvider;
import com.redhat.ceylon.eclipse.launching.ICeylonLaunchConfigurationConstants;
import com.redhat.ceylon.eclipse.ui.CeylonPlugin;
import com.redhat.ceylon.eclipse.vfs.ResourceVirtualFile;

public class CeylonApplicationLaunchShortcut implements ILaunchShortcut {

    @Override
    public void launch(ISelection selection, String mode) {
        if (! (selection instanceof IStructuredSelection)) {
            return;
        }
        
        IStructuredSelection structuredSelection = (IStructuredSelection) selection;
        List<IFile> files = new LinkedList<IFile>(); 
        for (Object object : structuredSelection.toList()) {
            if (object instanceof IAdaptable) {
                IResource resource = (IResource) ((IAdaptable)object).getAdapter(IResource.class);
                if (resource != null) {
                    addFiles(files, resource);
                }
            }
        }
        searchAndLaunch(files, mode);
    }

    public void addFiles(List<IFile> files, IResource resource) {
        switch (resource.getType()) {
            case IResource.FILE:
                IFile file = (IFile) resource;
                IPath path = file.getFullPath(); //getProjectRelativePath();
                if (path!=null && CeylonBuilder.LANGUAGE.hasExtension(path.getFileExtension())) {
                    files.add(file);
                }
                break;
            case IResource.FOLDER:
            case IResource.PROJECT:
                IContainer folder = (IContainer) resource;
                try {
                    for (IResource child: folder.members()) {
                        addFiles(files, child);
                    }
                }
                catch (CoreException e) {
                    e.printStackTrace();
                }
                break;
        }
    }

    @Override
    public void launch(IEditorPart editor, String mode) {
        IEditorInput input = editor.getEditorInput();
        List<IFile> files = Arrays.asList(Util.getFile(input));
        searchAndLaunch(files, mode);
    }

    private void searchAndLaunch(List<IFile> files, String mode) {
        List<Declaration> topLevelDeclarations = new LinkedList<Declaration>();
        List<IFile> correspondingfiles = new LinkedList<IFile>();
        for (IFile file : files) {
            IProject project = file.getProject();
            TypeChecker typeChecker = CeylonBuilder.getProjectTypeChecker(project);
            if (typeChecker != null) {
                PhasedUnit phasedUnit = typeChecker.getPhasedUnits()
                        .getPhasedUnit(ResourceVirtualFile.createResourceVirtualFile(file));
                if (phasedUnit!=null) {
                    List<Declaration> declarations = phasedUnit.getUnit().getDeclarations();
                    for (Declaration d : declarations) {
                        boolean candidateDeclaration = true;
                        if (!d.isToplevel()) {
                            candidateDeclaration = false;
                        }
                        if (d instanceof Method) {
                            Method methodDecl = (Method) d;
                            if (!methodDecl.getParameterLists().isEmpty() && 
                                    !methodDecl.getParameterLists().get(0).getParameters().isEmpty()) {
                                candidateDeclaration = false;
                            }
                        }
                        else if (d instanceof Class) {
                            Class classDecl = (Class) d;
                            if (classDecl.isAbstract() || 
                                    classDecl.getParameterList()==null || 
                                    !classDecl.getParameterList().getParameters().isEmpty()) {
                                candidateDeclaration = false;
                            }
                        }
                        else {
                            candidateDeclaration = false;
                        }
                        if (candidateDeclaration) {
                            topLevelDeclarations.add(d);
                            correspondingfiles.add(file);
                        }
                    }
                }
            }
        }
        
        Declaration declarationToRun = null;
        IFile fileToRun = null; 
        if (topLevelDeclarations.size() == 0) {
            MessageDialog.openError(CeylonBuilder.getShell(), "Ceylon Launcher", "No ceylon runnable element"); 
        } 
        else if (topLevelDeclarations.size() > 1) {
            declarationToRun = chooseDeclaration(topLevelDeclarations);
            if (declarationToRun!=null) {
                fileToRun = correspondingfiles.get(topLevelDeclarations.indexOf(declarationToRun));
            }
        } 
        else {
            declarationToRun = topLevelDeclarations.get(0);
            fileToRun = correspondingfiles.get(0);
        }
        if (declarationToRun != null) {
            launch(declarationToRun, fileToRun, mode);
        }
    }

    private static final String SETTINGS_ID = CeylonPlugin.PLUGIN_ID + ".TOPLEVEL_DECLARATION_SELECTION_DIALOG";
    protected Declaration chooseDeclaration(final List<Declaration> declarations) {
        FilteredItemsSelectionDialog sd = new FilteredItemsSelectionDialog(CeylonBuilder.getShell())
        {
            {
                setTitle("Ceylon Launcher");
                setMessage("Select the toplevel method or class to launch:");
                setListLabelProvider(new LabelProvider());
                setDetailsLabelProvider(new DetailsLabelProvider());
                setListSelectionLabelDecorator(new SelectionLabelDecorator());
            }
            
            @Override
            protected Control createExtendedContentArea(Composite parent) {
                return null;
            }

            @Override
            protected IDialogSettings getDialogSettings() {
                IDialogSettings settings = CeylonPlugin.getInstance().getDialogSettings();
                IDialogSettings section = settings.getSection(SETTINGS_ID);
                if (section == null) {
                    section = settings.addNewSection(SETTINGS_ID);
                } 
                return section;
            }

            @Override
            protected IStatus validateItem(Object item) {
                return Status.OK_STATUS;
            }

            @Override
            protected ItemsFilter createFilter() {
                return new ItemsFilter() {
                    @Override
                    public boolean matchItem(Object item) {
                        return matchesRawNamePattern(item);
                    }
                    @Override
                    public boolean isConsistentItem(Object item) {
                        return true;
                    }
                    @Override
                    public String getPattern() {
                        String pattern = super.getPattern(); 
                        return pattern.isEmpty() ? "**" : pattern;
                    }
                };
            }

            @Override
            protected Comparator getItemsComparator() {
                Comparator comp = new Comparator() {
                    public int compare(Object o1, Object o2) {
                        if(o1 instanceof Declaration && o2 instanceof Declaration) {
                        	if (o1 instanceof TypedDeclaration && o2 instanceof TypeDeclaration) {
                        		return -1;
                        	}
                        	else if (o2 instanceof TypedDeclaration && o1 instanceof TypeDeclaration) {
                        		return 1;
                        	}
                        	else {
                        		return ((Declaration)o1).getName().compareTo(((Declaration)o2).getName());
                        	}
                        }
                        return 0;
                    }
                };
                return comp;
            }

            @Override
            protected void fillContentProvider(
                    AbstractContentProvider contentProvider,
                    ItemsFilter itemsFilter, IProgressMonitor progressMonitor)
                    throws CoreException {
                if(declarations != null) {
                    for(Declaration d : declarations) {
                        if(itemsFilter.isConsistentItem(d)) {
                            contentProvider.add(d, itemsFilter);
                        }
                    }
                }
            }

            @Override
            public String getElementName(Object item) {
                return ((Declaration) item).getName();
            }
            
        };
        
        if (sd.open() == Window.OK) {
            return (Declaration)sd.getResult()[0];
        }
        return null;
    }

    class LabelProvider extends StyledCellLabelProvider 
            implements DelegatingStyledCellLabelProvider.IStyledLabelProvider, ILabelProvider {
        
        @Override
        public void addListener(ILabelProviderListener listener) {}
        
        @Override
        public void dispose() {}
        
        @Override
        public boolean isLabelProperty(Object element, String property) {
            return false;
        }
        
        @Override
        public void removeListener(ILabelProviderListener listener) {}
        
        @Override
        public Image getImage(Object element) {
            Declaration d = (Declaration) element;
            return d==null ? null : CeylonLabelProvider.getImage(d);
        }
        
        @Override
        public String getText(Object element) {
            Declaration d = (Declaration) element;
            return d==null ? null : getDescriptionFor(d);
        }
        
        @Override
        public StyledString getStyledText(Object element) {
            if (element==null) {
                return new StyledString();
            }
            else {
                Declaration d = (Declaration) element;
                return getStyledDescriptionFor(d);
            }
        }
        
        @Override
        public void update(ViewerCell cell) {
            Object element = cell.getElement();
            if (element!=null) {
                StyledString styledText = getStyledText(element);
                cell.setText(styledText.toString());
                cell.setStyleRanges(styledText.getStyleRanges());
                cell.setImage(getImage(element));
                super.update(cell);
            }
        }
    
    }
    
    class DetailsLabelProvider implements ILabelProvider {
        @Override
        public void removeListener(ILabelProviderListener listener) {}
        
        @Override
        public boolean isLabelProperty(Object element, String property) {
            return false;
        }
        
        @Override
        public void dispose() {}
        
        @Override
        public void addListener(ILabelProviderListener listener) {}
        
        @Override
        public String getText(Object element) {
            return getPackageLabel((Declaration) element);
        }

        @Override
        public Image getImage(Object element) {
            return CeylonLabelProvider.PACKAGE;
        }
    }
    
    class SelectionLabelDecorator implements ILabelDecorator {
        @Override
        public void removeListener(ILabelProviderListener listener) {}
        
        @Override
        public boolean isLabelProperty(Object element, String property) {
            return false;
        }
        
        @Override
        public void dispose() {}
        
        @Override
        public void addListener(ILabelProviderListener listener) {}
        
        @Override
        public String decorateText(String text, Object element) {
            return text + " - " + getPackageLabel((Declaration) element);
        }
        
        @Override
        public Image decorateImage(Image image, Object element) {
            return null;
        }
    }

    private void launch(Declaration declarationToRun, IFile fileToRun, String mode) {
        ILaunchConfiguration config = findLaunchConfiguration(declarationToRun, fileToRun, getConfigurationType());
        if (config == null) {
            config = createConfiguration(declarationToRun, fileToRun);
        }
        if (config != null) {
            DebugUITools.launch(config, mode);
        }           
    }

    protected ILaunchConfigurationType getConfigurationType() {
        return getLaunchManager().getLaunchConfigurationType(ICeylonLaunchConfigurationConstants.ID_CEYLON_APPLICATION);        
    }
    
    private ILaunchManager getLaunchManager() {
        return DebugPlugin.getDefault().getLaunchManager();
    }
    
    /**
     * Finds and returns an <b>existing</b> configuration to re-launch for the given type,
     * or <code>null</code> if there is no existing configuration.
     * 
     * @return a configuration to use for launching the given type or <code>null</code> if none
     */
    protected ILaunchConfiguration findLaunchConfiguration(Declaration declaration, IFile file, ILaunchConfigurationType configType) {
        List candidateConfigs = Collections.EMPTY_LIST;
        try {
            ILaunchConfiguration[] configs = DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations(configType);
            candidateConfigs = new ArrayList(configs.length);
            for (int i = 0; i < configs.length; i++) {
                ILaunchConfiguration config = configs[i];
                if (config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "").equals(declaration.getQualifiedNameString())) { 
                    if (config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "").equals(file.getProject().getName())) {
                        candidateConfigs.add(config);
                    }
                }
            }
        } catch (CoreException e) {
            e.printStackTrace(); // TODO : Use a logger
        }
        int candidateCount = candidateConfigs.size();
        if (candidateCount == 1) {
            return (ILaunchConfiguration) candidateConfigs.get(0);
        } else if (candidateCount > 1) {
            return chooseConfiguration(candidateConfigs);
        }
        return null;
    }
    
    /**
     * Returns a configuration from the given collection of configurations that should be launched,
     * or <code>null</code> to cancel. Default implementation opens a selection dialog that allows
     * the user to choose one of the specified launch configurations.  Returns the chosen configuration,
     * or <code>null</code> if the user cancels.
     * 
     * @param configList list of configurations to choose from
     * @return configuration to launch or <code>null</code> to cancel
     */
    protected ILaunchConfiguration chooseConfiguration(List configList) {
        IDebugModelPresentation labelProvider = DebugUITools.newDebugModelPresentation();
        ElementListSelectionDialog dialog= new ElementListSelectionDialog(CeylonBuilder.getShell(), labelProvider);
        dialog.setElements(configList.toArray());
        dialog.setTitle("Ceylon Launcher");  
        dialog.setMessage("Please choose a configuration to start the Ceylon application");
        dialog.setMultipleSelection(false);
        int result = dialog.open();
        labelProvider.dispose();
        if (result == Window.OK) {
            return (ILaunchConfiguration) dialog.getFirstResult();
        }
        return null;        
    }
    
    protected ILaunchConfiguration createConfiguration(Declaration declarationToRun, IFile file) {
        ILaunchConfiguration config = null;
        ILaunchConfigurationWorkingCopy wc = null;
        try {
            ILaunchConfigurationType configType = getConfigurationType();
            String configurationName = "";
            if (declarationToRun instanceof Class) {
                configurationName += "class ";
            }
            else {
                if (declarationToRun instanceof Method) {
                    Method method = (Method) declarationToRun;
                    if (method.isDeclaredVoid()) {
                        configurationName += "void ";
                    }
                    else {
                        configurationName += "function ";
                    }
                }
            }
            configurationName += declarationToRun.getName() + "() - ";
            String packageName = declarationToRun.getContainer().getQualifiedNameString();
            configurationName += packageName.isEmpty() ? "default package" : packageName;
            
            wc = configType.newInstance(null, getLaunchManager().generateLaunchConfigurationName(configurationName));
            wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, declarationToRun.getQualifiedNameString());
            wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, file.getProject().getName());
            wc.setMappedResources(new IResource[] {file});
            config = wc.doSave();
        } catch (CoreException exception) {
            MessageDialog.openError(CeylonBuilder.getShell(), "Ceylon Launcher Error", exception.getStatus().getMessage()); 
        } 
        return config;
    }
}
