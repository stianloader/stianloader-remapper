package org.stianloader.remapper;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A simple implementation of {@link MappingLookup} which can be mutated via the implemented {@link MappingSink}
 * interface.
 *
 * <h2>Thread safety and concurrency</h2>
 *
 * <p>Instances of this class can be shared across multiple threads for as long as no instance is mutated via the
 * methods provided by {@link MappingSink}. More specifically, the thread safety and concurrency semantics of this class
 * are identical to the semantics of {@link HashMap}. It is as such not recommended to make use of this class
 * in asynchronous or concurrent environments.
 *
 * <p>Instances of this class can be freely shared by multiple {@link Remapper} instances within the bounds of
 * aforementioned restrictions.
 *
 * <h2>Method overloading, inheritance and other remapping restrictions</h2>
 *
 * <p><b>Instances of this class do not handle method overloading and inheritance overall</b> - that is every method
 * and field is considered to be independent and is considered to exist as-is. Thus,
 * {@link MappingSink#remapMember(MemberRef, String)} must be called for each child class that might override
 * the method. Furthermore, {@link #remapMember(MemberRef, String)} should also be called even if an override
 * does not explicitly occur as it is still possible for java-bytecode to refer the members that might not exist
 * as-is but exist in the parent class.
 *
 * <p>This implementation prohibits renaming the initialisation methods &lt;init&gt; and &lt;clinit&gt; or renaming
 * methods to this name - fields may be renamed from and to this name regardless in case it is required for more
 * advanced obfuscation.
 *
 * <p>When making use of this lookup implementation, layered mappings should either be implemented by squashing/chaining
 * multiple {@link MappingLookup} instances together in an uber-lookup instance or by calling
 * {@link Remapper#remapNode(org.objectweb.asm.tree.ClassNode, StringBuilder)} at least once per mapping layer.
 * It is generally recommended to go with the former approach as that is the most performance-friendly option, but the latter
 * approach can also work.
 *
 * <p>This implementation does not verify the integrity of the remapping requests, so illegal names might be emitted
 * by this lookup instance. Similarly, this implementation does to scan for mapping collisions.
 * Attempting to map a class or class member to two different names will cause the latter remapping request to override
 * the former, omitting no warning nor other indicator while doing so.
 */
public class SimpleMappingLookup implements MappingLookup, MappingSink {

    private final Map<String, String> classNames = new HashMap<>();
    private final Map<MemberRef, String> memberNames = new HashMap<>();

    @SuppressWarnings("null") // The default value is non-null so #getOrDefault cannot return a null value
    @Override
    @NotNull
    public String getRemappedClassName(@NotNull String srcName) {
        return this.classNames.getOrDefault(srcName, srcName);
    }

    @Override
    @Nullable
    public String getRemappedClassNameFast(@NotNull String srcName) {
        return this.classNames.get(srcName);
    }

    @SuppressWarnings("null")
    @Override
    @NotNull
    public String getRemappedFieldName(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc) {
        return this.memberNames.getOrDefault(new MemberRef(srcOwner, srcName, srcDesc), srcName);
    }

    @SuppressWarnings("null")
    @Override
    @NotNull
    public String getRemappedMethodName(@NotNull String srcOwner, @NotNull String srcName, @NotNull String srcDesc) {
        return this.memberNames.getOrDefault(new MemberRef(srcOwner, srcName, srcDesc), srcName);
    }

    @Contract(mutates = "this", pure = false, value = "_, _ -> this")
    @Override
    @NotNull
    public SimpleMappingLookup remapClass(@NotNull String srcName, @NotNull String dstName) {
        this.classNames.put(srcName, dstName);
        return this;
    }

    @Contract(mutates = "this", pure = false, value = "_, _ -> this")
    @Override
    @NotNull
    public SimpleMappingLookup remapMember(@NotNull MemberRef srcRef, @NotNull String dstName) {
        if (srcRef.getDesc().codePointAt(0) == '(') {
            if (dstName.equals("<init>") || dstName.equals("<clinit>")) {
                if (dstName.equals(srcRef.getName())) {
                    // This would be a NOP mapping anyways. I don't know why one would do this, but I cannot really see
                    // anything acutely wrong with it either so we'll let it slide to make it easier for the API consumer.
                    return this;
                }
                throw new IllegalArgumentException("Illegal destination name for src member " + srcRef + ": " + dstName);
            } else if (srcRef.getName().equals("<init>") || srcRef.getName().equals("<clinit>")) {
                throw new IllegalArgumentException("Illegal attempt at renaming src member " + srcRef + " to " + dstName);
            }
        }
        this.memberNames.put(srcRef, dstName);
        return this;
    }
}
