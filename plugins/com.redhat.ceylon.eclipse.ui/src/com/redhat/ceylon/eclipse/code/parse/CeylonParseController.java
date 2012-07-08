package com.redhat.ceylon.eclipse.code.parse;

import static com.redhat.ceylon.compiler.java.util.Util.makeRepositoryManager;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.PROBLEM_MARKER_ID;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.getCeylonModulesOutputDirectory;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.getProjectModelLoader;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.getProjectRepositoryManager;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.getProjectTypeChecker;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.getProjects;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.getSourceFolders;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.getUserRepositories;
import static com.redhat.ceylon.eclipse.core.builder.CeylonBuilder.isModelAvailable;
import static com.redhat.ceylon.eclipse.ui.CeylonPlugin.LANGUAGE_ID;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.runtime.ANTLRInputStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.imp.editor.quickfix.IAnnotation;
import org.eclipse.imp.model.ISourceProject;
import org.eclipse.imp.model.ModelFactory;
import org.eclipse.imp.model.ModelFactory.ModelException;
import org.eclipse.imp.parser.IMessageHandler;
import org.eclipse.imp.parser.ParseControllerBase;
import org.eclipse.imp.parser.SimpleAnnotationTypeInfo;
import org.eclipse.imp.services.IAnnotationTypeInfo;
import org.eclipse.imp.services.ILanguageSyntaxProperties;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.text.IRegion;

import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.compiler.loader.AbstractModelLoader;
import com.redhat.ceylon.compiler.loader.model.LazyPackage;
import com.redhat.ceylon.compiler.typechecker.TypeChecker;
import com.redhat.ceylon.compiler.typechecker.TypeCheckerBuilder;
import com.redhat.ceylon.compiler.typechecker.analyzer.UsageWarning;
import com.redhat.ceylon.compiler.typechecker.context.PhasedUnit;
import com.redhat.ceylon.compiler.typechecker.io.VirtualFile;
import com.redhat.ceylon.compiler.typechecker.model.Module;
import com.redhat.ceylon.compiler.typechecker.model.Modules;
import com.redhat.ceylon.compiler.typechecker.model.Package;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.parser.CeylonLexer;
import com.redhat.ceylon.compiler.typechecker.parser.CeylonParser;
import com.redhat.ceylon.compiler.typechecker.parser.LexError;
import com.redhat.ceylon.compiler.typechecker.parser.ParseError;
import com.redhat.ceylon.compiler.typechecker.tree.Message;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.eclipse.code.editor.CeylonParserScheduler;
import com.redhat.ceylon.eclipse.core.builder.CeylonBuilder;
import com.redhat.ceylon.eclipse.core.vfs.IFolderVirtualFile;
import com.redhat.ceylon.eclipse.core.vfs.SourceCodeVirtualFile;
import com.redhat.ceylon.eclipse.core.vfs.TemporaryFile;
import com.redhat.ceylon.eclipse.util.EclipseLogger;
import com.redhat.ceylon.eclipse.util.ErrorVisitor;

public class CeylonParseController extends ParseControllerBase {
    
    public CeylonParseController() {
        super(LANGUAGE_ID);
    }
    
    private final SimpleAnnotationTypeInfo simpleAnnotationTypeInfo = new SimpleAnnotationTypeInfo();
    private CeylonSourcePositionLocator sourcePositionLocator;

    private List<CommonToken> tokens;
    private TypeChecker typeChecker;
    private AbstractModelLoader modelLoader;
    
    /**
     * @param filePath		Project-relative path of file
     * @param project		Project that contains the file
     * @param handler		A message handler to receive error messages (or any others)
     * 						from the parser
     */
    public void initialize(IPath filePath, ISourceProject project, IMessageHandler handler) {
        super.initialize(filePath, project, handler);
        simpleAnnotationTypeInfo.addProblemMarkerType(PROBLEM_MARKER_ID);
    }
    
    public CeylonSourcePositionLocator getSourcePositionLocator() {
        if (sourcePositionLocator == null) {
            sourcePositionLocator= new CeylonSourcePositionLocator(this);
        }
        return sourcePositionLocator;
    }
    
