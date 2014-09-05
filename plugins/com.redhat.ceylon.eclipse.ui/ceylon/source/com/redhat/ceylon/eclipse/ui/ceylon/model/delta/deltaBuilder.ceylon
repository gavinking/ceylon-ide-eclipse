import com.redhat.ceylon.compiler.typechecker.context {
    PhasedUnit
}
import com.redhat.ceylon.compiler.typechecker.analyzer {
    ModuleManager {
        moduleDescriptorFileName=\iMODULE_FILE,
        packageDescriptorFileName=\iPACKAGE_FILE
    }
}
import com.redhat.ceylon.compiler.typechecker.tree {
    Ast = Tree,
    AstAbstractNode=Node,
    Visitor,
    VisitorAdaptor
}
import ceylon.collection {
    HashSet,
    HashMap,
    MutableMap,
    MutableSet,
    ArrayList,
    MutableList,
    StringBuilder
}
import com.redhat.ceylon.compiler.typechecker.model {
    ModelDeclaration = Declaration,
    ModelUnit = Unit,
    ProducedType,
    Method
}
import ceylon.interop.java {
    CeylonIterable,
    javaClassFromInstance,
    CeylonIterator
}

"Builds a [[model delta|AbstractDelta]] that describes the model differences 
 between a [[reference PhasedUnit|buildDeltas.referencePhasedUnit]] 
 and a [[changed PhasedUnit|buildDeltas.changedPhasedUnit]]
 related to the same file.
 
 In case of a regular compilation unit(not a descriptor), only the 
 model elements visibile _outside_ the unit are considered.
 "
shared CompilationUnitDelta buildDeltas(
    "Referenced phased unit, typically of central Ceylon model"
    PhasedUnit referencePhasedUnit,
    "Changed phased unit, typically a just-saved working copy"
    PhasedUnit changedPhasedUnit,
    "Listener that registers the detail of every structural node comparisons"
    NodeComparisonListener? nodeComparisonListener = null) {
    
    assert (exists unitFile = referencePhasedUnit.unitFile);
    if (unitFile.name == moduleDescriptorFileName) {
        return buildModuleDescriptorDeltas(referencePhasedUnit, changedPhasedUnit, nodeComparisonListener);
    }
    
    if (unitFile.name == packageDescriptorFileName) {
        return buildPackageDescriptorDeltas(referencePhasedUnit, changedPhasedUnit, nodeComparisonListener);
    }
    
    return buildCompilationUnitDeltas(referencePhasedUnit, changedPhasedUnit, nodeComparisonListener);
}

ModuleDescriptorDelta buildModuleDescriptorDeltas(PhasedUnit referencePhasedUnit, PhasedUnit changedPhasedUnit, NodeComparisonListener? nodeComparisonListener) => nothing;

PackageDescriptorDelta buildPackageDescriptorDeltas(PhasedUnit referencePhasedUnit, PhasedUnit changedPhasedUnit, NodeComparisonListener? nodeComparisonListener) => nothing;

RegularCompilationUnitDelta buildCompilationUnitDeltas(PhasedUnit referencePhasedUnit, PhasedUnit changedPhasedUnit, NodeComparisonListener? nodeComparisonListener) {
    value builder = RegularCompilationUnitDeltaBuilder(referencePhasedUnit.compilationUnit, changedPhasedUnit.compilationUnit, nodeComparisonListener);
    return builder.buildDelta();
}

alias AstNode => <Ast.Declaration | Ast.CompilationUnit | Ast.ModuleDescriptor | Ast.ImportModule | Ast.PackageDescriptor> & AstAbstractNode;

abstract class DeltaBuilder(AstNode oldNode, AstNode? newNode) {
    
    shared formal [AstNode*] getChildren(AstNode astNode);
    shared formal AbstractDelta buildDelta();
        
    shared formal void addRemovedChange();
    shared formal void calculateStructuralChanges();
    shared formal void manageChildDelta(AstNode oldChild, AstNode? newChild);
    shared formal void addMemberAddedChange(AstNode newChild);
    
