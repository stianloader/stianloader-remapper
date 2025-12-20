package org.stianloader.remapper;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus.AvailableSince;
import org.objectweb.asm.Type;

/**
 * A {@link MappingLookup} stores mappings which can be obtained by the specified accessor methods.
 * While the {@link MappingLookup} interface on it's own is strictly read-only, implementations may mutate the mappings
 * between calls and are allowed to implement interfaces that introduce mapping writing properties such as {@link MappingSink}.
 * That being said, callers should generally be safe in that assumption that one an element maps to a certain value, the
 * association will stay over a longer duration.
 *
 * <p>Concrete implements of this interface such as {@link HierarchyAwareMappingDelegator} or {@link SimpleMappingLookup}, which
 * implement {@link MappingSink}, should thus not be mutated after performing the first map requests. However, implementations
 * (which include the implementations listed previously) are not required to ensure such immutability.
 *
 * <h2>Method overloading, inheritance and other remapping restrictions</h2>
 *
 * <p>Implementations of {@link MappingLookup} are generally encouraged to implement inheritance, and may also support overloading
 * if chosen. However, {@link MappingLookup} does not explicitly define a behaviour here and implementations such as
 * {@link SimpleMappingLookup} may choose to handle inheritance and method overloading at all. For more insight as to
 * why inheritance and overloading may be beneficial, see the appropriate section in {@link Remapper}.

 * <p>The methods provided by {@link MappingLookup} should avoid returning illegal names wherever possible.
 * However, beware that {@link #getRemappedMethodName(String, String, String)} is likely to be called with the static
 * initialization block &lt;clinit&gt; or the constructor &lt;init&gt;, which should not map to anything but these names.
 * This means that implementations of {@link MappingLookup} should not try to be smart and refuse seemingly illegal mapping
 * requests (one can still return the name of the member in the source namespace though) - this behaviour is employed
 * for performance reasons
 *
 * <p>If an implementation of {@link MappingLookup} chooses to handle method hierarchies, it must be aware that similar
 * hierarchy structures exist for fields, too. Furthermore, it should be aware that
 * {@link #getRemappedFieldName(String, String, String)} and {@link #getRemappedMethodName(String, String, String)}
 * may be called with member it may not know (this can be the case with different JDKs - even older ones! Breaking
 * changes did happen in the past even within the public APIs).
 *
 * <h2>Concurrency and thread safety</h2>
 *
 * <p>While a {@link MappingLookup} instance on it's own is read-only and thus usually fine to use in concurrent environments,
 * there may be implementations that are either not completely in-memory or allow mutation which thus may compromise any
 * thread safety guarantees. As such the usual advice applies here, too: Consult the manual of your implementation regarding
 * thread safety and prefer to err on the side of caution if not known. The {@link MappingLookup} interface otherwise makes
 * no thread safety and concurrency guarantees.
 */
public interface MappingLookup {

    /**
     * Obtains the name of the class in the destination namespace (or in laymen's
     * terms the remapped name). If the class name in the source namespace is equal
     * to the name in the destination namespace (e.g. because there is no mapping),
     * then the name in the source namespace must be returned.
     *
     * <p>It is valid to have {@link MappingLookup#getRemappedClassNameFast(String)}
     * and {@link MappingLookup#getRemappedClassName(String)} be the same implementation for as long as
     * {@link MappingLookup#getRemappedClassName(String)} never returns null, however doing so might incur
     * a slight performance reduction.
     *
     * <p>srcName and the returned value are in the same format as {@link Type#getInternalName()}.
     *
     * @param srcName The name of the class in the source namespace
     * @return The mapped name in the destination namespace, or the name in the source namespace if no mapping
     *         exists.
     */
    @NotNull
    @Contract(pure = true)
    String getRemappedClassName(@NotNull String srcName);

