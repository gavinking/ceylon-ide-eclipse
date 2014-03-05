package com.redhat.ceylon.eclipse.code.refactor;

/*******************************************************************************
 * Copyright (c) 2005, 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

import static com.redhat.ceylon.eclipse.code.complete.LinkedModeCompletionProposal.getNameProposals;
import static com.redhat.ceylon.eclipse.ui.CeylonPlugin.PLUGIN_ID;
import static com.redhat.ceylon.eclipse.util.FindUtils.getAbstraction;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.internal.ui.refactoring.RefactoringExecutionHelper;
import org.eclipse.jdt.ui.refactoring.RefactoringSaveHelper;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.link.LinkedPositionGroup;
import org.eclipse.jface.text.link.ProposalPosition;
import org.eclipse.ltk.core.refactoring.DocumentChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.ui.refactoring.RefactoringWizard;

import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.tree.NaturalVisitor;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.Identifier;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;
import com.redhat.ceylon.eclipse.code.editor.CeylonEditor;
import com.redhat.ceylon.eclipse.code.parse.CeylonTokenColorer;


public class EnterAliasLinkedMode extends RefactorLinkedMode {

    protected LinkedPosition namePosition;
    protected LinkedPositionGroup linkedPositionGroup;
    
    private EnterAliasRefactoring refactoring;

    private final class LinkedPositionsVisitor 
            extends Visitor implements NaturalVisitor {
        private final int adjust;
        private final IDocument document;
        private final LinkedPositionGroup linkedPositionGroup;
        int i=1;

        private LinkedPositionsVisitor(int adjust, IDocument document,
                LinkedPositionGroup linkedPositionGroup) {
            this.adjust = adjust;
            this.document = document;
            this.linkedPositionGroup = linkedPositionGroup;
        }

        @Override
        public void visit(Tree.StaticMemberOrTypeExpression that) {
            super.visit(that);
            addLinkedPosition(document, that.getIdentifier(), 
                    that.getDeclaration());
        }
        
        @Override
        public void visit(Tree.SimpleType that) {
            super.visit(that);
            addLinkedPosition(document, that.getIdentifier(), 
                    that.getDeclarationModel());
        }

        @Override
        public void visit(Tree.MemberLiteral that) {
            super.visit(that);
            addLinkedPosition(document, that.getIdentifier(), 
                    that.getDeclaration());
        }
        
        private void addLinkedPosition(final IDocument document,
                Identifier id, Declaration d) {
            if (id!=null && d!=null && 
                    refactoring.getElement().getDeclarationModel()
                            .equals(getAbstraction(d))) {
                try {
                    int pos = id.getStartIndex()+adjust;
                    int len = id.getText().length();
                    linkedPositionGroup.addPosition(new LinkedPosition(document, 
                            pos, len, i++));
                }
                catch (BadLocationException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public EnterAliasLinkedMode(CeylonEditor editor) {
        super(editor);
        refactoring = new EnterAliasRefactoring(editor);
    }
    
    @Override
    protected String getName() {
        return refactoring.getNewName();
    }

    @Override
    public String getHintTemplate() {
        return "Enter alias for " + linkedPositionGroup.getPositions().length + 
                " occurrences of '" + 
                refactoring.getElement().getDeclarationModel().getName() + 
                "' {0}";
    }
    
    @Override
    protected int performInitialChange(IDocument document) {
        try {
            DocumentChange change = 
                    new DocumentChange("Enter Alias", document);
            int result = refactoring.renameInFile(change);
            change.perform(new NullProgressMonitor());
            return result;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    protected String getNewNameFromNamePosition() {
        try {
            return namePosition.getContent();
        }
        catch (BadLocationException e) {
            return getOriginalName();
        }
    }
    
    @Override
    protected void setupLinkedPositions(IDocument document, int adjust)
            throws BadLocationException {
        linkedPositionGroup = new LinkedPositionGroup();        
        int offset;
        Tree.ImportMemberOrType element = refactoring.getElement();
        Tree.Alias alias = element.getAlias();
        if (alias == null) {
            offset = element.getIdentifier().getStartIndex();
        }
        else {
            offset = alias.getStartIndex();
        }
        String originalName = getOriginalName();
        namePosition = new ProposalPosition(document, offset, 
                originalName.length(), 0,
                getNameProposals(offset, 0, 
                        element.getDeclarationModel().getName(),
                        originalName));
        
        linkedPositionGroup.addPosition(namePosition);
        editor.getParseController().getRootNode()
                .visit(new LinkedPositionsVisitor(adjust, document, 
                        linkedPositionGroup));
        linkedModeModel.addGroup(linkedPositionGroup);
    }

    private boolean isEnabled() {
        String newName = getNewNameFromNamePosition();
        return !getOriginalName().equals(newName) &&
                newName.matches("^\\w(\\w|\\d)*$") &&
                !CeylonTokenColorer.keywords.contains(newName);
    }

    @Override
    public void done() {
        if (isEnabled()) {
            try {
//                hideEditorActivity();
                setName(getNewNameFromNamePosition());
                revertChanges();
                if (isShowPreview()) {
                    openPreview();
                }
                else {
                    new RefactoringExecutionHelper(refactoring,
                            RefactoringStatus.WARNING,
                            RefactoringSaveHelper.SAVE_ALL,
                            editor.getSite().getShell(),
                            editor.getSite().getWorkbenchWindow())
                        .perform(false, true);
                }
            } 
            catch (Exception e) {
                e.printStackTrace();
            }
//            finally {
//                unhideEditorActivity();
//            }
            super.done();
        }
        else {
            super.cancel();
        }
    }

    @Override
    protected String getActionName() {
        return PLUGIN_ID + ".action.enterAlias";
    }

    @Override
    protected void setName(String name) {
        refactoring.setNewName(name);
    }

    @Override
    protected boolean canStart() {
        return refactoring.isEnabled();
    }

    @Override
    protected void openPreview() {
        new EnterAliasRefactoringAction(editor) {
            @Override
            public AbstractRefactoring createRefactoring() {
                return EnterAliasLinkedMode.this.refactoring;
            }
            @Override
            public RefactoringWizard createWizard(AbstractRefactoring refactoring) {
                return new EnterAliasWizard((EnterAliasRefactoring) refactoring) {
                    @Override
                    protected void addUserInputPages() {}
                };
            }
        }.run();
    }

    @Override
    protected void openDialog() {
        new EnterAliasRefactoringAction(editor) {
            @Override
            public AbstractRefactoring createRefactoring() {
                return EnterAliasLinkedMode.this.refactoring;
            }
        }.run();
    }
    
}