    shared default void recurse() {
        if (newNode is Null) {
            addRemovedChange();
            return;
        }
        assert(exists newNode);
        
        calculateStructuralChanges();
        
        [AstNode*] oldChildren = getChildren(oldNode);
        [AstNode*] newChildren = getChildren(newNode);
        
        if (newChildren nonempty || oldChildren nonempty) {
            value allChildrenSet = HashSet<String>();

            function toMap([AstNode*] children) {
                MutableMap<String,AstNode>? childrenSet;
                if (nonempty children) {
                    childrenSet = HashMap<String,AstNode>();
                    assert (exists childrenSet);
                    for (child in children) {
                        String childKey;
                        switch (child)
                        case(is Ast.Declaration) {
                            value model = child.declarationModel;
                            childKey = "``javaClassFromInstance(model).simpleName``[``model.qualifiedNameString``]";
                        }
                        case(is Ast.ModuleDescriptor) {
                            childKey = child.unit.fullPath;
                        }
                        case(is Ast.PackageDescriptor) {
                            childKey = child.unit.fullPath;
                        }
                        case(is Ast.CompilationUnit) {
                            childKey = child.unit.fullPath;
                        }
                        case(is Ast.ImportModule) {
                            childKey = "/".join {child.quotedLiteral.string, child.version.string};
                        }
                        
                        allChildrenSet.add(childKey);
                        childrenSet.put(childKey, child);
                    }
                } else {
                    childrenSet = null;
                }
                return childrenSet;
            }
            
            MutableMap<String,AstNode>? oldChildrenSet = toMap(oldChildren);
            MutableMap<String,AstNode>? newChildrenSet = toMap(newChildren);
            
            for (keyChild in allChildrenSet) {
                value oldChild = oldChildrenSet?.get(keyChild) else null;
                value newChild = newChildrenSet?.get(keyChild) else null;
                if (exists oldChild) {
                    manageChildDelta(oldChild, newChild);
                } else {
                    assert(exists newChild);
                    addMemberAddedChange(newChild);
                }
            }
        }
    }
}

class RegularCompilationUnitDeltaBuilder(Ast.CompilationUnit oldNode, Ast.CompilationUnit newNode, NodeComparisonListener? nodeComparisonListener)
        extends DeltaBuilder(oldNode, newNode) {

    variable value changes = ArrayList<RegularCompilationUnitDelta.PossibleChange>();
    variable value childrenDeltas = ArrayList<TopLevelDeclarationDelta>();
    
    shared actual RegularCompilationUnitDelta buildDelta() {
        recurse();
        object delta satisfies RegularCompilationUnitDelta {
            changedElement => oldNode.unit;
            shared actual {RegularCompilationUnitDelta.PossibleChange*} changes => outer.changes;
            shared actual {TopLevelDeclarationDelta*} childrenDeltas => outer.childrenDeltas;
            shared actual Boolean equals(Object that) => (super of AbstractDelta).equals(that);
        }
        return delta;
    }
    
    shared actual void manageChildDelta(AstNode oldChild, AstNode? newChild) {
        assert(is Ast.Declaration oldChild, 
                is Ast.Declaration? newChild, 
                oldChild.declarationModel.toplevel);
        value builder = TopLevelDeclarationDeltaBuilder(oldChild, newChild, nodeComparisonListener);
        value delta = builder.buildDelta();
        if (delta.changes.empty && delta.childrenDeltas.empty) {
            return;
        }
        childrenDeltas.add(delta);
    }
    
    shared actual void addMemberAddedChange(AstNode newChild) {
        assert(is Ast.Declaration newChild, newChild.declarationModel.toplevel);
        changes.add(TopLevelDeclarationAdded(newChild.declarationModel.nameAsString, newChild.declarationModel.shared));
    }
    
    shared actual void addRemovedChange() {
        "A compilation unit cannot be removed from a PhasedUnit"
        assert(false);
    }
    
    shared actual void calculateStructuralChanges() {
        // No structural change can occur within a compilation unit
        // Well ... is it true ? What about the initialization order of toplevel declarations ?
        // TODO consider the declaration order of top-levels inside a compilation unit as a structural change ?
        // TODO extend this question to the order of declaration inside the initialization section : 
        //      we should check that the initialization section of a class is not changed
        // TODO more generally : where is the order of declaration important ? and when an order change can trigger compilation errors ?
        
    }
    
    shared actual Ast.Declaration[] getChildren(AstNode astNode) {
        value children = ArrayList<Ast.Declaration>(5);
        object visitor extends Visitor() {
            shared actual void visit(Ast.Declaration declaration) {
                assert(declaration.declarationModel.toplevel);
                children.add(declaration);
            }
        }
        astNode.visitChildren(visitor);
        return children.sequence();
    }
}
    
