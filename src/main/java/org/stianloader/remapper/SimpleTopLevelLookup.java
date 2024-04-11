package org.stianloader.remapper;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonBlocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.stianloader.remapper.HierarchyAwareMappingDelegator.TopLevelMemberLookup;

// FIXME the javadocs document an edge case (colliding interface declarations) that we may want to get rid of
/**
 * An implementation of {@link TopLevelMemberLookup} that works based on a statically computed map of
 * {@link MemberRef} to {@link MemberRealm}. The {@link MemberRealm} can be used to obtain the realm members,
 * that is the uses of a realm or the types where the member can be used in (note that in cases where two interfaces
 * define the same method, the set of realm members may overlap - at this point in time there is no good approach of handling
 * that edge-case besides praying it does not occur). A {@link MemberRealm} also documents the root definition of a member
 * (in case of the interface edge case, this is by default the most frequently implemented interface - if both interfaces
 * are implemented the same amount of times, it falls back to lexicographic ordering)
 */
public class SimpleTopLevelLookup implements TopLevelMemberLookup {

    /**
     * A member realm is a group of class members (so either fields or methods) with the same name
     * (but not necessarily the same descriptor or signature as it is permissible for subclasses to be more/less
     * strict on what they return or consume) but different classes. They form a unit (here referred as a <em>realm</em>)
     * as they must have the same name in both source and destination namespace, otherwise the method hierarchy
     * may be disjointed, resulting in mapping anomalies which may cause the application to no longer run as intended.
     *
     * <p>There may be multiple units with the same name, spanning different classes if they never intersect with each other.
     * However, one currently known edge-case (technically a bug) 
     */
    public static class MemberRealm {
        /**
         * All classes (written as an {@link Type#getInternalName() internal name}) where the member
         * is accessible from.
         */
        @NotNull
        @Unmodifiable
        public final Set<@NotNull String> realmMembers;

        /**
         * The top level declaration of the member realm. In case where two different interfaces might define the
         * same method, then the most frequently used interface is defined as the declarer.
         */
        @NotNull
        public final MemberRef rootDefinition;

        /**
         * Create a {@link MemberRealm} with the supplied {@link #rootDefinition} and {@link #realmMembers}.
         *
         * @param rootDefinition The top level declaration of the member realm.
         * @param realmMembers The list of all classes in which the member realm is active.
         */
        public MemberRealm(@NotNull MemberRef rootDefinition, @Unmodifiable @NotNull Set<@NotNull String> realmMembers) {
            this.rootDefinition = rootDefinition;
            this.realmMembers = realmMembers;
        }
    }

    /**
     * <p>A helper method that assembles a map where for a Map with a key of A with a Set as a value, a Map
     * is returned where the keys are the same but the Set also contains all values that would be addressable
     * by either A or a value of the Set represented by key A (even recursively).
     *
     * <p>A small generic method  I wrote since I am fed up writing such algorithms by hand all the time
     * (it is remarkable how often one needs to assemble dependency trees)
     *
     * <p> Not very performant, but should do the job as a temporary band-aid fix until I feel like improving the performance
     *
     * <p>Null set values are ignored and treated like an empty set (which in turn has the same behaviour as
     * there being no entry in the first place).
     *
     * @param <T> The type of the key as well as the values of the Set
     * @param input The input map to do the computation on. May not be modified while running the method
     * @return The output map which follows the semantics described above
     */
    @NotNull
    @Contract(pure = true, value = "null -> fail; !null -> new")
    @Unmodifiable
    private static <T> Map<T, @NotNull Set<T>> collect(@Unmodifiable @NotNull Map<T, ? extends @Nullable Set<T>> input) {
        Map<T, @NotNull Set<T>> mapOut = new HashMap<>();
        Queue<T> queue = new ArrayDeque<>();
        for (T key : input.keySet()) {
            Set<T> collected = new HashSet<>();
            queue.addAll(input.get(key));
            while (!queue.isEmpty()) {
                T queued = queue.remove();
                if (!collected.add(queued)) {
                    continue;
                }
                if (mapOut.containsKey(queued)) {
                    collected.addAll(mapOut.get(queued));
                    continue;
                }
                Set<T> set = input.get(queued);
                if (set != null) {
                    collected.addAll(set);
                    queue.addAll(set);
                }
            }
            mapOut.put(key, collected);
        }
        return Collections.unmodifiableMap(mapOut);
    }

