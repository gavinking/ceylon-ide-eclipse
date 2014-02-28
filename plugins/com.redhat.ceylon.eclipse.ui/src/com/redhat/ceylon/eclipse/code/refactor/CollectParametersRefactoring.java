package com.redhat.ceylon.eclipse.code.refactor;

import static com.redhat.ceylon.eclipse.code.parse.CeylonSourcePositionLocator.getNodeEndOffset;
import static com.redhat.ceylon.eclipse.code.parse.CeylonSourcePositionLocator.getNodeLength;
import static com.redhat.ceylon.eclipse.code.parse.CeylonSourcePositionLocator.getNodeStartOffset;
import static com.redhat.ceylon.eclipse.util.FindUtils.findToplevelStatement;
import static com.redhat.ceylon.eclipse.util.Indents.getDefaultLineDelimiter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.DocumentChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.ui.texteditor.ITextEditor;

import com.redhat.ceylon.compiler.typechecker.context.PhasedUnit;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;
import com.redhat.ceylon.eclipse.util.FindRefinementsVisitor;

public class CollectParametersRefactoring extends AbstractRefactoring {
    
    private Declaration declaration;
    private int parameterListIndex;
    private List<Tree.Parameter> parameters = 
            new ArrayList<Tree.Parameter>();
    private Set<MethodOrValue> models = 
            new HashSet<MethodOrValue>();
    private int firstParam=-1;
    private int lastParam;
    
    private class FindParametersVisitor extends Visitor {
        private void handleParamList(Tree.Declaration that, int i,
                Tree.ParameterList pl) {
            IRegion selection = editor.getSelection();
            int start = selection.getOffset();
            int end = selection.getOffset() + selection.getLength();
            if (start>pl.getStartIndex() &&
                start<=pl.getStopIndex()) {
                parameterListIndex = i;
                declaration = that.getDeclarationModel();
                for (int j=0; j<pl.getParameters().size(); j++) {
                    Tree.Parameter p = pl.getParameters().get(j);
                    if (p.getStartIndex()>=start && p.getStopIndex()<end) {
                        parameters.add(p);
                        models.add(p.getParameterModel().getModel());
                        if (firstParam==-1) firstParam=j;
                        lastParam=j;
                    }
                }
            }
        }
        @Override
        public void visit(Tree.AnyMethod that) {
            for (int i=0; i<that.getParameterLists().size(); i++) {
                handleParamList(that, i, 
                        that.getParameterLists().get(i));
            }
            super.visit(that);
        }
        @Override
        public void visit(Tree.ClassDefinition that) {
            handleParamList(that, 0, 
                    that.getParameterList());
            super.visit(that);
        }        
    }
    
    private static class FindInvocationsVisitor extends Visitor {
        private Declaration declaration;
        private final Set<Tree.ArgumentList> results = 
                new HashSet<Tree.ArgumentList>();
        Set<Tree.ArgumentList> getResults() {
            return results;
        }
        private FindInvocationsVisitor(Declaration declaration) {
            this.declaration=declaration;
        }
        @Override
        public void visit(Tree.InvocationExpression that) {
            super.visit(that);
            Tree.Primary primary = that.getPrimary();
            if (primary instanceof Tree.MemberOrTypeExpression) {
                if (((Tree.MemberOrTypeExpression) primary).getDeclaration()
                        .equals(declaration)) {
                    Tree.PositionalArgumentList pal = 
                            that.getPositionalArgumentList();
                    if (pal!=null) {
                        results.add(pal);
                    }
                    Tree.NamedArgumentList nal = 
                            that.getNamedArgumentList();
                    if (nal!=null) {
                        results.add(nal);
                    }
                }
            }
        }
    }
    
    private static class FindArgumentsVisitor extends Visitor {
        private Declaration declaration;
        private final Set<Tree.MethodArgument> results = 
                new HashSet<Tree.MethodArgument>();
        Set<Tree.MethodArgument> getResults() {
            return results;
        }
        private FindArgumentsVisitor(Declaration declaration) {
            this.declaration=declaration;
        }
        @Override
        public void visit(Tree.MethodArgument that) {
            super.visit(that);
            Parameter p = that.getParameter();
            if (p!=null && p.getModel().equals(declaration)) {
                results.add(that);
            }
        }
    }

    private String newName;
        
    public String getNewName() {
        return newName;
    }
    
