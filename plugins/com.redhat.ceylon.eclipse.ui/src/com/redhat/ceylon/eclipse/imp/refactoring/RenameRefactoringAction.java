package com.redhat.ceylon.eclipse.imp.refactoring;

import org.eclipse.ltk.ui.refactoring.RefactoringWizard;
import org.eclipse.ui.IEditorPart;

public class RenameRefactoringAction extends AbstractRefactoringAction {
	public RenameRefactoringAction(IEditorPart editor) {
		super("Rename.", editor);
		setActionDefinitionId("com.redhat.ceylon.eclipse.ui.action.rename");
	}
	
	@Override
	public AbstractRefactoring createRefactoring() {
	    return new RenameRefactoring(getTextEditor());
	}
	
	@Override
	public RefactoringWizard createWizard(AbstractRefactoring refactoring) {
	    return new RenameWizard((RenameRefactoring) refactoring);
	}
	
	@Override
	String message() {
	    return "No declaration name selected";
	}
	
	public String currentName() {
	    return ((RenameRefactoring) refactoring).getDeclaration().getName();
	}
	
}