    /**
     * Obtains the name of the class in the destination namespace (or in laymen's
     * terms the remapped name). If the class name in the source namespace is equal
     * to the name in the destination namespace (e.g. because there is no mapping),
     * then null <em>can</em> be returned for performance reasons.
     *
     * <p>This is especially done when some string manipulation can be skipped if the
     * source name equals the destination name.
     *
     * <p>It is valid to have {@link MappingLookup#getRemappedClassNameFast(String)}
     * and {@link MappingLookup#getRemappedClassName(String)} be the same implementation for as long as
     * {@link MappingLookup#getRemappedClassName(String)} never returns null, however doing so might incur
     * a slight performance reduction.
     *
     * <p>srcName and the returned value are in the same format as {@link Type#getInternalName()}.
     *
     * @param srcName The name of the class in the source namespace
     * @return The name in the destination namespace, or null if the name is the
     *         same as the source namespace
     */
    @Nullable
    @Contract(pure = true)
    default String getRemappedClassNameFast(@NotNull String srcName) {
        return this.getRemappedClassName(srcName);
    }

    /**
     * Obtains the name of a field in the destination namespace (or the mapped name).
     * If the field is not known or if no mapping exists for the field, then the name in the
     * source namespace must be returned (that is the srcName parameter).
     *
     * <p>srcOwner is in the same format as {@link Type#getInternalName()}.
     *
     * <p>Implementations of this interface are encouraged to not throw if possible.
     *
     * @param srcOwner The name of the owner of the member in the source namespace
     * @param srcName The name of the member in the source namespace
     * @param srcDesc The descriptor of the member (where as classes are all in the source namespace)
     * @return The mapped name in the destination namespace, or the name in the source namespace if no mapping
     *         exists.
     */
    @NotNull
    @Contract(pure = true)
    String getRemappedFieldName(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc);

    /**
     * Obtains the name of a field in the destination namespace (or the mapped name).
     * If the field is not known or if no mapping exists for the field, then the name in the
     * source namespace must be returned (that is the srcName parameter).
     *
     * <p>srcOwner is in the same format as {@link Type#getInternalName()}.
     *
     * <p>Implementations of this interface are encouraged to not throw if possible.
     *
     * @param srcOwner The name of the owner of the member in the source namespace
     * @param srcName The name of the member in the source namespace
     * @param srcDesc The descriptor of the member (where as classes are all in the source namespace)
     * @return The mapped name in the destination namespace, or the name in the source namespace if no mapping
     *         exists.
     */
    @NotNull
    @Contract(pure = true)
    String getRemappedMethodName(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc);

    /**
     * Obtains the name of the parameter in the destination namespace (or in laymen's
     * terms the remapped name of the parameter). Should the name of the parameter
     * not be known in the destination namespace, then {@code null} is returned.
     * This is the default behaviour of this method.
     *
     * <p>The {@code paramIndex} argument is 0-indexed. It does not distinguish
     * between computational categories. Or in other words, for both {@code (IZ)V} and
     * {@code (JZ)V} the boolean parameter corresponds to a {@code paramIndex} value
     * of 1, while the integer and long have a value of 0.
     *
     * <p>Callers may throw an {@link IndexOutOfBoundsException} if {@code paramIndex}
     * is below 0 or above the amount of parameters as discernible by {@code srcDesc}.
     * As such, this method cannot be used to remap LVT entries, even though they are
     * quite similar in structure. This is because LVT entries can be reused.
     * By default this method does not do any checks whether {@code paramIndex} is
     * a valid value. This is done for performance reasons.
     *
     * <p>Implementations of this method are encouraged to not throw if possible, unless
     * for reasons described above. While it theoretically makes sense to throw an
     * exception in cases where a method is known to not exist, this is generally
     * rarely the case.
     *
     * <p>If this {@link MappingLookup} instance does not support remapping parameters,
     * then {@code null} should be returned unconditionally, without throwing outside
     * of aforementioned reasons.
     *
     * @param srcOwner The name of the owner of the member in the source namespace.
     * @param srcName The name of the member in the source namespace.
     * @param srcDesc The descriptor of the member (where as classes are all in the source namespace).
     * @param paramIndex The index of the parameter, excluding the implied {@code this} parameter.
     * @param isStaticMethod Whether the method is static. Used when working with mappings formats such as Enigma, TINYv2, or TSRG.
     * @return The name of the parameter in the destination namespace, or {@code null} if not applicable.
     * @since 0.2.0-a20251220
     */
    @Nullable
    @Contract(pure = true)
    @AvailableSince("0.2.0-a20251220")
    public default String getRemappedParameterName(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc, int paramIndex, boolean isStaticMethod) {
        return null;
    }
}