    /**
     * Compute a map of class member to member realm relations from a list of ClassNodes.
     *
     * <p>The classnodes within the list should include everything that is relevant to the remapping process -
     * so the obfuscated application as well optionally the application that needs to be remapped (e.g. when remapping mods).
     *
     * <p>However, one currently known edge-case (technically a bug) is that when two interfaces define a method with the same
     * name and descriptor, then if the method hierarchies were to intersect, then the method realms would still be treated
     * separately, even though in reality they are the same realm.
     *
     * @param nodes The list of {@link ClassNode ClassNodes} to process. Members will only be in that list.
     * @return A {@link Map} that maps {@link MemberRef member references} to their respective {@link MemberRealm}.
     */
    @NotNull
    @Unmodifiable
    public static Map<@NotNull MemberRef, @NotNull MemberRealm> realmsOf(@Unmodifiable @NotNull List<@NotNull ClassNode> nodes) {
        // FIXME Inner classes can make use of private methods and fields without an accessor in never versions of java.
        // The question here is - does that also apply to overrides?
        Map<@NotNull String, Set<@NotNull String>> immediateChildren = new HashMap<>();
        Map<@NotNull String, ClassNode> nodeLookup = new HashMap<>();
        for (ClassNode node : nodes) {
            nodeLookup.put(node.name, node);
            BiFunction<String, Set<String>, Set<String>> combiner = (key, children) -> {
                if (children == null) {
                    children = new TreeSet<>();
                }
                children.add(node.name);
                return children;
            };
            immediateChildren.compute(node.superName, combiner);
            for (String interfaceName : node.interfaces) {
                immediateChildren.compute(interfaceName, combiner);
            }
        }

        Map<@NotNull String, @NotNull Set<@NotNull String>> allChildren = SimpleTopLevelLookup.collect(immediateChildren);

        // Ensure that we go by parent classes first, then go to the respective children
        TreeSet<@NotNull String> applyOrder = new TreeSet<>((e1, e0) -> {
            int hiOrder = allChildren.getOrDefault(e0, Collections.emptySet()).size() - allChildren.getOrDefault(e1, Collections.emptySet()).size();
            return hiOrder == 0 ? e1.compareTo(e0) : hiOrder;
        });

        // Ensure only obfuscated nodes are applied (e.g. ignore java/lang/Object).
        // This assumes that users won't try to map deobfuscated names, but that could be handled separately in the future
        applyOrder.addAll(nodeLookup.keySet());

        Map<@NotNull MemberRef, @NotNull MemberRealm> realms = new HashMap<>();
        for (String superType : applyOrder) {
            // Note: non-obfuscated classes won't be present here, nor will they be in the set of children classes
            ClassNode superNode = nodeLookup.get(superType);
            for (MethodNode superMethod : superNode.methods) {
                MemberRef myLoc = new MemberRef(superType, superMethod.name, superMethod.desc);
                if (realms.containsKey(myLoc)) {
                    // Someone (likely a supertype) already added the entry - safe to assume it is being overwritten by this class
                    continue;
                }
                if ((superMethod.access & Opcodes.ACC_STATIC) != 0 || (superMethod.access & Opcodes.ACC_PRIVATE) != 0) {
                    // ACC_STATIC or ACC_PRIVATE is set - no need for inheritance
                    // -> The list is as such immutable
                    realms.put(myLoc, new MemberRealm(myLoc, Collections.singleton(superType)));
                } else if ((superMethod.access & Opcodes.ACC_PUBLIC) != 0 || (superMethod.access & Opcodes.ACC_PROTECTED) != 0) {
                    // ACC_PUBLIC or ACC_PROTECTED is set (both behave the same as far as overrides are concerned)
                    // -> Exposed to all children (ACC_FINAL is irrelevant as far as I know, nor are bridge methods)
                    Set<@NotNull String> children = allChildren.getOrDefault(superType, Collections.emptySet());
                    Set<@NotNull String> realmMembers;
                    if (children.isEmpty()) {
                        realmMembers = Collections.singleton(superType);
                    } else {
                        realmMembers = new HashSet<>(children);
                        realmMembers.add(superType);
                    }
                    MemberRealm realm = new MemberRealm(myLoc, realmMembers);
                    realms.put(myLoc, realm);
                    for (String child : children) {
                        realms.put(new MemberRef(child, superMethod.name, superMethod.desc), realm);
                    }
                } else {
                    // package-protected access (no explicit access flags set) - this is where it gets more complicated
                    // as children could expand the access to ACC_PUBLIC or ACC_PROTECTED
                    // However, one still needs to be aware that in order for ACC_PUBLIC or ACC_PROTECTED to work in that way,
                    // the class that widens the access must be in the same package as the defining class.
                    Set<@NotNull String> realmAccess = new TreeSet<>();
                    Set<@NotNull String> children = allChildren.getOrDefault(superType, Collections.emptySet());
                    int lastSlashSuper = superType.lastIndexOf('/');
                    realmAccess.add(superType);
                    for (String child : children) {
                        int lastSlashChild = child.lastIndexOf('/');
                        if (lastSlashChild != lastSlashSuper || !child.regionMatches(0, superType, 0, lastSlashChild)) {
                            continue;
                        }
                        realmAccess.add(child);
                        ClassNode childNode = nodeLookup.get(child);
                        if (childNode == null) {
                            // Won't happen, but doesn't hurt to have it in there regardless
                            continue;
                        }
                        for (MethodNode method : childNode.methods) {
                            if (!method.name.equals(superMethod.name) || !method.desc.equals(superMethod.desc)) {
                                continue;
                            }
                            if ((method.access & Opcodes.ACC_PUBLIC) != 0 || (method.access & Opcodes.ACC_PROTECTED) != 0) {
                                // Widened access
                                realmAccess.addAll(allChildren.getOrDefault(child, Collections.emptySet()));
                            }
                        }
                    }

                    MemberRealm realm = new MemberRealm(myLoc, realmAccess);
                    for (String realmType : realmAccess) {
                        realms.put(new MemberRef(realmType, superMethod.name, superMethod.desc), realm);
                    }
                }
                if (!realms.containsKey(myLoc)) {
                    throw new IllegalStateException("Reference not in list of realms: " + myLoc);
                }
            }
            for (FieldNode superField : superNode.fields) {
                MemberRef myLoc = new MemberRef(superType, superField.name, superField.desc);
                if (realms.containsKey(myLoc)) {
                    // Someone (likely a supertype) already added the entry - safe to assume it is being overwritten by this class
                    continue;
                }
                if ((superField.access & Opcodes.ACC_STATIC) != 0 || (superField.access & Opcodes.ACC_PRIVATE) != 0) {
                    // ACC_STATIC or ACC_PRIVATE is set - no need for inheritance
                    // -> The list is as such immutable
                    realms.put(myLoc, new MemberRealm(myLoc, Collections.singleton(superType)));
                } else if ((superField.access & Opcodes.ACC_PUBLIC) != 0 || (superField.access & Opcodes.ACC_PROTECTED) != 0) {
                    // ACC_PUBLIC or ACC_PROTECTED is set (both behave the same as far as overrides are concerned)
                    // -> Exposed to all children (ACC_FINAL is irrelevant as far as I know, nor are bridge methods)
                    Set<@NotNull String> children = allChildren.getOrDefault(superType, Collections.emptySet());
                    Set<@NotNull String> realmMembers;
                    if (children.isEmpty()) {
                        realmMembers = Collections.singleton(superType);
                    } else {
                        realmMembers = new HashSet<>(children);
                        realmMembers.add(superType);
                    }
                    MemberRealm realm = new MemberRealm(myLoc, realmMembers);
                    realms.put(myLoc, realm);
                    for (String child : children) {
                        realms.put(new MemberRef(child, superField.name, superField.desc), realm);
                    }
                } else {
                    // package-protected access (no explicit access flags set) - this is where it gets more complicated
                    // as children could expand the access to ACC_PUBLIC or ACC_PROTECTED
                    // However, one still needs to be aware that in order for ACC_PUBLIC or ACC_PROTECTED to work in that way,
                    // the class that widens the access must be in the same package as the defining class.
                    Set<@NotNull String> realmAccess = new TreeSet<>();
                    Set<@NotNull String> children = allChildren.getOrDefault(superType, Collections.emptySet());
                    int lastSlashSuper = superType.lastIndexOf('/');
                    realmAccess.add(superType);
                    for (String child : children) {
                        int lastSlashChild = child.lastIndexOf('/');
                        if (lastSlashChild != lastSlashSuper || !child.regionMatches(0, superType, 0, lastSlashChild)) {
                            continue;
                        }
                        realmAccess.add(child);
                        ClassNode childNode = nodeLookup.get(child);
                        if (childNode == null) {
                            // Won't happen, but doesn't hurt to have it in there regardless
                            continue;
                        }
                        for (FieldNode field : childNode.fields) {
                            if (!field.name.equals(superField.name) || !field.desc.equals(superField.desc)) {
                                continue;
                            }
                            if ((field.access & Opcodes.ACC_PUBLIC) != 0 || (field.access & Opcodes.ACC_PROTECTED) != 0) {
                                // Widened access
                                realmAccess.addAll(allChildren.getOrDefault(child, Collections.emptySet()));
                            }
                        }
                    }

                    MemberRealm realm = new MemberRealm(myLoc, realmAccess);
                    for (String realmType : realmAccess) {
                        realms.put(new MemberRef(realmType, superField.name, superField.desc), realm);
                    }
                }
                if (!realms.containsKey(myLoc)) {
                    throw new IllegalStateException("Reference not in list of realms: " + myLoc);
                }
            }
        }

        return Collections.unmodifiableMap(realms);
    }