    public ILanguageSyntaxProperties getSyntaxProperties() {
        return CeylonLanguageSyntaxProperties.INSTANCE;
    }
    
    public IAnnotationTypeInfo getAnnotationTypeInfo() {
        return simpleAnnotationTypeInfo;
    }
    
    private boolean isCanceling(IProgressMonitor monitor) {
        boolean isCanceling = false;
        if (monitor != null) {
            isCanceling = monitor.isCanceled();
        }
        CeylonParserScheduler scheduler = getScheduler();
        if (scheduler != null && scheduler.isCanceling()) {
            if (monitor != null && !monitor.isCanceled()) {
                monitor.setCanceled(true);
            }
            isCanceling = true;
        }
        return isCanceling;
    }
    
    private CeylonParserScheduler getScheduler() {
        final Job parsingJob = Job.getJobManager().currentJob();
        if (parsingJob instanceof CeylonParserScheduler) {
            return (CeylonParserScheduler) parsingJob;
        }
        return null;
    }
    
    public Object parse(String contents, IProgressMonitor monitor) {
        
        IPath path = getPath();
        ISourceProject sourceProject = getProject();
        IPath resolvedPath = path;
        IPath fullPath = path;
        VirtualFile file;
        if (path!=null) {
            if (sourceProject!=null) {
                resolvedPath = sourceProject.resolvePath(path);
                fullPath = sourceProject.getRawProject().getLocation().append(path);
                if (resolvedPath == null) {// file has been deleted for example
                    path = null;
                    sourceProject = null;
                }
            }
        }
        if (path == null) {
            file = new SourceCodeVirtualFile(contents);
        } else {
            file = new SourceCodeVirtualFile(contents, fullPath);
        }
        
        if (! file.getName().endsWith(".ceylon")) {
            return fCurrentAst;
        }
        if (isCanceling(monitor)) {
            return fCurrentAst;
        }
        
        ANTLRInputStream input;
        try {
            input = new ANTLRInputStream(file.getInputStream());
        } 
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        CeylonLexer lexer = new CeylonLexer(input);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        
        if (isCanceling(monitor)) {
            return fCurrentAst;
        }
        
        CeylonParser parser = new CeylonParser(tokenStream);
        Tree.CompilationUnit cu;
        try {
            cu = parser.compilationUnit();
        }
        catch (RecognitionException e) {
            throw new RuntimeException(e);
        }
        
        tokens = new ArrayList<CommonToken>(tokenStream.getTokens().size()); 
        tokens.addAll(tokenStream.getTokens());

        if (isCanceling(monitor)) {
            return fCurrentAst;
        }
        
        VirtualFile srcDir = null;
        IProject project = null;
        
        if (sourceProject!=null) {
            srcDir = getSourceFolder(sourceProject.getRawProject(), resolvedPath);
            project = sourceProject.getRawProject();
        }
        
        if (srcDir == null && project == null) {
            if (path!=null) { //path==null in structured compare editor
                //for files from external repos, search for
                //the repo by iterating all repos referenced
                //by all projects

                String pathString = path.toString();
                String artifactName = null;
                String version = null;
                int lastColonIdx = pathString.lastIndexOf('!');
                if (lastColonIdx > 0) {
                    String srcArchivePath= pathString.substring(0, lastColonIdx);
                    String separator = Pattern.quote("/");
                    String hyphen = Pattern.quote("-");
                    String dot = Pattern.quote(".");
                    Pattern pattern = Pattern.compile(".*" + separator + "([^" + separator + "]+)" + hyphen + "(.+)" + dot + "src");
                    Matcher matcher = pattern.matcher(srcArchivePath);
                    if (matcher.matches()) {
                        artifactName = matcher.group(1);
                        version = matcher.group(2);
                    }
                    srcDir = new TemporaryFile(srcArchivePath+'!');
                }
                
                for (IProject p: CeylonBuilder.getProjects()) {
                    boolean found = false;
                    //RepositoryManager manager = getProjectRepositoryManager(project);
                    
                    if (artifactName != null && version != null) {
                        RepositoryManager repositoryManager = getProjectRepositoryManager(p);
                        File artifact = null;
                        artifact = repositoryManager.getArtifact(artifactName, version);
                        if (artifact != null) {
                            try {
                                sourceProject = ModelFactory.open(p);
                                project = p;
                                found=true;
                            } catch (ModelException e) {
                                e.printStackTrace();
                            }
                            break;
                        }
                        if (found) break;
                    }
                }
            }
        }
        
        if (isCanceling(monitor)) {
            return fCurrentAst;
        }

        if (project != null) {
            if (!isModelAvailable(project)) {
                return fCurrentAst; // TypeChecking has not been performed.
            }
            typeChecker = getProjectTypeChecker(project);
            modelLoader = getProjectModelLoader(project);
        }
        
        //System.out.println("Compiling " + file.getPath());
        
        if (isCanceling(monitor)) {
            return fCurrentAst;
        }

        List<LexError> lexerErrors = lexer.getErrors();
        for (LexError le : lexerErrors) {
            //System.out.println("Lexer error in " + file.getName() + ": " + le.getMessage());
            cu.addLexError(le);
        }
        lexerErrors.clear();
        
        List<ParseError> parserErrors = parser.getErrors();
        for (ParseError pe : parserErrors) {
            //System.out.println("Parser error in " + file.getName() + ": " + pe.getMessage());
            cu.addParseError(pe);
        }
        parserErrors.clear();
        
        fCurrentAst = cu;
        
        boolean showWarnings = CeylonBuilder.showWarnings(project);
        
        if (typeChecker == null) {
        	TypeCheckerBuilder tcb = new TypeCheckerBuilder()
        	        .verbose(false).usageWarnings(showWarnings);
        	
        	if (path!=null) { //path==null in structured compare editor
                for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                    if (p.getLocation().isPrefixOf(path)) {
                        project = p;
                        break;
                    }
                }
            }
            
            List<String> repos = new LinkedList<String>();
            try {
                if (project==null) {
                    if (path!=null) { //path==null in structured compare editor
                        //for files from external repos, search for
                        //the repo by iterating all repos referenced
                        //by all projects

                        for (IProject p: getProjects()) {
                            boolean found = false;
                            for (String repo: getUserRepositories(p)) {
                                if (path.toString().startsWith(repo)) {
                                    repos.add(repo);
                                    found=true;
                                    break;
                                }
                            }

                            if (found) break;
                        }
                    }
                }
                else {
                    for (String repo: getUserRepositories(project)) {
                        repos.add(repo);
                    }
                    repos.add(getCeylonModulesOutputDirectory(JavaCore.create(project)).getAbsolutePath());
                }
            } 
            catch (CoreException e) {
                return fCurrentAst; 
            }
        	tcb.setRepositoryManager(makeRepositoryManager(repos, null, new EclipseLogger()));
            
        	TypeChecker tc = tcb.getTypeChecker();
            tc.process();
            typeChecker = tc;
        }
        
