package org.stianloader.remapper;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.ApiStatus.AvailableSince;
import org.jetbrains.annotations.ApiStatus.ScheduledForRemoval;
import org.objectweb.asm.tree.ClassNode;

/**
 * A simple implementation of {@link HierarchyAwareMappingDelegator} that uses a {@link SimpleMappingLookup}
 * as a delegate. The inheritance hierarchy is provided by a {@link TopLevelMemberLookup}, which can either be
 * supplied directly, computed
 *
 * <h2>Thread safety and concurrency</h2>
 *
 * This implementation is not thread-safe as-is due to {@link SimpleMappingLookup} not being thread-safe either.
 * It may be safe to use in a read-only manner within concurrent environments, however it is recommended against doing so.
 * For more information, read the documentation of {@link SimpleMappingLookup} and {@link HierarchyAwareMappingDelegator}
 * as well as the appropriate implementation of {@link TopLevelMemberLookup} (unless you use your own instance it is likely to be
 * thread-safe, to the extend specified).
 */
public class SimpleHierarchyAwareMappingLookup extends HierarchyAwareMappingDelegator<SimpleMappingLookup> {

    /**
     * Create a {@link SimpleHierarchyAwareMappingLookup} with an empty {@link SimpleMappingLookup}.
     * The used {@link TopLevelMemberLookup} is a {@link SimpleTopLevelLookup} which is created using the provided list
     * of classnodes. This is mostly a convenience method.
     *
     * <p>JDK Classes or library classes that are irrelevant to the remapping process should be left out for performance
     * (especially memory-related) reasons.
     *
     * @param nodes The nodes to analyze.
     * @see SimpleTopLevelLookup#SimpleTopLevelLookup(java.util.List)
     * @since 0.3.0
     */
    @AvailableSince("0.3.0")
    public SimpleHierarchyAwareMappingLookup(@NotNull @Unmodifiable Iterable<@NotNull ClassNode> nodes) {
        this(new SimpleMappingLookup(), new SimpleTopLevelLookup(nodes));
    }

    /**
     * Create a {@link SimpleHierarchyAwareMappingLookup} with an empty {@link SimpleMappingLookup}.
     * The used {@link TopLevelMemberLookup} is a {@link SimpleTopLevelLookup} which is created using the provided list
     * of classnodes. This is mostly a convenience method.
     *
     * <p>JDK Classes or library classes that are irrelevant to the remapping process should be left out for performance
     * (especially memory-related) reasons.
     *
     * @param nodes The list of nodes to analyze.
     * @see SimpleTopLevelLookup#SimpleTopLevelLookup(java.util.List)
     * @deprecated use {@link #SimpleHierarchyAwareMappingLookup(Iterable)} instead. This method only exists for backwards compatibility.
     */
    @Deprecated
    @ScheduledForRemoval
    public SimpleHierarchyAwareMappingLookup(@NotNull @Unmodifiable List<@NotNull ClassNode> nodes) {
        this((Iterable<@NotNull ClassNode>) nodes);
    }

    /**
     * Generic constructor that takes in a {@link SimpleMappingLookup} and a {@link TopLevelMemberLookup}.
     * Use this constructor if a specific lookup instance should be reused.
     *
     * @param delegate The {@link SimpleMappingLookup} to which lookup and sink calls are forwarded to.
     * @param topLevelLookup The {@link TopLevelMemberLookup} used by the {@link HierarchyAwareMappingDelegator}.
     */
    public SimpleHierarchyAwareMappingLookup(@NotNull SimpleMappingLookup delegate, @NotNull TopLevelMemberLookup topLevelLookup) {
        super(delegate, topLevelLookup);
    }
}