    public void setNewName(String newName) {
        this.newName = newName;
    }
    
    public CollectParametersRefactoring(ITextEditor editor) {
        super(editor);
        new FindParametersVisitor().visit(rootNode);
        if (declaration!=null) {
            newName = Character.toUpperCase(declaration.getName().charAt(0))
                    + declaration.getName().substring(1);
        }
    }
    
    @Override
    public boolean isEnabled() {
        return declaration!=null && 
                !parameters.isEmpty();
    }
    
    @Override
    int countReferences(Tree.CompilationUnit cu) {
        FindInvocationsVisitor frv = new FindInvocationsVisitor(declaration);
        FindRefinementsVisitor fdv = new FindRefinementsVisitor(declaration);
        FindArgumentsVisitor fav = new FindArgumentsVisitor(declaration);
        cu.visit(frv);
        cu.visit(fdv);
        cu.visit(fav);
        return frv.getResults().size() + 
                fdv.getDeclarationNodes().size() + 
                fav.getResults().size();
    }

    public String getName() {
        return "Collect Parameters";
    }

    public RefactoringStatus checkInitialConditions(IProgressMonitor pm)
            throws CoreException, 
                   OperationCanceledException {
        // Check parameters retrieved from editor context
        return new RefactoringStatus();
    }

    public RefactoringStatus checkFinalConditions(IProgressMonitor pm)
            throws CoreException, 
                   OperationCanceledException {
        return new RefactoringStatus();
    }

    public CompositeChange createChange(IProgressMonitor pm) 
            throws CoreException,
                   OperationCanceledException {
        List<PhasedUnit> units = getAllUnits();
        pm.beginTask(getName(), units.size());
        CompositeChange cc = new CompositeChange(getName());
        int i=0;
        for (PhasedUnit pu: units) {
            if (searchInFile(pu)) {
                TextFileChange tfc = newTextFileChange(pu);
                refactorInFile(tfc, cc, pu.getCompilationUnit());
                pm.worked(i++);
            }
        }
        if (searchInEditor()) {
            DocumentChange dc = newDocumentChange();
            refactorInFile(dc, cc, 
                    editor.getParseController().getRootNode());
            pm.worked(i++);
        }
        pm.done();
        return cc;
    }

    private void refactorInFile(final TextChange tfc, CompositeChange cc, 
            Tree.CompilationUnit root) {
        tfc.setEdit(new MultiTextEdit());
        if (declaration!=null) {
            String paramName = 
                    Character.toLowerCase(newName.charAt(0)) + newName.substring(1);
            FindInvocationsVisitor fiv = new FindInvocationsVisitor(declaration);
            root.visit(fiv);
            for (Tree.ArgumentList pal: fiv.getResults()) {
                refactorInvocation(tfc, paramName, pal);
            }
            FindRefinementsVisitor frv = new FindRefinementsVisitor(declaration);
            root.visit(frv);
            for (Tree.StatementOrArgument decNode: frv.getDeclarationNodes()) {
                refactorDeclaration(tfc, paramName, decNode);
            }
            FindArgumentsVisitor fav = new FindArgumentsVisitor(declaration);
            root.visit(fav);
            for (Tree.MethodArgument decNode: fav.getResults()) {
                refactorArgument(tfc, paramName, decNode);
            }
            createNewClassDeclaration(tfc, root);
        }
        if (tfc.getEdit().hasChildren()) {
            cc.add(tfc);
        }
    }

    private void refactorInvocation(TextChange tfc,
            String paramName, Tree.ArgumentList al) {
        if (al instanceof Tree.PositionalArgumentList) {
            List<Tree.PositionalArgument> pas = 
                    ((Tree.PositionalArgumentList) al).getPositionalArguments();
            if (pas.size()>firstParam) {
                Integer startIndex = pas.get(firstParam).getStartIndex();
                tfc.addEdit(new InsertEdit(startIndex, newName + "("));
                Integer stopIndex = pas.size()>lastParam?
                        pas.get(lastParam).getStopIndex()+1:
                            pas.get(pas.size()-1).getStopIndex()+1;
                tfc.addEdit(new InsertEdit(stopIndex, ")"));
            }
        }
        else if (al instanceof Tree.NamedArgumentList) {
            List<Tree.NamedArgument> nas = 
                    ((Tree.NamedArgumentList) al).getNamedArguments();
            List<Tree.NamedArgument> results = new ArrayList<Tree.NamedArgument>();
            for (Tree.NamedArgument na: nas) {
                if (models.contains(na.getParameter().getModel())) {
                    results.add(na);
                    tfc.addEdit(new DeleteEdit(getNodeStartOffset(na), getNodeLength(na)));
                }
            }
            if (!results.isEmpty()) {
                StringBuilder builder = new StringBuilder();
                builder.append(paramName).append(" = ").append(newName).append(" { ");
                for (Tree.NamedArgument na: results) {
                    builder.append(toString(na)).append(" ");
                }
                builder.append("};");
                tfc.addEdit(new InsertEdit(results.get(0).getStartIndex(), builder.toString()));
            }
        }
    }

