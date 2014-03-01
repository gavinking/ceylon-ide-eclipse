package com.redhat.ceylon.eclipse.code.resolve;

import java.util.List;

import com.redhat.ceylon.compiler.typechecker.context.PhasedUnit;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.Referenceable;
import com.redhat.ceylon.compiler.typechecker.model.Unit;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.DocLink;
import com.redhat.ceylon.eclipse.code.parse.CeylonParseController;
import com.redhat.ceylon.eclipse.core.model.CeylonBinaryUnit;
import com.redhat.ceylon.eclipse.core.model.CeylonUnit;
import com.redhat.ceylon.eclipse.core.model.EditedSourceFile;
import com.redhat.ceylon.eclipse.core.model.ExternalSourceFile;
import com.redhat.ceylon.eclipse.core.model.ICrossProjectReference;
import com.redhat.ceylon.eclipse.core.model.ProjectSourceFile;
import com.redhat.ceylon.eclipse.core.typechecker.ProjectPhasedUnit;
import com.redhat.ceylon.eclipse.util.FindDeclarationNodeVisitor;

public class CeylonReferenceResolver {

    public static Node getReferencedNode(Node node, 
            CeylonParseController controller) {
        return getReferencedNode(getReferencedModel(node), 
                controller);
    }

    public static Node getReferencedNode(Referenceable dec, 
            CeylonParseController controller) {
        return getReferencedNode(dec, getCompilationUnit(dec, controller));
    }

    public static Referenceable getReferencedModel(Node node) {
        if (node instanceof Tree.ImportPath) {
            return ((Tree.ImportPath) node).getModel();
        }
        else {
            Declaration dec = getReferencedDeclaration((Node) node);
            if (dec instanceof MethodOrValue && 
                    ((MethodOrValue) dec).isShortcutRefinement()) {
                dec = dec.getRefinedDeclaration();
            }
            return dec;
        }
    }

    public static Declaration getReferencedExplicitDeclaration(Node node, 
            Tree.CompilationUnit rn) {
        Declaration dec = getReferencedDeclaration(node);
        if (dec!=null && dec.getUnit().equals(node.getUnit())) {
            FindDeclarationNodeVisitor fdv = new FindDeclarationNodeVisitor(dec);
            fdv.visit(rn);
            Node decNode = fdv.getDeclarationNode();
            if (decNode instanceof Tree.Variable) {
                Tree.Variable var = (Tree.Variable) decNode;
                if (var.getType() instanceof Tree.SyntheticVariable) {
                    Tree.Term term = var.getSpecifierExpression()
                            .getExpression().getTerm();
                    return getReferencedExplicitDeclaration(term, rn);
                }
            }
        }
        return dec;
    }

    public static Declaration getReferencedDeclaration(Node node) {
        //NOTE: this must accept a null node, returning null!
        if (node instanceof Tree.MemberOrTypeExpression) {
            return ((Tree.MemberOrTypeExpression) node).getDeclaration();
        } 
        else if (node instanceof Tree.SimpleType) {
            return ((Tree.SimpleType) node).getDeclarationModel();
        } 
        else if (node instanceof Tree.ImportMemberOrType) {
            return ((Tree.ImportMemberOrType) node).getDeclarationModel();
        } 
        else if (node instanceof Tree.Declaration) {
            return ((Tree.Declaration) node).getDeclarationModel();
        } 
        else if (node instanceof Tree.NamedArgument) {
            Parameter p = ((Tree.NamedArgument) node).getParameter();
            return p==null ? null : p.getModel();
        }
        else if (node instanceof Tree.InitializerParameter) {
            Parameter p = ((Tree.InitializerParameter) node).getParameterModel();
            return  p==null ? null : p.getModel();
        }
        else if (node instanceof Tree.MetaLiteral) {
            return ((Tree.MetaLiteral) node).getDeclaration();
        }
        else if (node instanceof Tree.DocLink) {
            DocLink docLink = (Tree.DocLink) node;
            List<Declaration> qualified = docLink.getQualified();
            if (qualified!=null && !qualified.isEmpty()) {
                return qualified.get(qualified.size()-1);
            }
            else {
                return docLink.getBase();
            }
        }
        else {
            return null;
        }
    }

    public static Node getReferencedNode(Referenceable dec,
            Tree.CompilationUnit compilationUnit) {
        if (compilationUnit==null || dec==null) {
            return null;
        }
        else {
            FindReferencedNodeVisitor visitor = new FindReferencedNodeVisitor(dec);
            compilationUnit.visit(visitor);
            //System.out.println("referenced node: " + visitor.getDeclarationNode());
            return visitor.getDeclarationNode();
        }
    }
    
    public static Tree.CompilationUnit getCompilationUnit(Referenceable model,
            CeylonParseController cpc) {
        if (model==null) {
            return null;
        }
        else {
            Tree.CompilationUnit root = cpc==null ? 
                    null : cpc.getRootNode();            
            if (root!=null && root.getUnit()!=null && 
                    root.getUnit().equals(model.getUnit())) {
                return root;
            }
            else {
                Unit unit = model.getUnit();
                PhasedUnit pu = null; 
                if (unit instanceof ProjectSourceFile) {
                    pu = ((ProjectSourceFile) unit).getPhasedUnit();
                    // Here pu should never be null !
                }
                if (unit instanceof EditedSourceFile) {
                    pu = ((EditedSourceFile) unit).getPhasedUnit();
                    // Here pu should never be null !
                }
                
                if (unit instanceof ICrossProjectReference) {
                    ProjectPhasedUnit requiredProjectPhasedUnit = ((ICrossProjectReference) unit).getOriginalPhasedUnit();
                    if (requiredProjectPhasedUnit != null 
                            && requiredProjectPhasedUnit.isFullyTyped()) {
                        pu = requiredProjectPhasedUnit;
                    }
                    else {
                        System.err.println("ABNORMAL : cross reference with a null original PhasedUnit !");
                        pu = ((ICrossProjectReference) unit).getPhasedUnit();
                    }
                }
                
                if (pu == null && (unit instanceof ExternalSourceFile || 
                        unit instanceof CeylonBinaryUnit)) {
                    pu = ((CeylonUnit)unit).getPhasedUnit();
                }
                
                // TODO : When using binary ceylon archives, add a case here with
                //        unit instanceof CeylonBinaryUnit
                //        And perform the same sort of thing as for ExternalSourceFile :
                //           -> return the associated source PhasedUnit if any 
                
                if (pu!=null) {
                    return pu.getCompilationUnit();
                }
                return null;
            }
        }
    }

}
