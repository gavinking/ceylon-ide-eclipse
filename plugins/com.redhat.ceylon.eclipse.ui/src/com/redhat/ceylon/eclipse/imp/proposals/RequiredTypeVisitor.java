package com.redhat.ceylon.eclipse.imp.proposals;

import java.util.List;

import com.redhat.ceylon.compiler.typechecker.model.Functional;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ProducedReference;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.tree.NaturalVisitor;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.NamedArgumentList;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.PositionalArgumentList;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

class RequiredTypeVisitor extends Visitor 
        implements NaturalVisitor {
    
    private Node node;
    private ProducedType requiredType = null;
    private ProducedType finalResult = null;
    private ProducedReference namedArgTarget = null;
    
    ProducedType getType() {
        return finalResult;
    }
    
    RequiredTypeVisitor(Node node) {
        this.node = node;
    }
    
    @Override
    public void visitAny(Node that) {
        if (node==that) {
            finalResult=requiredType;  
        }
        super.visitAny(that);
    }
    
    @Override
    public void visit(Tree.InvocationExpression that) {
        ProducedType ort = requiredType;
        ProducedReference onat = namedArgTarget;
        PositionalArgumentList pal = that.getPositionalArgumentList();
        if (pal!=null) {
            int pos = pal.getPositionalArguments().size();
            ProducedReference pr = getTarget(that);
            if (pr!=null) {
                List<Parameter> params = getParameters(pr);
                if (params.size()>pos) {
                    Parameter param = params.get(pos);
                    requiredType = pr.getTypedParameter(param).getType();
                    if (param.isSequenced()) {
                        requiredType = that.getUnit().getElementType(requiredType);
                    }
                }
            }
        }
        NamedArgumentList nal = that.getNamedArgumentList();
        if (nal!=null) {
            namedArgTarget = getTarget(that);
            if (namedArgTarget!=null) {
                List<Parameter> params = getParameters(namedArgTarget);
                Parameter param = params.get(params.size()-1);
                if (param.isSequenced()) {
                    requiredType = namedArgTarget.getTypedParameter(param).getType();
                    requiredType = that.getUnit().getElementType(requiredType);
                }
            }
        }
        super.visit(that);
        requiredType = ort;
        namedArgTarget = onat;
    }

    private static ProducedReference getTarget(Tree.InvocationExpression that) {
        Tree.Primary p = that.getPrimary();
        if (p instanceof Tree.MemberOrTypeExpression) {
            return ((Tree.MemberOrTypeExpression) p).getTarget();
        }
        else {
            return null;
        }
    }
    
    private static List<Parameter> getParameters(ProducedReference pr) {
        return ((Functional) pr.getDeclaration()).getParameterLists()
                .get(0).getParameters();
    }
    
    @Override
    public void visit(Tree.SpecifiedArgument that) {
        ProducedType ort = requiredType;
        Parameter p = that.getParameter();
        if (p!=null) {
            if (namedArgTarget!=null) {
                requiredType = namedArgTarget.getTypedParameter(p).getType();
            }
            else {
                requiredType = p.getType();            
            }
        }
        super.visit(that);
        requiredType = ort;
    }
    
    @Override
    public void visit(Tree.SpecifierStatement that) {
        ProducedType ort = requiredType;
        requiredType = that.getBaseMemberExpression().getTypeModel();
        super.visit(that);
        requiredType = ort;
    }
    
    @Override
    public void visit(Tree.AttributeDeclaration that) {
        ProducedType ort = requiredType;
        requiredType = that.getType().getTypeModel();
        super.visit(that);
        requiredType = ort;
    }
    
    @Override
    public void visit(Tree.AssignOp that) {
        ProducedType ort = requiredType;
        requiredType = that.getLeftTerm().getTypeModel();
        super.visit(that);
        requiredType = ort;
    }
    
    @Override
    public void visit(Tree.Return that) {
        ProducedType ort = requiredType;
        requiredType = CeylonContentProposer.type(that.getDeclaration());
        super.visit(that);
        requiredType = ort;
    }
    
}