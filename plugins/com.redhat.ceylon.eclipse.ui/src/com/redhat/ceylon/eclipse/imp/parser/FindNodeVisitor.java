package com.redhat.ceylon.eclipse.imp.parser;

import com.redhat.ceylon.compiler.typechecker.tree.NaturalVisitor;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.QualifiedMemberOrTypeExpression;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.Term;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

class FindNodeVisitor extends Visitor
        implements NaturalVisitor {
    
    FindNodeVisitor(int fStartOffset, int fEndOffset) {
        this.startOffset = fStartOffset;
        this.endOffset = fEndOffset;
    }
    
    private Node node;
    private int startOffset;
    private int endOffset;
    
    public Node getNode() {
        return node;
    }
    
    public void visit(Tree.ImportMemberOrTypeList that) {
        super.visit(that);
    }
    
    public void visit(Tree.ExtendedType that) {
        super.visit(that);
    }
    
    public void visit(Tree.SatisfiedTypes that) {
        super.visit(that);
    }
    
    @Override
    public void visitAny(Node that) {
        if (inBounds(that))  {
            node=that;
            super.visitAny(that);
        }
        //otherwise, as a performance optimization
        //don't go any further down this branch
    }
    
    @Override
    public void visit(Tree.ImportPath that) {
        if (inBounds(that)) {
            node = that;
        }
        else {
            super.visit(that);
        }
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
            node=that;
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
            node=that;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.ParameterList that) {
        if (inBounds(that)) {
            node=that;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.TypeParameterList that) {
        if (inBounds(that)) {
            node=that;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.ArgumentList that) {
        if (inBounds(that)) {
            node=that;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(Tree.TypeArgumentList that) {
        if (inBounds(that)) {
            node=that;
        }
        super.visit(that);
    }
    
    @Override
    public void visit(QualifiedMemberOrTypeExpression that) {
        if (inBounds(that.getMemberOperator(), that.getIdentifier())) {
            node=that;
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
    public void visit(Tree.StaticMemberOrTypeExpression that) {
        if (inBounds(that.getIdentifier())) {
            node = that;
            //Note: we can't be sure that this is "really"
            //      an EXPRESSION!
        }
        else {
            super.visit(that);
        }
    }
    
    @Override
    public void visit(Tree.SimpleType that) {
        if (inBounds(that.getIdentifier())) {
            node = that;
        }
        else {
            super.visit(that);
        }
    }
    
    @Override
    public void visit(Tree.ImportMemberOrType that) {
        if (inBounds(that.getIdentifier())) {
            node = that;
        }
        else {
            super.visit(that);
        }
    }
    
    //TODO: do we need to do this and the same thing for 
    //      Conditions with synthetic variables?
    /*@Override
    public void visit(Tree.IsCase that) {
        if (node!=that && inBounds(that)) {
            node=that;
        }
        if (that.getType()!=null) {
            that.getType().visit(this);
        }
        super.visit(that);
    }*/
    
    @Override
    public void visit(Tree.Declaration that) {
        if (inBounds(that.getIdentifier())) {
            node = that;
        }
        else {
            super.visit(that);
        }
    }
    
    @Override
    public void visit(Tree.NamedArgument that) {
        if (inBounds(that.getIdentifier())) {
            node = that;
        }
        else {
            super.visit(that);
        }
    }
    
    private boolean inBounds(Node that) {
        return inBounds(that, that);
    }
    
    private boolean inBounds(Node left, Node right) {
        if (left==null) return false;
        if (right==null) right=left;
        Integer tokenStartIndex = left.getStartIndex();
        Integer tokenStopIndex = right.getStopIndex();
        /*Token endToken = right.getEndToken();
        if (endToken!=null && (endToken.getType()==CeylonLexer.SEMICOLON ||
                endToken.getType()==CeylonLexer.RBRACE)) {
            tokenStopIndex--;
        }*/
        return tokenStartIndex!=null && tokenStopIndex!=null &&
                tokenStartIndex <= startOffset && 
                tokenStopIndex+1 >= endOffset;
    }
    
}