shared alias NodeComparisonListener => Anything(String?, String?, ModelDeclaration, String);
    
Boolean hasStructuralChanges(Ast.Declaration oldAstDeclaration, Ast.Declaration newAstDeclaration, NodeComparisonListener? nodeComparisonListener) {
    function lookForChanges<NodeType>(Boolean changed(NodeType oldNode, NodeType newNode))
            given NodeType satisfies Ast.Declaration {
        if (is NodeType oldAstDeclaration) {
            if (is NodeType newAstDeclaration) {
                if (changed(oldAstDeclaration, newAstDeclaration)) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }
    
    Boolean nodeChanged(AstAbstractNode? oldNode, AstAbstractNode? newNode, String declarationMemberName) {
        
        class NodeSigner(AstAbstractNode node) extends VisitorAdaptor() {
            variable value builder = StringBuilder();
            shared String signature() {
                node.visit(this);
                return builder.string;
            }
            
            shared actual void visitType(Ast.Type node) {
                builder.append("Type[");
                if (exists type = node.typeModel) {
                    builder.append(type.producedTypeName);
                }
                builder.append("]");
            }

            shared actual void visitAny(AstAbstractNode node) {
                builder.append("``node.nodeType``[");
                if (node.children.size() > 0) {
                    super.visitAny(node);
                } else {
                    builder.append(node.text);
                }
                builder.append("]");
            }
            
            shared actual void visitIdentifier(Ast.Identifier node) {
                if (is Method method = node.scope,
                    method.parameter) {
                    // parameters of a method functional parameter are not 
                    // part of the externally visible structure of the outer method
                    return;
                }
                
                visitAny(node);
            }
        }
        Boolean changed;
        if(exists oldNode, exists newNode) {
            String oldSignature = NodeSigner(oldNode).signature();
            String newSignature = NodeSigner(newNode).signature();
            if (exists nodeComparisonListener) {
                nodeComparisonListener(oldSignature, newSignature, oldAstDeclaration.declarationModel, declarationMemberName);
            }
            changed = oldSignature != newSignature;
        } else {
            changed = !(oldNode is Null && newNode is Null);
            if (exists nodeComparisonListener) {
                variable String? oldSignature = null;
                variable String? newSignature = null;
                if (exists oldNode) {
                    oldSignature = NodeSigner(oldNode).signature();
                }
                if (exists newNode) {
                    newSignature = NodeSigner(newNode).signature();
                }
                nodeComparisonListener(oldSignature, newSignature, oldAstDeclaration.declarationModel, declarationMemberName);
            }
        }
        return changed;
    }
    
    return lookForChanges {
        function changed(Ast.Declaration oldNode, Ast.Declaration newNode) {
            assert(exists oldDeclaration = oldNode.declarationModel);
            assert(exists newDeclaration = newNode.declarationModel);
            function annotations(ModelDeclaration declaration) {
                return HashSet {
                    for (annotation in CeylonIterable(declaration.annotations)) if (annotation.name != "shared") annotation.string
                };
            }
            return any {
                annotations(oldDeclaration) != annotations(newDeclaration),
                lookForChanges {
                    function changed(Ast.TypedDeclaration oldTyped, Ast.TypedDeclaration newTyped) {
                        return any {
                            nodeChanged(oldTyped.type, newTyped.type, "type"),
                            lookForChanges {
                                function changed(Ast.AnyMethod oldMethod, Ast.AnyMethod newMethod) {
                                    return any {
                                        nodeChanged(oldMethod.typeConstraintList, newMethod.typeConstraintList, "typeConstraintList"),
                                        nodeChanged(oldMethod.typeParameterList, newMethod.typeParameterList, "typeParameterList"),
                                        oldMethod.parameterLists.size() != newMethod.parameterLists.size(),
                                        anyPair {
                                            firstIterable => CeylonIterable(oldMethod.parameterLists);
                                            secondIterable => CeylonIterable(newMethod.parameterLists);
                                            Boolean selecting(Ast.ParameterList oldParamList, Ast.ParameterList newParamlist) {
                                                return nodeChanged(oldParamList, newParamlist, "parameterLists");
                                            }
                                        }
                                    };
                                }
                            },
                            lookForChanges {
                                function changed(Ast.ObjectDefinition oldObject, Ast.ObjectDefinition newObject) {
                                    return any {
                                        nodeChanged(oldObject.extendedType, newObject.extendedType, "extendedType"),
                                        nodeChanged(oldObject.satisfiedTypes, newObject.satisfiedTypes, "satisfiedTypes")
                                    };
                                }
                            },
                            lookForChanges {
                                function changed(Ast.Variable oldVariable, Ast.Variable newVariable) {
                                    return any {
                                        oldVariable.parameterLists.size() != oldVariable.parameterLists.size(),
                                        anyPair {
                                            firstIterable => CeylonIterable(oldVariable.parameterLists);
                                            secondIterable => CeylonIterable(newVariable.parameterLists);
                                            Boolean selecting(Ast.ParameterList oldParamList, Ast.ParameterList newParamlist) {
                                                return nodeChanged(oldParamList, newParamlist,"parameterLists");
                                            }
                                        }
                                    };
                                }
                            }
                        };
                    }
                },
                lookForChanges {
                    function changed(Ast.TypeDeclaration oldType, Ast.TypeDeclaration newType) {
                        return any {
                            nodeChanged(oldType.caseTypes, newType.caseTypes, "caseTypes"),
                            nodeChanged(oldType.satisfiedTypes, newType.satisfiedTypes, "satisfiedTypes"),
                            nodeChanged(oldType.typeParameterList, newType.typeParameterList, "typeParameterList"),
                            lookForChanges {
                                function changed(Ast.TypeParameterDeclaration oldTypeParameter, Ast.TypeParameterDeclaration newTypeParameter) {
                                    return any {
                                        nodeChanged(oldTypeParameter.typeSpecifier, newTypeParameter.typeSpecifier, "typeSpecifier"),
                                        nodeChanged(oldTypeParameter.typeVariance, newTypeParameter.typeVariance, "typeVariance")
                                    };
                                }
                            }
                        };
                    }
                }
            };
        }
    };
}

abstract class DeclarationDeltaBuilder(Ast.Declaration oldNode, Ast.Declaration? newNode, NodeComparisonListener? nodeComparisonListener)
        of TopLevelDeclarationDeltaBuilder | NestedDeclarationDeltaBuilder
        extends DeltaBuilder(oldNode, newNode) {

    shared variable MutableList<NestedDeclarationDelta> childrenDeltas = ArrayList<NestedDeclarationDelta>();
    shared formal {ImpactingChange*} changes;

    shared actual void manageChildDelta(AstNode oldChild, AstNode? newChild) {
        assert(is Ast.Declaration oldChild, 
            is Ast.Declaration? newChild, 
            ! oldChild.declarationModel.toplevel);
        value builder = NestedDeclarationDeltaBuilder(oldChild, newChild, nodeComparisonListener);
        value delta = builder.buildDelta();
        if (delta.changes.empty && delta.childrenDeltas.empty) {
            return;
        }
        childrenDeltas.add(delta);
    }
    
    shared actual Ast.Declaration[] getChildren(AstNode astNode) {
        value children = ArrayList<Ast.Declaration>(5);
        object visitor extends Visitor() {
            shared actual void visit(Ast.Declaration declaration) {
                assert(!declaration.declarationModel.toplevel);
                if (declaration.declarationModel.shared) {
                    children.add(declaration);
                }
            }
        }
        astNode.visitChildren(visitor);
        return children.sequence();
    }
}

class TopLevelDeclarationDeltaBuilder(Ast.Declaration oldNode, Ast.Declaration? newNode, NodeComparisonListener? nodeComparisonListener)
        extends DeclarationDeltaBuilder(oldNode, newNode, nodeComparisonListener) {
    
    variable value _changes = ArrayList<TopLevelDeclarationDelta.PossibleChange>();
    shared actual {TopLevelDeclarationDelta.PossibleChange*} changes => _changes;
    
    shared actual TopLevelDeclarationDelta buildDelta() {
        recurse();
        object delta satisfies TopLevelDeclarationDelta {
            changedElement => oldNode.declarationModel;
            shared actual {TopLevelDeclarationDelta.PossibleChange*} changes => outer.changes;
            shared actual {NestedDeclarationDelta*} childrenDeltas => outer.childrenDeltas;
            shared actual Boolean equals(Object that) => (super of AbstractDelta).equals(that);
        }
        return delta;
    }
    
    shared actual void addMemberAddedChange(AstNode newChild) {
        assert(is Ast.Declaration newChild);
        _changes.add(DeclarationMemberAdded(newChild.declarationModel.nameAsString));
    }
    
    shared actual void addRemovedChange() {
        _changes.add(removed);
    }
    
    shared actual void calculateStructuralChanges() {
        assert(exists newNode);

        assert(exists oldDeclaration = oldNode.declarationModel);
        assert(exists newDeclaration = newNode.declarationModel);
        if (oldDeclaration.shared && !newDeclaration.shared) {
            _changes.add(madeInvisibleOutsideScope);
        }
        if (!oldDeclaration.shared && newDeclaration.shared) {
            _changes.add(madeVisibleOutsideScope);
        }
        
        if (hasStructuralChanges(oldNode, newNode, nodeComparisonListener)) {
            _changes.add(structuralChange);
        }
    }
}
    
    
class NestedDeclarationDeltaBuilder(Ast.Declaration oldNode, Ast.Declaration? newNode, NodeComparisonListener? nodeComparisonListener)
        extends DeclarationDeltaBuilder(oldNode, newNode, nodeComparisonListener) {
    
    variable value _changes = ArrayList<NestedDeclarationDelta.PossibleChange>();
    shared actual {NestedDeclarationDelta.PossibleChange*} changes => _changes;
    
    shared actual NestedDeclarationDelta buildDelta() {
        recurse();
        object delta satisfies NestedDeclarationDelta {
            changedElement => oldNode.declarationModel;
            shared actual {NestedDeclarationDelta.PossibleChange*} changes => outer.changes;
            shared actual {NestedDeclarationDelta*} childrenDeltas => outer.childrenDeltas;
            shared actual Boolean equals(Object that) => (super of AbstractDelta).equals(that);
            
        }
        return delta;
    }
    
    shared actual void addMemberAddedChange(AstNode newChild) {
        assert(is Ast.Declaration newChild);
        _changes.add(DeclarationMemberAdded(newChild.declarationModel.nameAsString));
    }
    
    shared actual void addRemovedChange() {
        _changes.add(removed);
    }
    
    shared actual void calculateStructuralChanges() {
        assert(exists newNode);
        if (hasStructuralChanges(oldNode, newNode, nodeComparisonListener)) {
            _changes.add(structuralChange);
        }
    }
}
