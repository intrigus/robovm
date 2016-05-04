/*
 * Copyright (C) 2015 RoboVM AB
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/gpl-2.0.html>.
 */
package org.robovm.compiler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.robovm.compiler.clazz.Clazz;
import org.robovm.compiler.clazz.ClazzInfo;
import org.robovm.compiler.clazz.Dependency;
import org.robovm.compiler.clazz.InvokeMethodDependency;
import org.robovm.compiler.clazz.MethodDependency;
import org.robovm.compiler.clazz.MethodInfo;
import org.robovm.compiler.clazz.SuperMethodDependency;
import org.robovm.compiler.config.Config.TreeShakerMode;

/**
 * Used to build a graph of dependencies between classes and methods. By
 * traversing this graph the compiler can determine the minimum set of classes
 * that need to be compiled in to the final binary given a specific
 * {@link TreeShakerMode}.
 */
public class DependencyGraph {

    /**
     * Root {@link Node}s used as starting points when traversing reachable
     * nodes.
     */
    private final Set<ClassNode> roots = new HashSet<>();
    /**
     * {@link Node}s for classes added using {@link #add(Clazz, boolean)}.
     */
    private final Map<String, ClassNode> classNodes = new HashMap<>();
    /**
     * {@link Node}s for methods.
     */
    private final Map<String, MethodNode> methodNodes = new HashMap<>();
    /**
     * Used to cache reachable nodes between calls to
     * {@link #findReachableClasses(TreeShakerMode)} /
     * {@link #findReachableMethods(TreeShakerMode)} when no call to
     * {@link #add(Clazz, boolean)} has been done in between.
     */
    private final Set<Node> reachableNodes = new HashSet<>();

    private final TreeShakerMode treeShakerMode;

    public DependencyGraph(TreeShakerMode treeShakerMode) {
        this.treeShakerMode = treeShakerMode;
    }

    /**
     * Adds the specified {@link Clazz} to the graph after it has been compiled.
     * If {@code root == true} the class will be added to the root set and it as
     * well as its methods will always be reachable.
     */
    public void add(Clazz clazz, boolean root) {
        reachableNodes.clear();

        ClassNode classNode = getClassNode(clazz.getInternalName());
        if (root) {
            roots.add(classNode);
        }

        ClazzInfo ci = clazz.getClazzInfo();

        for (Dependency dep : ci.getDependencies()) {
            if (dep instanceof InvokeMethodDependency) {
                InvokeMethodDependency mdep = (InvokeMethodDependency) dep;
                classNode.addEgde(getMethodNode(mdep), mdep.isWeak());
            } else if (dep instanceof SuperMethodDependency) {
                SuperMethodDependency mdep = (SuperMethodDependency) dep;
                classNode.addEgde(getMethodNode(mdep), mdep.isWeak());
            } else {
                classNode.addEgde(getClassNode(dep.getClassName()), dep.isWeak());
            }
        }

        for (MethodInfo mi : ci.getMethods()) {
            boolean strong = root
                    // Keep callback methods
                    || mi.isCallback()
                    // Keep class initializers
                    || (mi.isStatic() && "<clinit>".equals(mi.getName()) && "()V".equals(mi.getDesc()))
                    // Keep the values() method generated by the Java compiler
                    // in enum classes
                    || (ci.isEnum() && mi.isStatic() && "values".equals(mi.getName()) && mi.getDesc().equals(
                            "()[L" + clazz.getInternalName() + ";"))
                    // Keep the sizeOf() method generated by the RoboVM compiler
                    // in Struct classes
                    || (ci.isStruct() && mi.isStatic() && "sizeOf".equals(mi.getName()) && "()I".equals(mi.getDesc()));

            MethodNode methodNode = getMethodNode(clazz, mi);
            classNode.addEgde(methodNode, !strong);
            methodNode.addEgde(classNode, false);

            for (Dependency dep : mi.getDependencies()) {
                if (dep instanceof InvokeMethodDependency) {
                    InvokeMethodDependency mdep = (InvokeMethodDependency) dep;
                    methodNode.addEgde(getMethodNode(mdep), mdep.isWeak());
                } else if (dep instanceof SuperMethodDependency) {
                    // Reverse the dependency so that the method is strongly
                    // linked if the super method is invoked.
                    SuperMethodDependency mdep = (SuperMethodDependency) dep;
                    getMethodNode(mdep).addEgde(methodNode, false);
                } else {
                    methodNode.addEgde(getClassNode(dep.getClassName()), dep.isWeak());
                }
            }
        }
    }

    private ClassNode getClassNode(String className) {
        ClassNode node = classNodes.get(className);
        if (node == null) {
            node = new ClassNode(className);
            classNodes.put(className, node);
        }
        return node;
    }

    private MethodNode getMethodNode(String owner, String name, String desc, boolean weaklyLinked,
            boolean stronglyLinked) {
        String key = owner + "." + name + desc;
        MethodNode node = methodNodes.get(key);
        if (node == null) {
            node = new MethodNode(owner, name, desc, weaklyLinked, stronglyLinked);
            methodNodes.put(key, node);
        } else {
            if (weaklyLinked) {
                node.weaklyLinked = true;
            }
            if (stronglyLinked) {
                node.stronglyLinked = true;
            }
        }
        return node;
    }

