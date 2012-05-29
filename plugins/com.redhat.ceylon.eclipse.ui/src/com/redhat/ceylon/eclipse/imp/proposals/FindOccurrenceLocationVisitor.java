package com.redhat.ceylon.eclipse.imp.proposals;

import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.EXPRESSION;
import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.EXTENDS;
import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.IMPORT;
import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.PARAMETER_LIST;
import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.SATISFIES;
import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.TYPE_ARGUMENT_LIST;
import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.TYPE_PARAMETER_LIST;
import static com.redhat.ceylon.eclipse.imp.proposals.OccurrenceLocation.UPPER_BOUND;

import com.redhat.ceylon.compiler.typechecker.tree.NaturalVisitor;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.QualifiedMemberOrTypeExpression;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.Term;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

class FindOccurrenceLocationVisitor extends Visitor
        implements NaturalVisitor {
    
    private Node node;
    
    private OccurrenceLocation occurrence;
    private boolean inTypeConstraint = false;
    
    FindOccurrenceLocationVisitor(Node node) {
        this.node = node;
    }
    
    OccurrenceLocation getOccurrenceLocation() {
        return occurrence;
    }
    
    @Override
    public void visitAny(Node that) {
        if (inBounds(that))  {
            super.visitAny(that);
        }
        //otherwise, as a performance optimization
        //don't go any further down this branch
    }
    
    public void visit(Tree.TypeConstraint that) {
        inTypeConstraint=true;
        super.visit(that);
        inTypeConstraint=false;
    }
    
    public void visit(Tree.ImportMemberOrTypeList that) {
        if (inBounds(that)) {
            occurrence = IMPORT;
        }
        super.visit(that);
    }
    
    public void visit(Tree.ExtendedType that) {
        if (inBounds(that)) {
            occurrence = EXTENDS;
        }
        super.visit(that);
    }
    
    public void visit(Tree.SatisfiedTypes that) {
        if (inBounds(that)) {
            occurrence = inTypeConstraint? 
                    UPPER_BOUND : SATISFIES;
        }
        super.visit(that);
    }
        
    @Override
    public void visit(Tree.BinaryOperatorExpression that) {
        Term right = that.getRightTerm();
        if (right==null) {
            right = that;
        }
        Term left = that.getLeftTerm();
        if (left==null) {
            left = that;
        }
        if (inBounds(left, right)) {
            occurrence = EXPRESSION;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.UnaryOperatorExpression that) {
        Term term = that.getTerm();
        if (term==null) {
            term = that;
        }
        if (inBounds(that, term) || inBounds(term, that)) {
            occurrence = EXPRESSION;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.ParameterList that) {
        if (inBounds(that)) {
            occurrence = PARAMETER_LIST;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.TypeParameterList that) {
        if (inBounds(that)) {
            occurrence = TYPE_PARAMETER_LIST;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.ArgumentList that) {
        if (inBounds(that)) {
            occurrence = EXPRESSION;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.TypeArgumentList that) {
        if (inBounds(that)) {
            occurrence = TYPE_ARGUMENT_LIST;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(QualifiedMemberOrTypeExpression that) {
        if (inBounds(that.getMemberOperator(), that.getIdentifier())) {
            occurrence = EXPRESSION;
        }
        else {
            super.visit(that);
        }
    }
    
    @Override
    public void visit(Tree.SyntheticSpecifierExpression that) {
        ((Tree.InvocationExpression) that.getExpression().getTerm())
                .getNamedArgumentList().visit(this);
    }
    
    @Override
    public void visit(Tree.SyntheticBlock that) {
        ((Tree.InvocationExpression) ((Tree.Return) that.getStatements().get(0))
                .getExpression().getTerm())
                .getNamedArgumentList().visit(this);
    }
        
    @Override
    public void visit(Tree.Declaration that) {
        if (inBounds(that) && 
                !(that instanceof Tree.Parameter)) {
            occurrence=null;
        }
        super.visit(that);
    }
    
    private boolean inBounds(Node that) {
        return inBounds(that, that);
    }
    
    private boolean inBounds(Node left, Node right) {
        if (left==null) return false;
        if (right==null) right=left;
        Integer startIndex = left.getStartIndex();
        Integer stopIndex = right.getStopIndex();
        return startIndex!=null && stopIndex!=null &&
                startIndex <= node.getStartIndex() && 
                stopIndex >= node.getStopIndex();
    }
    
}