        if (isCanceling(monitor)) {
            return fCurrentAst;
        }

        PhasedUnit builtPhasedUnit = typeChecker.getPhasedUnit(file);
        
        PhasedUnit phasedUnit;
        if (isExternalPath(path) && builtPhasedUnit != null) {
            // reuse the existing AST
            cu = builtPhasedUnit.getCompilationUnit();
            fCurrentAst = cu;
            phasedUnit = builtPhasedUnit;
            phasedUnit.analyseTypes();
			if (showWarnings) {
                phasedUnit.analyseUsage();
            }
        }
        else {
            Package pkg = null;
            if (builtPhasedUnit!=null) {
                // Editing an already built file
                Package sourcePackage = builtPhasedUnit.getPackage();
                if (sourcePackage instanceof LazyPackage) {
                    if (modelLoader != null) {
                        pkg = new LazyPackage(modelLoader);
                    } else {
                        pkg = new Package();
                    }
                } else {
                    pkg = new Package();
                }
                
                pkg.setName(sourcePackage.getName());
                pkg.setModule(sourcePackage.getModule());
                for (Unit pkgUnit : sourcePackage.getUnits()) {
                    pkg.addUnit(pkgUnit);
                }
            }
            else {
                // Editing a new file
                Modules modules = typeChecker.getContext().getModules();
                if (srcDir==null) {
                    srcDir = new TemporaryFile();
                }
                else {
                    // Retrieve the target package from the file src-relative path
                    //TODO: this is very fragile!
                    String packageName = constructPackageName(file, srcDir);
                    for (Module module: modules.getListOfModules()) {
                        for (Package p: module.getPackages()) {
                            if (p.getQualifiedNameString().equals(packageName)) {
                                pkg = p;
                                break;
                            }
                        }
                        if (pkg != null) {
                            break;
                        }
                    }
                    //TODO: is this better?
                    //pkg = modelLoader.findPackage(packageName);
                }
                if (pkg == null) {
                    // assume the default package
                    pkg = modules.getDefaultModule().getPackages().get(0);
                    
                    // TODO : iterate through parents to get the sub-package 
                    // in which the package has been created, until we find the module
                    // Then the package can be created.
                    // However this should preferably be done on notification of the 
                    // resource creation
                    // A more global/systematic integration between the model element 
                    // (modules, packages, Units) and the IResourceModel should
                    // maybe be considered. But for now it is not required.
                }
            }
            
            phasedUnit = new PhasedUnit(file, srcDir, cu, pkg, 
                    typeChecker.getPhasedUnits().getModuleManager(), 
                    typeChecker.getContext(), tokens);  
            
            phasedUnit.validateTree();
            phasedUnit.visitSrcModulePhase();
            phasedUnit.visitRemainingModulePhase();
            phasedUnit.scanDeclarations();
            phasedUnit.scanTypeDeclarations();
            phasedUnit.validateRefinement();
            phasedUnit.analyseTypes();
            if (showWarnings) {
            	phasedUnit.analyseUsage();
            }
            phasedUnit.analyseFlow();
        }
            
