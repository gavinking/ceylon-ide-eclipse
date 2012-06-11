package com.redhat.ceylon.eclipse.imp.quickfix;

import java.util.Collection;

import org.eclipse.imp.editor.UniversalEditor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

import com.redhat.ceylon.eclipse.imp.outline.CeylonLabelProvider;
import com.redhat.ceylon.eclipse.imp.refactoring.ExtractValueRefactoringAction;

class ExtractValueProposal implements ICompletionProposal {

    private ExtractValueRefactoringAction action;
    
    public ExtractValueProposal(UniversalEditor editor) {
        action = new ExtractValueRefactoringAction(editor);
    }
    
    @Override
    public Point getSelection(IDocument doc) {
    	return null;
    }

    @Override
    public Image getImage() {
    	return CeylonLabelProvider.LOCAL_ATTRIBUTE;
    }

    @Override
    public String getDisplayString() {
    	return "Extract value";
    }

    @Override
    public IContextInformation getContextInformation() {
    	return null;
    }

    @Override
    public String getAdditionalProposalInfo() {
    	return null;
    }

    @Override
    public void apply(IDocument doc) {
        action.run();
    }
    
    boolean isEnabled() {
        return action.isEnabled();
    }
    
    public static void add(Collection<ICompletionProposal> proposals, UniversalEditor editor) {
        ExtractValueProposal prop = new ExtractValueProposal(editor);
        if (prop.isEnabled()) {
            proposals.add(prop);
        }
    }

}