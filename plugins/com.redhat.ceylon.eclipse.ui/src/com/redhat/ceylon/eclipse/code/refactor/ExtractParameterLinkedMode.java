package com.redhat.ceylon.eclipse.code.refactor;

import static com.redhat.ceylon.compiler.typechecker.model.Util.isTypeUnknown;
import static com.redhat.ceylon.eclipse.ui.CeylonPlugin.PLUGIN_ID;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.DocumentChange;
import org.eclipse.ltk.ui.refactoring.RefactoringWizard;

import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.CompilationUnit;
import com.redhat.ceylon.eclipse.code.editor.CeylonEditor;

public final class ExtractParameterLinkedMode 
        extends ExtractLinkedMode {
        
    private final ExtractParameterRefactoring refactoring;
    
    public ExtractParameterLinkedMode(CeylonEditor editor) {
        super(editor);
        this.refactoring = new ExtractParameterRefactoring(editor);
    }
    
    @Override
    protected int performInitialChange(IDocument document) {
        try {
            DocumentChange change = 
                    new DocumentChange("Extract Parameter", document);
            refactoring.extractInFile(change);
            change.perform(new NullProgressMonitor());
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
        return 0;
    }
    
    @Override
    protected boolean canStart() {
        return refactoring.isEnabled();
    }
    
    @Override
    protected int getIdentifyingOffset() {
        return refactoring.decRegion.getOffset();
    }
    
    @Override
    protected int getExitPosition(int selectionOffset, int adjust) {
        return refactoring.refRegion.getOffset();
    }
    
    @Override
    protected void addLinkedPositions(IDocument document,
            CompilationUnit rootNode, int adjust) {
        
        addNamePosition(document, 
                refactoring.refRegion.getOffset(),
                refactoring.refRegion.getLength());
        
        ProducedType type = refactoring.getType();
        if (!isTypeUnknown(type)) {
            addTypePosition(document, type.getSupertypes(), 
                    refactoring.typeRegion.getOffset(), 
                    refactoring.typeRegion.getLength());
        }
        
    }
    
    @Override
    protected String getName() {
        return refactoring.getNewName();
    }
    
    @Override
    protected void setName(String name) {
        refactoring.setNewName(name);
    }
    
    @Override
    protected boolean forceWizardMode() {
        return refactoring.forceWizardMode();
    }
    
    @Override
    protected String getActionName() {
        return PLUGIN_ID + ".action.extractParameter";
    }
    
    @Override
    protected void openPreview() {
        new RenameRefactoringAction(editor) {
            @Override
            public AbstractRefactoring createRefactoring() {
                return ExtractParameterLinkedMode.this.refactoring;
            }
            @Override
            public RefactoringWizard createWizard(AbstractRefactoring refactoring) {
                return new ExtractValueWizard((ExtractValueRefactoring) refactoring) {
                    @Override
                    protected void addUserInputPages() {}
                };
            }
        }.run();
    }

    @Override
    protected void openDialog() {
        new ExtractParameterRefactoringAction(editor) {
            @Override
            public AbstractRefactoring createRefactoring() {
                return ExtractParameterLinkedMode.this.refactoring;
            }
        }.run();
    }
    
    @Override
    protected String getKind() {
        return "parameter";
    }
    
}