    @Unmodifiable
    @NotNull
    private final Map<MemberRef, MemberRealm> realms;

    /**
     * Create a {@link SimpleTopLevelLookup} based on a list of {@link ClassNode ClassNodes} that are used to
     * compute the member realms.
     *
     * <p>JDK Classes or library classes that are irrelevant to the remapping process should be left out for performance
     * (especially memory-related) reasons.
     *
     * @param applicationClasses The list of classes to analyze
     */
    public SimpleTopLevelLookup(@Unmodifiable @NotNull List<@NotNull ClassNode> applicationClasses) {
        this(SimpleTopLevelLookup.realmsOf(applicationClasses));
    }

    /**
     * Create a {@link SimpleTopLevelLookup} from an immutable map of {@link MemberRef member references} to
     * their respective {@link MemberRealm}.
     *
     * <p>JDK Classes or library classes that are irrelevant to the remapping process should be left out for performance
     * (especially memory-related) reasons.
     *
     * @param realms The map to use to lookup the realm of members.
     */
    public SimpleTopLevelLookup(@Unmodifiable @NotNull Map<MemberRef, MemberRealm> realms) {
        this.realms = realms;
    }

    @Override
    @NotNull
    @Contract(pure = true, value = "!null -> !null; null -> fail")
    @NonBlocking
    public MemberRef getDefinition(@NotNull MemberRef reference) {
        MemberRealm realm = this.realmOf(reference);
        if (realm == null) {
            return reference;
        }
        return realm.rootDefinition;
    }

    /**
     * Lookup the {@link MemberRealm} of a {@link MemberRef}.
     *
     * @param reference The reference to look up
     * @return The corresponding {@link MemberRef}, or null if not found.
     */
    @Nullable
    @Contract(pure = true)
    public MemberRealm realmOf(@NotNull MemberRef reference) {
        return this.realms.get(reference);
    }
}
