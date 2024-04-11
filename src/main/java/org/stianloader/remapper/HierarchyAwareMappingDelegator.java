package org.stianloader.remapper;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonBlocking;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link HierarchyAwareMappingDelegator} is an implementation of {@link MappingLookup} that binds mappings of class members
 * provided by {@link MappingSink#remapMember(MemberRef, String)} to their top level definition. The actual storage of mappings
 * are handled by a defined delegated {@link MappingLookup} (which also implements the {@link MappingSink} instance) of type
 * <em>T</em>.
 *
 * <p>The main point in using this delegator is in ensuring that disjointed method overrides do not happen, although this
 * {@link MappingLookup} still allows for name collisions to occur (unless the delegate prohibits it - read up on the
 * behaviour of delegates)
 *
 * <h2>Thread safety and concurrency</h2>
 *
 * The thread safety and concurrency guarantees are only as strong as the components of the instance of the class.
 * If the {@link TopLevelMemberLookup} and the used delegate are both thread-safe, then this class is thread-safe, too.
 *
 * In case of doubt, don't use instances of this class in an concurrent environment.
 *
 * @param <T> The type of {@link MappingLookup} to use as a the delegate (it must implement {@link MappingSink}, too)
 */
public class HierarchyAwareMappingDelegator<T extends MappingLookup & MappingSink> implements MappingLookup, MappingSink {

    /**
     * Interface for obtaining the root definition of a member.
     * The root definition is then used by a {@link HierarchyAwareMappingDelegator} to map mappings to a common member
     * within a group of similar methods/fields.
     *
     * <h2>Thread safety and concurrency</h2>
     *
     * <p>All implementations of {@link TopLevelMemberLookup} provided by stianloader-remapper are immutable and thus fully
     * thread safe, unless explicitly stated otherwise, however thread safety is not necessarily a required aspect for
     * implementors, although it is recommended to take advantage of it in order to reduce the required work for those that
     * wish to employ concurrency in their software (which could be you!).
     */
    public interface TopLevelMemberLookup {

        /**
         * Obtain the root-level definition of a member.
         *
         * <p>Beware that this method may be called for fields, too.
         * It is imperative that the declaration of fields is also followed as it is possible for javac to emit
         * GETFIELD requests where the owner does not match the class where the field in question is defined -
         * this for example might be produced by anonymous enum members.
         *
         * <p>Implementors are free to handle bridge methods as they please - the only requirement is that
         * they do it persistently and that implementors should be aware that this would cause the bridge methods
         * to potentially have the same name as the method it bridges to. This interface does not mandate any
         * behaviour towards bridge methods and they may be ignored completely (this most likely won't have
         * any notable repercussions anyways unless the codebase in question uses a lot of generics - however,
         * generics are likely to be absent anyways in which case searching for bridge methods may be a bit
         * superfluous).
         *
         * <p>Changing the computational type of the descriptor of the member
         * is not permitted and will result in an exception being thrown in
         * {@link HierarchyAwareMappingDelegator#remapMember(MemberRef, String)}.
         * However, outside of that changing the descriptor of a method is permitted, as might
         * for example be needed for bridge methods.
         *
         * <p>Implementations should return the original source reference for classes or methods they do not know.
         * Under rare cases it is possible for methods to change their descriptor in the JRE, so this fact shouldn't be
         * ignored even if the implementation should handle JRE classes (this specification recommends against
         * handling inheritance beyond the application that needs to be remapped).
         *
         * <p>Returning a {@link MemberRef} in a non-source namespace is permissible, but it is highly recommended against doing
         * so. That being said, the input {@link MemberRef} MUST be in the source namespace.
         *
         * <h4>Performance</h4>
         *
         * Stianloader-remapper expects calls to this method to be non-blocking and quick to process.
         * Thus implementors should use caching if appropriate as it is reasonable to expect that this method will
         * be called multiple times (sometimes even consecutively) with the same input argument - even if the
         * returned value should be the same across all calls.
         *
         * @param reference The reference of the member in the source namespace.
         * @return The reference of the declaration of the member
         */
        @NotNull
        @Contract(pure = true, value = "!null -> !null; null -> fail")
        @NonBlocking
        MemberRef getDefinition(@NotNull MemberRef reference);
    }

    @NotNull
    private final TopLevelMemberLookup definitionLookup;
    @NotNull
    private final T delegate;

    /**
     * Creates a {@link HierarchyAwareMappingDelegator} with the delegate <code>lookupDelegate</code> of type T
     * and a given {@link TopLevelMemberLookup}.
     *
     * @param lookupDelegate The object to which all lookup and sink calls are delegated to.
     * @param definitionLookup The lookup that is used to acquire the top-level definition of a class member.
     */
    public HierarchyAwareMappingDelegator(@NotNull T lookupDelegate, @NotNull TopLevelMemberLookup definitionLookup) {
        this.definitionLookup = definitionLookup;
        this.delegate = lookupDelegate;
    }

    @Override
    @NotNull
    public HierarchyAwareMappingDelegator<T> remapClass(@NotNull String srcName, @NotNull String dstName) {
        this.delegate.remapClass(srcName, dstName);
        return this;
    }

    @Override
    @NotNull
    public HierarchyAwareMappingDelegator<T> remapMember(@NotNull MemberRef srcRef, @NotNull String dstName) {
        MemberRef topLevel = this.definitionLookup.getDefinition(srcRef);
        if (srcRef.getDesc().codePointAt(0) != topLevel.getDesc().codePointAt(0)) {
            throw new IllegalStateException("Definition lookup altered the type of member from " + srcRef.getDesc() + " to " + topLevel.getDesc() + ", which is not permitted.");
        }
        this.delegate.remapMember(srcRef, dstName);
        return this;
    }

    @Override
    @NotNull
    public String getRemappedClassName(@NotNull String srcName) {
        return this.delegate.getRemappedClassName(srcName);
    }

    @Override
    @Nullable
    public String getRemappedClassNameFast(@NotNull String srcName) {
        return this.delegate.getRemappedClassNameFast(srcName);
    }

    @Override
    @NotNull
    public String getRemappedFieldName(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc) {
        MemberRef topLevel = this.definitionLookup.getDefinition(new MemberRef(srcOwner, srcName, srcDesc));
        if (srcDesc.codePointAt(0) != topLevel.getDesc().codePointAt(0)) {
            throw new IllegalStateException("Definition lookup altered the type of member from " + srcDesc + " to " + topLevel.getDesc() + ", which is not permitted.");
        }
        return this.delegate.getRemappedFieldName(topLevel.getOwner(), topLevel.getName(), topLevel.getDesc());
    }

    @Override
    @NotNull
    public String getRemappedMethodName(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc) {
        MemberRef topLevel = this.definitionLookup.getDefinition(new MemberRef(srcOwner, srcName, srcDesc));
        if (srcDesc.codePointAt(0) != topLevel.getDesc().codePointAt(0)) {
            throw new IllegalStateException("Definition lookup altered the type of member from " + srcDesc + " to " + topLevel.getDesc() + ", which is not permitted.");
        }
        return this.delegate.getRemappedMethodName(topLevel.getOwner(), topLevel.getName(), topLevel.getDesc());
    }
}