    private MethodNode getMethodNode(Clazz clazz, MethodInfo mi) {
        return getMethodNode(clazz.getInternalName(), mi.getName(), mi.getDesc(), mi.isWeaklyLinked(),
                mi.isStronglyLinked());
    }

    private MethodNode getMethodNode(MethodDependency dep) {
        return getMethodNode(dep.getOwner(), dep.getMethodName(), dep.getMethodDesc(), false, false);
    }

    /**
     * Finds reachable classes given the {@link TreeShakerMode} set when
     * creating this {@link DependencyGraph}.
     */
    public Set<String> findReachableClasses() {
        if (reachableNodes.isEmpty()) {
            for (ClassNode node : roots) {
                visitReachableNodes(node, reachableNodes);
            }
        }
        Set<String> classes = new HashSet<>();
        for (Node node : reachableNodes) {
            if (node instanceof ClassNode) {
                classes.add(((ClassNode) node).className);
            }
        }
        return classes;
    }

    /**
     * Finds reachable methods given {@link TreeShakerMode} set when creating
     * this {@link DependencyGraph}. The returned {@link Triple}s contain the
     * method owner, method name and method descriptor.
     */
    public Set<Triple<String, String, String>> findReachableMethods() {
        if (reachableNodes.isEmpty()) {
            for (ClassNode node : roots) {
                visitReachableNodes(node, reachableNodes);
            }
        }
        Set<Triple<String, String, String>> methods = new HashSet<>();
        for (Node node : reachableNodes) {
            if (node instanceof MethodNode) {
                MethodNode mnode = (MethodNode) node;
                methods.add(new ImmutableTriple<String, String, String>(mnode.owner, mnode.name, mnode.desc));
            }
        }
        return methods;
    }

    private void visitReachableNodes(Node node, Set<Node> visited) {
        if (!visited.contains(node)) {
            visited.add(node);
            for (Node child : node.strongEdges) {
                visitReachableNodes(child, visited);
            }
            for (Node child : node.weakEdges) {
                if (treeShakerMode == TreeShakerMode.conservative && child instanceof MethodNode) {
                    MethodNode mnode = (MethodNode) child;
                    if (!mnode.isWeaklyLinked()) {
                        visitReachableNodes(child, visited);
                    }
                } else if (treeShakerMode == TreeShakerMode.aggressive) {
                    if (child instanceof MethodNode) {
                        MethodNode mnode = (MethodNode) child;
                        if (mnode.isStronglyLinked() || (!mnode.isWeaklyLinked() && "<init>".equals(mnode.name))) {
                            visitReachableNodes(child, visited);
                        }
                }
                } else {
                    visitReachableNodes(child, visited);
                }
            }
        }
    }

    public TreeSet<String> getAllClasses() {
        TreeSet<String> result = new TreeSet<String>();
        for (ClassNode node : classNodes.values()) {
            result.add(node.className);
        }
        return result;
    }

    private static abstract class Node {
        private final Set<Node> weakEdges = new HashSet<>();
        private final Set<Node> strongEdges = new HashSet<>();

        public void addEgde(Node to, boolean weak) {
            (weak ? weakEdges : strongEdges).add(to);
        }
    }

    private static class ClassNode extends Node {
        private final String className;

        private ClassNode(String className) {
            this.className = className;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((className == null) ? 0 : className.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ClassNode other = (ClassNode) obj;
            if (className == null) {
                if (other.className != null) {
                    return false;
                }
            } else if (!className.equals(other.className)) {
                return false;
            }
            return true;
        }
    }

    private static class MethodNode extends Node {
        private final String owner;
        private final String name;
        private final String desc;
        private boolean weaklyLinked;
        private boolean stronglyLinked;

        private MethodNode(String owner, String name, String desc, boolean weaklyLinked, boolean stronglyLinked) {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.weaklyLinked = weaklyLinked;
            this.stronglyLinked = stronglyLinked;
        }

        public boolean isWeaklyLinked() {
            return weaklyLinked;
        }

        public boolean isStronglyLinked() {
            return stronglyLinked;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((desc == null) ? 0 : desc.hashCode());
            result = prime * result + ((name == null) ? 0 : name.hashCode());
            result = prime * result + ((owner == null) ? 0 : owner.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            MethodNode other = (MethodNode) obj;
            if (desc == null) {
                if (other.desc != null) {
                    return false;
                }
            } else if (!desc.equals(other.desc)) {
                return false;
            }
            if (name == null) {
                if (other.name != null) {
                    return false;
                }
            } else if (!name.equals(other.name)) {
                return false;
            }
            if (owner == null) {
                if (other.owner != null) {
                    return false;
                }
            } else if (!owner.equals(other.owner)) {
                return false;
            }
            return true;
        }
    }
}