        if (isCanceling(monitor)) {
            return fCurrentAst;
        }
        
        final IMessageHandler handler = getHandler();
        if (handler!=null) {
            cu.visit(new ErrorVisitor(handler) {
                @Override
                public int getSeverity(Message error, boolean expected) {
                    return expected || error instanceof UsageWarning ? 
                    		IAnnotation.WARNING : IAnnotation.ERROR;
                }
            });      
        }
        
        //System.out.println("Finished compiling " + file.getPath());
        
        return fCurrentAst;
    }

	public boolean isExternalPath(IPath path) {
        IWorkspaceRoot wsRoot= ResourcesPlugin.getWorkspace().getRoot();
        // If the path is outside the workspace, or pointing inside the workspace, 
        // but is still file-system-absolute.
        return path!=null && path.isAbsolute() && 
        		(wsRoot.getLocation().isPrefixOf(path) || !wsRoot.exists(path));
    }
    
    private String constructPackageName(VirtualFile file, VirtualFile srcDir) {
        return file.getPath().replaceFirst(srcDir.getPath() + "/", "")
                .replace("/" + file.getName(), "").replace('/', '.');
    }
    
    private VirtualFile getSourceFolder(IProject project, IPath resolvedPath) {
        for (IPath folderPath: getSourceFolders(project)) {
            if (folderPath.isPrefixOf(resolvedPath)) {
                return new IFolderVirtualFile(project, 
                        folderPath.makeRelativeTo(project.getFullPath()));
            }
        }
        return null;
    }
    
    public Iterator<CommonToken> getTokenIterator(IRegion region) {
        return CeylonSourcePositionLocator.getTokenIterator(getTokens(), region);
    }
    
    public List<CommonToken> getTokens() {
        return tokens;
    }
    
    public TypeChecker getTypeChecker() {
        return typeChecker;
    }
    
    public Tree.CompilationUnit getRootNode() {
        return (Tree.CompilationUnit) getCurrentAst();
    }
    
}
