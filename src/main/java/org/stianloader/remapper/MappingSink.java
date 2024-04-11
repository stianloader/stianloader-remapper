package org.stianloader.remapper;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A {@link MappingSink} is an object that accepts mappings of classes or class members.
 * A {@link MappingSink} by itself is strictly write-only, that is it only accepts changes
 * but provides no means of obtaining the current state.
 *
 * <p>The interface does not specify what the mapping changes are used for - they may be used for a
 * {@link MappingLookup} instance, but they may also be used to write mappings to a file - or may
 * delegate to multiple {@link MappingSink} instances at the same time and do multiple actions at once.
 *
 * <p>Implementations of {@link MappingSink} are not required to ensure that remapping requests are
 * sensical or valid overall. The burden for this lies on the caller.
 *
 * <p>Both fields and methods are renamed using {@link #remapMember(MemberRef, String)}. Classes are renamed
 * using {@link #remapClass(String, String)}. It is not yet possible to create inner classes (as in
 * {@link ClassNode#innerClasses}), rename inner classes (that is {@link InnerClassNode#innerName}), rename
 * method parameters (both in LVT and via {@link MethodNode#parameters}) or rename local variables
 * (via {@link MethodNode#localVariables}). However, it is possible that in the future such functionality
 * is added.
 *
 * <p>As {@link MappingSink} has no (direct) way of obtaining the result of a remapping request, implementors are
 * permitted to ignore remapping requests at will. MappingSinks that also implement {@link MappingLookup} should
 * generally not ignore requests however - but may do so if appropriate. When encountering illegal requests,
 * implementors are encourages to throw an exception (usually {@link IllegalArgumentException}) over plainly discarding
 * a request in order to aid in troubleshooting bugs or otherwise unintended behaviour.
 *
 * <h2>Concurrency and thread safety</h2>
 *
 * <p>The {@link MappingSink} interface makes no guarantees on the behaviour of implementations when
 * it comes to concurrent environment. Implementations may be thread safe and may be usable in concurrent
 * environments, but they might also not be. In case of doubt it is generally recommended to assume
 * that an implementation is not thread-safe.
 *
 * <p>The burden of making sure that thread safety constraints are correctly handled falls on the API
 * consumer.
 */
public interface MappingSink {

    /**
     * Remaps a specific class. Do note that this makes no promises on inner classes.
     * That is using the dollar ('$') sign or a dot ('.') has no effect on the internal
     * arrangement of inner class nodes. Inner classes are as of now not handled by the
     * {@link Remapper} implementation and if implemented would be independent of this
     * method.
     *
     * <p>Class names are defined via the {@link Type#getInternalName() internal name}
     * of a class - that is forward slashes ('/') are used instead of dots ('.').
     * Usage of dots or semicolons (';') are completely forbidden as they are not allowed
     * in the JVMS within internal names of classes.
     *
     * <p>Implementations are not required to ensure that the class names make sense
     * or are valid - the burden of verification falls upon the caller.
     *
     * @param srcName The name of the member in the source namespace (that is the unmapped name)
     * @param dstName The name of the member in the destination namespace (that is the mapped name)
     * @return The current {@link MappingSink} instance (i.e. this), for chaining
     */
    @Contract(mutates = "this", pure = false, value = "_, _ -> this")
    @NotNull
    MappingSink remapClass(@NotNull String srcName, @NotNull String dstName);

    /**
     * Remaps a class member - that is either a field or a method.
     * Whether the member is a field or a method can be easily discerned by looking at the first character
     * of the {@link MemberRef#getDesc() descriptor} of the {@link MemberRef}. If the first character is
     * a '(', then it is a method - otherwise it is a field.
     *
     * <p>As no non-method descriptor can start with '(', this check suffices and reduces the work required
     * for bridging between stianloader-remapper and other mapping formats or software.
     *
     * @param srcRef The reference of the member in the source namespace (i.e. the unmapped member)
     * @param dstName The name of the member in the target namespace (i.e. the mapped member name)
     * @return The current {@link MappingSink} instance, for chaining
     */
    @Contract(mutates = "this", pure = false, value = "_, _ -> this")
    @NotNull
    MappingSink remapMember(@NotNull MemberRef srcRef, @NotNull String dstName);
}
