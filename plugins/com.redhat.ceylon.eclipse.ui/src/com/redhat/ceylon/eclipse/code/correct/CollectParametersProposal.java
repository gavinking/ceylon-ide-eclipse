package com.redhat.ceylon.eclipse.code.correct;

import java.util.Collection;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

import com.redhat.ceylon.eclipse.code.editor.CeylonEditor;
import com.redhat.ceylon.eclipse.code.outline.CeylonLabelProvider;
import com.redhat.ceylon.eclipse.code.refactor.CollectParametersRefactoring;
import com.redhat.ceylon.eclipse.code.refactor.CollectParametersRefactoringAction;

class CollectParametersProposal implements ICompletionProposal,
        ICompletionProposalExtension6 {

    private final CeylonEditor editor;
        
    CollectParametersProposal(CeylonEditor editor) {
        this.editor = editor;
    }
    
    @Override
    public Point getSelection(IDocument doc) {
        return null;
    }

    @Override
    public Image getImage() {
        return CeylonLabelProvider.COMPOSITE_CHANGE;
    }

    @Override
    public String getDisplayString() {
        return "Collect selected parameters into new class";
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
        new CollectParametersRefactoringAction(editor).run();
    }
    
    @Override
    public StyledString getStyledDisplayString() {
        return CorrectionUtil.styleProposal(getDisplayString());
    }

    public static void add(Collection<ICompletionProposal> proposals,
            CeylonEditor editor) {
        CollectParametersRefactoring cpr = new CollectParametersRefactoring(editor);
        if (cpr.isEnabled()) {
            proposals.add(new CollectParametersProposal(editor));
        }
    }

}