    private void createNewClassDeclaration(final TextChange tfc,
            Tree.CompilationUnit root) {
        if (declaration.getUnit().equals(root.getUnit())) {
            String delim = getDefaultLineDelimiter(document);
            //TODO: for unshared declarations, we don't 
            //      need to make it toplevel, I guess
            int loc = findToplevelStatement(rootNode, node).getStartIndex();
            StringBuilder builder = new StringBuilder();
            if (declaration.isShared()) {
                builder.append("shared ");
            }
            builder.append("class ").append(newName).append("(");
            for (Tree.Parameter p: parameters) {
                builder.append("shared ").append(toString(p)).append(", ");
            }
            if (builder.toString().endsWith(", ")) {
                builder.setLength(builder.length()-2);
            }
            builder.append(") {}").append(delim).append(delim);
            tfc.addEdit(new InsertEdit(loc, builder.toString()));
        }
    }

    private void refactorArgument(TextChange tfc, String paramName,
            Tree.MethodArgument decNode) {
        refactorDec(tfc, paramName, 
                decNode.getParameterLists().get(parameterListIndex), 
                decNode.getBlock());
    }

    private void refactorDeclaration(TextChange tfc, String paramName, 
            Tree.StatementOrArgument decNode) {
        Tree.ParameterList pl;
        Node body;
        if (decNode instanceof Tree.MethodDefinition) {
            pl = ((Tree.AnyMethod) decNode).getParameterLists()
                    .get(parameterListIndex);
            body = ((Tree.MethodDefinition) decNode).getBlock();
        }
        else if (decNode instanceof Tree.MethodDeclaration) {
            pl = ((Tree.AnyMethod) decNode).getParameterLists()
                    .get(parameterListIndex);
            body = ((Tree.MethodDeclaration) decNode).getSpecifierExpression();
        }
        else if (decNode instanceof Tree.ClassDefinition) {
            pl = ((Tree.ClassDefinition) decNode).getParameterList();
            body = ((Tree.ClassDefinition) decNode).getClassBody();
        }
        else if (decNode instanceof Tree.SpecifierStatement) {
            Tree.Term bme = 
                    ((Tree.SpecifierStatement) decNode).getBaseMemberExpression();
            body = ((Tree.SpecifierStatement) decNode).getSpecifierExpression();
            if (bme instanceof Tree.ParameterizedExpression) {
                pl = ((Tree.ParameterizedExpression) bme)
                        .getParameterLists().get(parameterListIndex);
            }
            else {
                return;
            }
        }
        else {
            return;
        }
        refactorDec(tfc, paramName, pl, body);
    }
    
    private void refactorDec(final TextChange tfc, final String paramName,
            Tree.ParameterList pl, Node body) {
        List<Tree.Parameter> ps = pl.getParameters();
        final Set<MethodOrValue> params = new HashSet<MethodOrValue>();
        for (int i=firstParam; i<ps.size()&&i<=lastParam; i++) {
            params.add(ps.get(i).getParameterModel().getModel());
        }
        int startOffset = getNodeStartOffset(ps.get(firstParam));
        int endOffset = getNodeEndOffset(ps.get(lastParam));
        tfc.addEdit(new InsertEdit(startOffset, 
                newName + " " + paramName));
        tfc.addEdit(new DeleteEdit(startOffset, endOffset-startOffset));
        body.visit(new Visitor() {
            @Override
            public void visit(Tree.BaseMemberExpression that) {
                super.visit(that);
                if (params.contains(that.getDeclaration())) {
                    tfc.addEdit(new InsertEdit(that.getStartIndex(), 
                            paramName + "."));
                }
            }
        });
    }
    
}
